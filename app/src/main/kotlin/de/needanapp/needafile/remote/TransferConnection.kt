package de.needanapp.needafile.remote

import de.needanapp.needafile.transfer.ResumableCopy
import de.needanapp.needafile.transfer.TransferControl
import de.needanapp.needafile.transfer.TransferProgress
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * A server (SFTP, SMB, WebDAV, FTP) whose copies run on connections of their own, see [openTransfer]. The copying
 * itself is the same for all of them ([ResumableCopy]): it can pause and, after a broken connection, carry on where
 * it stopped.
 */
interface ServerFileSystem : RemoteFileSystem {
    /** Opens a connection for one copy, separate from the one used for browsing. */
    fun openTransfer(): TransferConnection

    override fun download(items: List<BrowserFile>, targetDir: File, isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit): List<File> =
        ResumableCopy.download(this, items, targetDir, TransferControl.cancellable(isCancelled), onProgress)

    override fun upload(items: List<File>, targetDir: String, isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit) {
        ResumableCopy.upload(this, items, targetDir, TransferControl.cancellable(isCancelled), onProgress)
    }
}

/**
 * One connection to a server for copying, closed when the copy is done or the connection broke. Besides listing and
 * creating folders it reads and writes files from an offset on, and tells how much of a file is there already, so an
 * interrupted copy can continue. Paths are server paths as in [RemoteFileSystem].
 */
interface TransferConnection : Closeable {
    /** Throws if nothing can be copied into the folder [path], e.g. the list of SMB shares. */
    fun checkTarget(path: String) = Unit

    /** The content of the folder [path]. */
    fun list(path: String): List<BrowserFile>

    /**
     * The same name for the folder [path] however it is reached, used to stop at symlink loops. Null if the server
     * can't tell, then the folder depth is limited instead.
     */
    fun folderKey(path: String): String? = null

    /** The size of the file [path], null if there is none. */
    fun sizeOf(path: String): Long?

    /** Creates the folder [path]; an existing one is fine. */
    fun makeFolder(path: String)

    /** Hands [block] the content of the file [path] from [offset] on. */
    fun read(path: String, offset: Long, block: (InputStream) -> Unit)

    /**
     * Hands [block] a stream into the file [path] from [offset] on: 0 creates or replaces the file, more keeps what is
     * there up to [offset] (only when [canContinueWrite]). [length] is how many bytes [block] writes. Afterwards the
     * file gets the modification time [modified] where the server allows it.
     */
    fun write(path: String, offset: Long, length: Long, modified: Long, block: (OutputStream) -> Unit)

    /** Whether [write] can continue a file instead of writing it anew. */
    val canContinueWrite: Boolean

    /**
     * How much of a file's end is written again when an upload continues, for servers that may keep pieces written
     * out of order: what lies before is surely complete.
     */
    val rewriteMargin: Long get() = 0

    fun deleteFile(path: String)
}

/**
 * The server refused a request with an error answer of its own (FTP reply, HTTP status), so it decided itself what
 * became of the file. [isTemporary] when trying again later may work, e.g. FTP's 4xx answers or HTTP 503.
 */
interface ServerErrorReply {
    val isTemporary: Boolean
}
