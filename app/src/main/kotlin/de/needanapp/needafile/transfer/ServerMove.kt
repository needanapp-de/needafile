package de.needanapp.needafile.transfer

import de.needanapp.needafile.R
import de.needanapp.needafile.errors.LocalizedException
import de.needanapp.needafile.remote.BrowserFile
import de.needanapp.needafile.remote.RemoteFileSystem
import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.remote.joinRemotePath

/**
 * Moves within one server by renaming there, so nothing travels through the phone: instant even for big files. Like
 * [LocalCopier] on the phone, a file already in the target is replaced and a folder already there gets the content
 * merged into it; whatever should be kept was left out before. What was moved stays moved when it stops halfway.
 */
internal object ServerMove {
    fun run(
        server: RemoteFileSystem,
        items: List<BrowserFile>,
        targetDir: String,
        isCancelled: () -> Boolean,
        onProgress: (TransferProgress) -> Unit,
    ) {
        val present = server.listFiles(targetDir).associateBy { it.name }
        items.forEachIndexed { index, item ->
            if (isCancelled()) throw TransferCancelledException()
            onProgress(TransferProgress(item.name, index + 1, items.size, index.toLong(), items.size.toLong()))
            move(server, item, joinRemotePath(targetDir, item.name), present[item.name], isCancelled)
        }
    }

    private fun move(server: RemoteFileSystem, item: BrowserFile, target: String, present: BrowserFile?, isCancelled: () -> Boolean) {
        when {
            present == null -> server.move(item.path, target)

            // a file can't replace a folder or the other way round; that would throw away a whole folder by surprise
            present.isDirectory != item.isDirectory -> throw LocalizedException(R.string.transfer_kind_conflict, item.name)

            !item.isDirectory -> {
                server.delete(listOf(target))
                server.move(item.path, target)
            }

            else -> {
                val presentInside = server.listFiles(target).associateBy { it.name }
                server.listFiles(item.path).forEach { child ->
                    if (isCancelled()) throw TransferCancelledException()
                    move(server, child, joinRemotePath(target, child.name), presentInside[child.name], isCancelled)
                }
                // all of it went over; something still inside arrived meanwhile and keeps its folder
                if (server.listFiles(item.path).isNotEmpty()) throw LocalizedException(R.string.transfer_move_source_left, item.name)
                server.delete(listOf(item.path))
            }
        }
    }
}
