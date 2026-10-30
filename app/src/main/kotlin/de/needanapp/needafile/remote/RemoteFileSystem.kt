package de.needanapp.needafile.remote

import de.needanapp.needafile.transfer.TransferProgress
import java.io.File
import java.io.IOException

/**
 * One saved server (SFTP, SMB, WebDAV or FTP) or an opened archive as seen by the files tab. Paths are server-side paths that
 * always start with "/", "/" being the start of what the connection can reach. Every call blocks,
 * so callers must run them off the main thread and call [close] when done.
 */
interface RemoteFileSystem {
    /** Verifies host and credentials, throws on failure. */
    fun testConnection()

    /** The folder's content, sorted with [BrowserFile.DEFAULT_ORDER]. */
    fun listFiles(path: String): List<BrowserFile>

    /**
     * Copies the remote [items] (folders recursively) into the local folder [targetDir], overwriting
     * existing files. Returns the local files that were written.
     */
    fun download(
        items: List<BrowserFile>,
        targetDir: File,
        isCancelled: () -> Boolean,
        onProgress: (TransferProgress) -> Unit,
    ): List<File>

    /** Copies the local [items] (folders recursively) into the remote folder [targetDir], overwriting existing files. */
    fun upload(
        items: List<File>,
        targetDir: String,
        isCancelled: () -> Boolean,
        onProgress: (TransferProgress) -> Unit,
    )

    /** Deletes the given files and folders, folders with all their content. */
    fun delete(paths: List<String>)

    /** Renames [path] within its folder and returns the new path. */
    fun rename(path: String, newName: String): String {
        val newPath = joinRemotePath(getRemoteParentPath(path), newName)
        move(path, newPath)
        return newPath
    }

    /**
     * Moves [path] to [newPath] on the same server without copying anything, also into another folder. Fails when
     * something is at [newPath] already.
     */
    fun move(path: String, newPath: String)

    /** Whether [move] can take [path] to [newPath]; not between two SMB shares, for instance. */
    fun canMove(path: String, newPath: String): Boolean = true

    /** Creates the folder [name] in [parentPath] and returns its path. */
    fun createFolder(parentPath: String, name: String): String

    /** Closes the kept-open browsing connection, if any. */
    fun close()
}

class TransferCancelledException : IOException("Transfer cancelled")

fun joinRemotePath(folder: String, name: String) = if (folder.endsWith("/")) "$folder$name" else "$folder/$name"

fun getRemoteParentPath(path: String): String {
    val trimmed = path.trimEnd('/')
    val index = trimmed.lastIndexOf('/')
    return if (index <= 0) "/" else trimmed.substring(0, index)
}
