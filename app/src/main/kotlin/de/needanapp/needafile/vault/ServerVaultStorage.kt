package de.needanapp.needafile.vault

import de.needanapp.needafile.errors.LocalizedException
import de.needanapp.needafile.remote.ServerFileSystem
import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.remote.TransferConnection
import de.needanapp.needafile.remote.joinRemotePath
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Semaphore

/**
 * The vault's folder [root] on a server. Reading and writing run on copy connections of their own ([TransferConnection]),
 * a few of which are kept open for the next request while the vault is unlocked, so browsing doesn't connect anew
 * for every folder. [server] is asked each time, the app replaces it when the server's settings change.
 */
class ServerVaultStorage(private val server: () -> ServerFileSystem, private val root: String) : VaultStorage {
    private val idle = ArrayDeque<TransferConnection>()
    private val permits = Semaphore(MAX_CONNECTIONS)

    /** Folders known to exist, so writing many files into one doesn't ask for it every time. */
    private val knownFolders = HashSet<String>()

    @Volatile
    private var isClosed = false

    private fun full(path: String) = if (path.isEmpty()) root else joinRemotePath(root, path)

    override fun list(path: String): List<StoredEntry>? = try {
        withConnection { connection -> connection.list(full(path)).map { StoredEntry(it.name, it.isDirectory, it.size, it.lastModified) } }
    } catch (e: TransferCancelledException) {
        throw e
    } catch (e: IOException) {
        // most servers simply fail for a folder that isn't there; the folder above tells whether it is
        if (path.isNotEmpty() && entry(path)?.isDirectory != true) null else throw e
    }

    override fun entry(path: String): StoredEntry? {
        if (path.isEmpty()) return StoredEntry("", true, 0, 0)
        val name = path.substringAfterLast('/')
        return list(path.substringBeforeLast('/', ""))?.firstOrNull { it.name == name }
    }

    override fun read(path: String, block: (InputStream) -> Unit) = withConnection { it.read(full(path), 0, block) }

    override fun readFrom(path: String, offset: Long, block: (InputStream) -> Unit) = withConnection { it.read(full(path), offset, block) }

    override fun write(path: String, length: Long, modified: Long, block: (OutputStream) -> Unit) =
        withConnection { it.write(full(path), 0, length, modified, block) }

    override fun makeFolder(path: String) = withConnection { connection ->
        var current = ""
        path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            current = if (current.isEmpty()) segment else "$current/$segment"
            if (synchronized(knownFolders) { current !in knownFolders }) {
                connection.makeFolder(full(current))
                synchronized(knownFolders) { knownFolders.add(current) }
            }
        }
    }

    override fun deleteFile(path: String) = withConnection { it.deleteFile(full(path)) }

    override fun deleteTree(path: String) {
        forgetFolders(path)
        server().delete(listOf(full(path)))
    }

    override fun move(path: String, newPath: String) {
        forgetFolders(path)
        server().move(full(path), full(newPath))
    }

    private fun forgetFolders(path: String) = synchronized(knownFolders) {
        knownFolders.removeAll { it == path || it.startsWith("$path/") }
    }

    /**
     * Runs [block] on a kept connection or a new one. A kept connection may have been closed by the server meanwhile,
     * so a request failing on it is tried once more on a new connection; [block] must therefore be able to start over.
     */
    private fun <T> withConnection(block: (TransferConnection) -> T): T {
        if (isClosed) throw IOException("Vault closed")
        permits.acquire()
        try {
            val kept = synchronized(idle) { idle.removeFirstOrNull() }
            if (kept != null) {
                try {
                    return block(kept).also { giveBack(kept) }
                } catch (e: Exception) {
                    runCatching { kept.close() }
                    if (e !is IOException || e is TransferCancelledException || e is LocalizedException) throw e
                }
            }
            val connection = server().openTransfer()
            try {
                return block(connection).also { giveBack(connection) }
            } catch (e: Exception) {
                runCatching { connection.close() }
                throw e
            }
        } finally {
            permits.release()
        }
    }

    private fun giveBack(connection: TransferConnection) {
        val keep = synchronized(idle) { (!isClosed && idle.size < MAX_CONNECTIONS).also { if (it) idle.addLast(connection) } }
        if (!keep) runCatching { connection.close() }
    }

    override fun close() {
        isClosed = true
        val closing = synchronized(idle) { idle.toList().also { idle.clear() } }
        closing.forEach { runCatching { it.close() } }
    }

    private companion object {
        /** Previews load side by side; a few connections at once are plenty and spare the server. */
        const val MAX_CONNECTIONS = 3
    }
}
