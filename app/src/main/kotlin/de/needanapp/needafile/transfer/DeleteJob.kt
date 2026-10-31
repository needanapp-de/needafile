package de.needanapp.needafile.transfer

import android.content.Context
import de.needanapp.needafile.R
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.errors.LocalizedException
import de.needanapp.needafile.files.getVirtualInnerPath
import de.needanapp.needafile.files.isWritableVirtualPath
import de.needanapp.needafile.remote.RemoteFileSystem
import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.remote.getRemoteParentPath
import java.io.File
import java.nio.file.Files

/**
 * Deletes [items], folders with all their content, as work of [TransferManager]: a deletion that takes a while shows its
 * progress above the pill and in the notification and can be cancelled. It then stops after the file at hand; what is
 * gone stays gone. On the phone and on SFTP, SMB and FTP servers the files and folders are counted first and each one
 * gone is told; WebDAV and vaults delete a folder in one go, there the selected entries are counted.
 */
class DeleteJob(private val context: Context, private val items: List<FileEntry>) : TransferJob {
    /** Files and folders in and with an entry, and the bytes of its files. */
    private class Count(val entries: Int, val bytes: Long)

    override fun run(isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit): TransferJob.Result {
        val firstPath = items.first().path
        if (!firstPath.isWritableVirtualPath()) return deleteOnPhone(isCancelled, onProgress)
        val fileSystem = TransferManager.getRemoteFileSystem(context, firstPath)
        return if (fileSystem.deletesFileByFile) deleteOnServer(fileSystem, isCancelled, onProgress) else deleteEntries(fileSystem, isCancelled, onProgress)
    }

    private fun deleteOnPhone(isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit): TransferJob.Result {
        val roots = items.map { File(it.path) }
        val plans = roots.map { collect(it, isCancelled) }
        val total = plans.sumOf { it.size }
        var done = 0
        var freed = 0L
        var leftOver: String? = null
        val gone = ArrayList<File>()
        plans.forEachIndexed { index, plan ->
            val root = roots[index]
            val parent = root.parent.orEmpty()
            plan.forEach { file ->
                if (isCancelled()) throw TransferCancelledException()
                val size = if (file.isFile) file.length() else 0L
                if (file.delete() || !file.exists()) {
                    freed += size
                } else if (leftOver == null) {
                    leftOver = file.name
                }
                done++
                onProgress(TransferProgress(file.path.removePrefix(parent).trimStart('/'), done, total, freed, 0, index))
            }
            if (!root.exists()) gone.add(root)
            onProgress(TransferProgress(root.name, done, total, freed, 0, index + 1))
        }
        leftOver?.let { throw LocalizedException(R.string.delete_left, it) }
        return TransferJob.Result(items.size, gone, freed)
    }

    /** [root] and everything in it, each folder after its content; links are deleted themselves, never followed. */
    private fun collect(root: File, isCancelled: () -> Boolean): List<File> {
        val result = ArrayList<File>()
        fun walk(file: File) {
            if (isCancelled()) throw TransferCancelledException()
            if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) file.listFiles()?.forEach { walk(it) }
            result.add(file)
        }
        walk(root)
        return result
    }

    private fun deleteOnServer(fileSystem: RemoteFileSystem, isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit): TransferJob.Result {
        val counts = items.map { item ->
            if (item.isDirectory) count(fileSystem, item.path.getVirtualInnerPath(), isCancelled) else Count(1, item.size)
        }
        val total = counts.sumOf { it.entries }
        var done = 0
        items.forEachIndexed { index, item ->
            if (isCancelled()) throw TransferCancelledException()
            val path = item.path.getVirtualInnerPath()
            val parent = getRemoteParentPath(path)
            fileSystem.delete(listOf(path)) { gone ->
                // a server may hold more than it listed meanwhile
                done = minOf(done + 1, total)
                onProgress(TransferProgress(gone.removePrefix(parent).trimStart('/'), done, total, 0, 0, index))
                if (isCancelled()) throw TransferCancelledException()
            }
            onProgress(TransferProgress(item.name, done, total, 0, 0, index + 1))
        }
        return TransferJob.Result(items.size, emptyList(), counts.sumOf { it.bytes })
    }

    private fun count(fileSystem: RemoteFileSystem, path: String, isCancelled: () -> Boolean): Count {
        var entries = 1
        var bytes = 0L
        fileSystem.listFiles(path).forEach { child ->
            if (isCancelled()) throw TransferCancelledException()
            if (child.isDirectory) {
                val inner = count(fileSystem, child.path, isCancelled)
                entries += inner.entries
                bytes += inner.bytes
            } else {
                entries++
                bytes += child.size
            }
        }
        return Count(entries, bytes)
    }

    /** A folder goes in one request here, so the progress can only count the selected entries. */
    private fun deleteEntries(fileSystem: RemoteFileSystem, isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit): TransferJob.Result {
        items.forEachIndexed { index, item ->
            if (isCancelled()) throw TransferCancelledException()
            onProgress(TransferProgress(item.name, index, items.size, 0, 0, index))
            fileSystem.delete(listOf(item.path.getVirtualInnerPath()))
            onProgress(TransferProgress(item.name, index + 1, items.size, 0, 0, index + 1))
        }
        return TransferJob.Result(items.size, emptyList())
    }
}
