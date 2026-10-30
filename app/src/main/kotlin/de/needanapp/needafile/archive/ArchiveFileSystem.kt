package de.needanapp.needafile.archive

import de.needanapp.needafile.R
import de.needanapp.needafile.errors.LocalizedException
import de.needanapp.needafile.remote.BrowserFile
import de.needanapp.needafile.remote.RemoteFileSystem
import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.transfer.TransferProgress
import net.sf.sevenzipjbinding.PropID
import java.io.File
import java.util.Date

/** Archives can be browsed and extracted, but not changed. */
class ReadOnlyArchiveException : LocalizedException(R.string.archive_read_only)

/**
 * An archive seen as a read-only folder tree: lists its folders and extracts entries, with the same interface as a
 * server so it fits into tabs, clipboard and background transfers. [file] is a local copy for archives on servers
 * or inside other archives. Paths are inner paths starting with "/". The archive stays open until [close].
 */
class ArchiveFileSystem private constructor(
    private val file: File,
    private val kind: ArchiveKind,
    private val opened: OpenedArchive?,
    private val password: String?,
    private val entries: List<Entry>,
) : RemoteFileSystem {

    /** One file or folder; folders only implied by the paths of their content have no [index]. */
    private class Entry(val index: Int, val path: String, val isDirectory: Boolean, val size: Long, val modified: Long, val isEncrypted: Boolean) {
        val name get() = path.substringAfterLast('/')
        val parent get() = path.substringBeforeLast('/', "")
    }

    private val byPath = entries.associateBy { it.path }
    private val byIndex = entries.filter { it.index >= 0 }.associateBy { it.index }
    private val children = entries.groupBy { it.parent }

    /** Whether extracting needs a password that wasn't given: the file names are visible, the content is encrypted. */
    val needsPassword get() = password == null && entries.any { it.isEncrypted }

    override fun testConnection() = Unit

    override fun listFiles(path: String): List<BrowserFile> {
        val folder = normalize(path)
        if (folder.isNotEmpty() && byPath[folder]?.isDirectory != true) throw ArchiveException(R.string.archive_folder_missing, path)
        return children[folder].orEmpty().map { it.toBrowserFile() }.sortedWith(BrowserFile.DEFAULT_ORDER)
    }

    private fun Entry.toBrowserFile() = BrowserFile(name, "/$path", isDirectory, size, modified)

    /** Extracts [items] (folders with their content) into [targetDir]; returns the files and folders created there. */
    override fun download(items: List<BrowserFile>, targetDir: File, isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit): List<File> {
        val targets = HashMap<Int, File>()
        val folders = ArrayList<File>()
        val written = items.map { item ->
            val entry = byPath[normalize(item.path)] ?: throw ArchiveException(R.string.archive_folder_missing, item.path)
            val top = File(targetDir, entry.name)
            if (entry.isDirectory) {
                folders.add(top)
                entries.filter { it.path.startsWith(entry.path + "/") }.forEach { inner ->
                    val target = File(top, inner.path.removePrefix(entry.path + "/"))
                    if (inner.isDirectory) folders.add(target)
                    if (inner.index >= 0) targets[inner.index] = target
                }
            } else {
                targets[entry.index] = top
            }
            top
        }

        folders.forEach { it.mkdirs() }
        if (targets.isEmpty()) return written
        if (needsPassword && targets.keys.any { byIndex[it]?.isEncrypted == true }) {
            throw ArchivePasswordException(isWrong = false)
        }

        val fileCount = targets.keys.count { byIndex[it]?.isDirectory == false }
        var currentName = ""
        var currentNumber = 0
        extract(targets, isCancelled, { done, total ->
            onProgress(TransferProgress(currentName, currentNumber.coerceAtLeast(1), fileCount.coerceAtLeast(1), done, total))
        }) { index ->
            targets[index]?.also {
                if (byIndex[index]?.isDirectory == false) {
                    currentName = it.name
                    currentNumber++
                }
            }
        }
        return written
    }

    private fun extract(targets: Map<Int, File>, isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit, targetFor: (Int) -> File?) {
        if (opened == null) {
            // a single compressed file read by XZ for Java
            val target = targets.values.single()
            target.parentFile?.mkdirs()
            ArchiveExtractor(target.parentFile ?: file.parentFile!!).decompressSingle(file, kind, target)
            return
        }

        synchronized(opened) {
            try {
                ArchiveExtractor.extractItems(opened.archive, targets.keys.sorted().toIntArray(), password, isCancelled, onProgress, targetFor)
            } catch (e: CancelledExtractionException) {
                throw TransferCancelledException()
            }
        }
    }

    /** Extracts the file at [path] into [folder] (e.g. to open it) and returns it. */
    fun extractFile(path: String, folder: File): File = download(listOf(byPath[normalize(path)]!!.toBrowserFile()), folder, { false }, {}).single()

    /** Checks [candidate] on the smallest encrypted file, without writing anything that stays. */
    fun isPasswordRight(candidate: String, scratchFolder: File): Boolean {
        val smallest = entries.filter { it.isEncrypted && !it.isDirectory && it.index >= 0 }.minByOrNull { it.size } ?: return true
        val probe = File(scratchFolder, "password_probe")
        return try {
            synchronized(opened!!) {
                ArchiveExtractor.extractItems(opened.archive, intArrayOf(smallest.index), candidate) { probe }
            }
            true
        } catch (e: ArchivePasswordException) {
            false
        } finally {
            probe.delete()
        }
    }

    override fun upload(items: List<File>, targetDir: String, isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit) = throw ReadOnlyArchiveException()

    override fun delete(paths: List<String>) = throw ReadOnlyArchiveException()

    override fun rename(path: String, newName: String) = throw ReadOnlyArchiveException()

    override fun move(path: String, newPath: String) = throw ReadOnlyArchiveException()

    override fun canMove(path: String, newPath: String) = false

    override fun createFolder(parentPath: String, name: String) = throw ReadOnlyArchiveException()

    override fun close() {
        opened?.let { synchronized(it) { it.close() } }
    }

    companion object {
        /**
         * Opens the archive [file] of [kind]; [name] is the archive's own name, for single compressed files whose
         * content is named after it. Throws [ArchivePasswordException] when the file names are encrypted and
         * [password] is missing or wrong.
         */
        fun open(file: File, kind: ArchiveKind, name: String, password: String?): ArchiveFileSystem {
            if (kind == ArchiveKind.XZ) {
                val entry = Entry(0, ArchiveKind.baseName(name), false, -1L, file.lastModified(), false)
                return ArchiveFileSystem(file, kind, null, null, listOf(entry))
            }

            val opened = ArchiveOpener.open(file, kind, password)
            try {
                return ArchiveFileSystem(file, kind, opened, password, readEntries(opened, kind, name, file.lastModified()))
            } catch (e: Exception) {
                opened.close()
                throw e
            }
        }

        /** Sizes 7-Zip doesn't know (e.g. in .Z files) are -1; single compressed files without a date get [fileModified]. */
        private fun readEntries(opened: OpenedArchive, kind: ArchiveKind, name: String, fileModified: Long): List<Entry> {
            val archive = opened.archive
            val byPath = LinkedHashMap<String, Entry>()
            for (index in 0 until archive.numberOfItems) {
                val rawPath = archive.getStringProperty(index, PropID.PATH)
                // single compressed files often carry no name of their own
                val path = normalize(rawPath.orEmpty()).ifEmpty { if (kind.isSingleStream) ArchiveKind.baseName(name) else null } ?: continue
                // entries reaching outside the archive's tree ("../x") are left out
                if (path.split('/').any { it == ".." }) continue
                val isDirectory = archive.getProperty(index, PropID.IS_FOLDER) == true
                val size = (archive.getProperty(index, PropID.SIZE) as? Long) ?: if (isDirectory) 0L else -1L
                val modified = (archive.getProperty(index, PropID.LAST_MODIFICATION_TIME) as? Date)?.time
                    ?: if (kind.isSingleStream) fileModified else 0L
                val isEncrypted = archive.getProperty(index, PropID.ENCRYPTED) == true
                // a path appearing twice (e.g. an updated file) keeps its last version
                byPath[path] = Entry(index, path, isDirectory, size, modified, isEncrypted)
            }

            // folders that only appear in the paths of their content
            byPath.keys.toList().forEach { path ->
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotEmpty() && byPath[parent] == null) {
                    byPath[parent] = Entry(-1, parent, true, 0, 0, false)
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            return byPath.values.toList()
        }

        /** "a/b" for "/a/b/", "a\b" or "./a/b"; "" for the archive's root. */
        private fun normalize(path: String) = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }
}
