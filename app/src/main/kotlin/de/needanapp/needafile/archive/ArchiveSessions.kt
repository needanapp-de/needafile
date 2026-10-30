package de.needanapp.needafile.archive

import android.content.Context
import de.needanapp.needafile.R
import de.needanapp.needafile.errors.LocalizedException
import de.needanapp.needafile.remote.BrowserFile
import de.needanapp.needafile.remote.RemoteConnections
import de.needanapp.needafile.remote.getRemoteParentPath
import de.needanapp.needafile.remote.getRemoteServerId
import de.needanapp.needafile.remote.getRemoteServerPath
import de.needanapp.needafile.remote.isRemotePath
import de.needanapp.needafile.remote.joinRemotePath
import java.io.File
import java.security.MessageDigest

/**
 * The archives currently browsed, each opened once and kept open for quick listing; the least recently used are
 * closed when more are open. Archives on servers or inside other archives are copied into the cache first.
 * Passwords given for an archive are kept in memory while the app runs.
 */
object ArchiveSessions {
    private const val MAX_OPEN = 4
    private const val CACHE_FOLDER = "archives"
    private const val JOINED_PREFIX = "joined_"

    private class Session(val fileSystem: ArchiveFileSystem, val localFile: File, val stamp: Pair<Long, Long>, val copyFolder: File?)

    private val sessions = object : LinkedHashMap<String, Session>(MAX_OPEN, 0.75f, true) {}
    private val passwords = HashMap<String, String>()
    private var cacheCleaned = false

    /** The opened archive at [location] (a phone path, a server path or a path inside another archive). */
    fun get(context: Context, location: String): ArchiveFileSystem {
        synchronized(this) {
            cleanCacheOnce(context)
            sessions[location]?.let { session ->
                // a phone archive that changed meanwhile is read again
                if (session.copyFolder != null || stampOf(session.localFile) == session.stamp) return session.fileSystem
                close(location)
            }
        }

        // copying and opening may take long, they happen outside the lock
        val (localFile, copyFolder) = localFileFor(context, location)
        val name = location.substringAfterLast('/')
        val kind = ArchiveKind.of(name) ?: throw ArchiveException(R.string.archive_unsupported, name)
        val browsable = browsableFile(context, localFile, kind, copyFolder)
        val fileSystem = try {
            ArchiveFileSystem.open(browsable.first, browsable.second, name, passwordFor(location))
        } catch (e: Exception) {
            copyFolder?.deleteRecursively()
            throw e
        }

        synchronized(this) {
            sessions[location]?.let { fileSystem.close(); copyFolder?.deleteRecursively(); return it.fileSystem }
            sessions[location] = Session(fileSystem, localFile, stampOf(localFile), copyFolder)
            while (sessions.size > MAX_OPEN) {
                close(sessions.keys.first())
            }
        }
        return fileSystem
    }

    @Synchronized
    fun passwordFor(location: String): String? = passwords[location]

    /** Remembers [password] for [location]; the archive is opened again with it. */
    @Synchronized
    fun setPassword(location: String, password: String) {
        passwords[location] = password
        close(location)
    }

    /** Closes the archive at [location], e.g. before its file goes away. */
    @Synchronized
    fun close(location: String) {
        val session = sessions.remove(location) ?: return
        runCatching { session.fileSystem.close() }
        session.copyFolder?.deleteRecursively()
    }

    @Synchronized
    fun closeAll() {
        sessions.keys.toList().forEach(::close)
    }

    private fun stampOf(file: File) = file.lastModified() to file.length()

    /** The archive as a phone file: itself, or a copy of an archive on a server or inside another archive. */
    private fun localFileFor(context: Context, location: String): Pair<File, File?> {
        if (!location.isRemotePath() && !location.isArchivePath()) {
            val file = File(location)
            if (!file.canRead()) throw ArchiveException(R.string.archive_damaged)
            return file to null
        }

        val folder = File(cacheRoot(context), hash(location)).apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val name = location.substringAfterLast('/')
            val copy = if (location.isRemotePath()) {
                copyFromServer(context, location, folder)
            } else {
                get(context, location.getArchiveLocation()).extractFile(location.getArchiveInnerPath(), folder)
            }
            return (copy ?: File(folder, name)) to folder
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw e
        }
    }

    /** Downloads the archive at the server path [location] with all its parts into [folder]; returns the first part. */
    private fun copyFromServer(context: Context, location: String, folder: File): File {
        val server = RemoteConnections.get(context, location.getRemoteServerId()) ?: throw LocalizedException(R.string.unknown_server)
        val serverPath = location.getRemoteServerPath()
        val parentPath = getRemoteParentPath(serverPath)
        val siblings = server.listFiles(parentPath).filterNot { it.isDirectory }.associateBy { it.name.lowercase() }
        val selectedName = serverPath.substringAfterLast('/')
        val firstName = ArchiveVolumes.firstVolumeName(selectedName)?.takeIf { it.lowercase() in siblings } ?: selectedName
        val parts = ArchiveVolumes.volumeNames(firstName) { it.lowercase() in siblings }
            .map { siblings[it.lowercase()] ?: BrowserFile(it, joinRemotePath(parentPath, it), false, 0, 0) }
        server.download(parts, folder, { false }, {})
        return File(folder, parts.first().name)
    }

    /**
     * Compressed tars are browsed as the tar inside them, which is unpacked into the cache first. Split ZIPs, which
     * 7-Zip can't read, are joined into one ZIP there.
     */
    private fun browsableFile(context: Context, file: File, kind: ArchiveKind, copyFolder: File?): Pair<File, ArchiveKind> {
        val isSplitZip = kind == ArchiveKind.ZIP && isSplitZip(file)
        if (!kind.isTarCompressed && !isSplitZip) return file to kind

        val folder = copyFolder ?: File(cacheRoot(context), hash(file.absolutePath)).apply { mkdirs() }
        val prepared = File(folder, if (isSplitZip) JOINED_PREFIX + file.name else ArchiveKind.baseName(file.name) + ".tar")
        if (!prepared.exists() || prepared.lastModified() < file.lastModified()) {
            prepared.delete()
            if (isSplitZip) {
                SplitZipJoiner.join(file, prepared)
            } else {
                ArchiveExtractor(folder).decompressSingle(file, kind, prepared)
            }
        }
        return prepared to if (isSplitZip) ArchiveKind.ZIP else ArchiveKind.TAR
    }

    private fun cacheRoot(context: Context) = File(context.cacheDir, CACHE_FOLDER)

    /** Copies left over from the last run are of no use any more. */
    private fun cleanCacheOnce(context: Context) {
        if (cacheCleaned) return
        cacheCleaned = true
        cacheRoot(context).deleteRecursively()
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
