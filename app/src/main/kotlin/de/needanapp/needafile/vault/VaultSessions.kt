package de.needanapp.needafile.vault

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import de.needanapp.needafile.R
import de.needanapp.needafile.core.inBackground
import de.needanapp.needafile.errors.LocalizedException
import de.needanapp.needafile.files.forgetThumbnails
import de.needanapp.needafile.remote.BrowserFile
import de.needanapp.needafile.remote.RemoteConnections
import de.needanapp.needafile.remote.ServerFileSystem
import de.needanapp.needafile.remote.getRemoteServerId
import de.needanapp.needafile.remote.getRemoteServerPath
import de.needanapp.needafile.remote.isRemotePath
import de.needanapp.needafile.transfer.TransferProgress
import java.io.File
import java.security.MessageDigest

/**
 * The unlocked vaults by location (their folder on the phone or a server path). Keys live in memory only; locking a
 * vault forgets them and deletes whatever was decrypted for viewing or previews. Listeners and [version] change on
 * the main thread.
 */
object VaultSessions {
    private const val CACHE_FOLDER = "vaults"
    private const val OPENED_FOLDER = "open"
    private const val PREVIEW_FOLDER = "previews"

    private val sessions = HashMap<String, VaultFileSystem>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(String) -> Unit>()

    /** Where the files decrypted for viewing come from, by their path in the cache. */
    private val origins = HashMap<String, Origin>()

    /** A file of the vault at [location], with its "vault://" [path] and its entry. */
    class Origin(val location: String, val path: String, val item: BrowserFile)

    /** Counts unlocking and locking, so screens listing vaults show their state. */
    var version by mutableIntStateOf(0)
        private set

    @Synchronized
    fun isUnlocked(location: String) = location in sessions

    @Synchronized
    fun hasUnlocked() = sessions.isNotEmpty()

    @Synchronized
    fun unlockedLocations(): List<String> = sessions.keys.toList()

    /** The unlocked vault at [location]; throws [VaultLockedException] when it is locked. */
    @Synchronized
    fun get(location: String): VaultFileSystem = sessions[location] ?: throw VaultLockedException(location)

    /** Where the encrypted files of the vault at [location] lie. */
    fun storageFor(context: Context, location: String): VaultStorage {
        if (!location.isRemotePath()) return LocalVaultStorage(File(location))
        val appContext = context.applicationContext
        val serverId = location.getRemoteServerId()
        return ServerVaultStorage(
            server = { RemoteConnections.get(appContext, serverId) as? ServerFileSystem ?: throw LocalizedException(R.string.unknown_server) },
            root = location.getRemoteServerPath(),
        )
    }

    /** Unlocks the vault at [location] with [password] and remembers it in the list; blocks for a moment (scrypt). */
    fun unlock(context: Context, location: String, password: CharSequence) {
        if (isUnlocked(location)) return
        val storage = storageFor(context, location)
        val opened = try {
            VaultAccess.open(storage, password)
        } catch (e: Exception) {
            storage.close()
            throw e
        }
        register(context, location, VaultFileSystem(storage, opened))
    }

    /** Keeps [fileSystem] as the unlocked vault at [location], e.g. one just created. */
    fun register(context: Context, location: String, fileSystem: VaultFileSystem) {
        val previous = synchronized(this) { sessions.put(location, fileSystem) }
        previous?.let { inBackground { it.close() } }
        VaultStore.remember(context, location)
        changed(location)
    }

    fun lock(context: Context, location: String) {
        val fileSystem = synchronized(this) { sessions.remove(location) } ?: return
        // closing may talk to the server, that can't happen on the main thread
        inBackground { fileSystem.close() }
        VaultShares.revoke(context, location)
        synchronized(this) { origins.values.removeAll { it.location == location } }
        cacheFolder(context, location).deleteRecursively()
        forgetThumbnails(vaultRootPath(location).removeSuffix("/"))
        changed(location)
    }

    fun lockAll(context: Context) = unlockedLocations().forEach { lock(context, it) }

    /** Locks the vaults on the server [serverId], e.g. when it was deleted. */
    fun lockServer(context: Context, serverId: String) =
        unlockedLocations().filter { it.isRemotePath() && it.getRemoteServerId() == serverId }.forEach { lock(context, it) }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    private fun changed(location: String) = mainHandler.post {
        version++
        listeners.toList().forEach { it(location) }
    }

    /**
     * Decrypts the vault file at [path] (a "vault://" path) into the cache, to show it in one of needafile's viewers;
     * it is deleted again when the vault locks. Other apps get it through [VaultShares] instead. Blocks.
     */
    fun decryptForViewing(
        context: Context,
        path: String,
        item: BrowserFile,
        isCancelled: () -> Boolean = { false },
        onProgress: (TransferProgress) -> Unit = {},
    ): File {
        val file = decryptInto(context, path, item, OPENED_FOLDER, hash(path), isCancelled, onProgress)
        synchronized(this) { origins[file.absolutePath] = Origin(path.getVaultLocation(), path, item) }
        return file
    }

    /** The vault file [path] was decrypted from for viewing, null for any other file. */
    @Synchronized
    fun originOf(path: String): Origin? = origins[path]

    /**
     * The uri other apps get for [file] when it was decrypted from a vault for viewing: they read the vault file
     * itself through [VaultShares], never the decrypted copy. Null for any other file.
     */
    fun shareUriFor(context: Context, file: File): Uri? = originOf(file.absolutePath)?.let { VaultShares.uriFor(context, it.path, it.item) }

    /** Decrypts [item] for making a preview; the caller deletes the returned file right after. Blocks. */
    fun decryptForPreview(context: Context, path: String, item: BrowserFile): File =
        decryptInto(context, path, item, PREVIEW_FOLDER, "${hash(path)}-${System.nanoTime()}")

    private fun decryptInto(
        context: Context,
        path: String,
        item: BrowserFile,
        kind: String,
        key: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (TransferProgress) -> Unit = {},
    ): File {
        val location = path.getVaultLocation()
        val folder = File(cacheFolder(context, location), "$kind/$key").apply { mkdirs() }
        return get(location).download(listOf(item), folder, isCancelled, onProgress).single()
    }

    private fun cacheFolder(context: Context, location: String) = File(cacheRoot(context), hash(location))

    private fun cacheRoot(context: Context) = File(context.cacheDir, CACHE_FOLDER)

    /** Decrypted files left over when the app was ended while a vault was open. */
    fun cleanCache(context: Context) {
        synchronized(this) { origins.clear() }
        cacheRoot(context).deleteRecursively()
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
