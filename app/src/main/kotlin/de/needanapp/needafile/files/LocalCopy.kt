package de.needanapp.needafile.files

import android.widget.Toast
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.formatSize
import de.needanapp.needafile.core.getAvailableStorageB
import de.needanapp.needafile.core.getParentPath
import de.needanapp.needafile.core.inBackground
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.core.volumeRoot
import de.needanapp.needafile.transfer.TransferManager
import java.io.File

/**
 * Copying and moving on the device, in the background through [TransferManager] like copies to and from servers, so
 * the paste bar and the notification show the progress and can cancel it. Name conflicts are decided first: items
 * already in the target are replaced (folders merged) or left out.
 */
internal class LocalCopy(private val activity: BaseActivity, private val requestDialog: (FileDialog) -> Unit) {
    /**
     * [onStarted] runs once the copy is on its way, e.g. to end the selection. [fromClipboard] when the items are
     * pasted, the clipboard then empties once they arrived.
     */
    fun copyMove(items: List<FileEntry>, destination: String, isCopy: Boolean, fromClipboard: Boolean = false, onStarted: () -> Unit) {
        val source = items.first().parentPath
        if (source == destination || items.any { destination == it.path || destination.startsWith("${it.path}/") }) {
            activity.toast(R.string.source_and_destination_same)
            return
        }
        if (!File(destination).isDirectory) {
            activity.toast(R.string.invalid_destination)
            return
        }

        inBackground {
            val conflicts = items.filter { File(destination, it.name).exists() }
            val needed = if (isCopy || source.volumeRoot(activity) != destination.volumeRoot(activity)) items.sumOf { File(it.path).totalSize() } else 0L
            val available = destination.getAvailableStorageB()
            activity.runOnUiThread {
                if (available != -1L && needed >= available) {
                    activity.toast(activity.getString(R.string.no_space, needed.formatSize(), available.formatSize()), Toast.LENGTH_LONG)
                    return@runOnUiThread
                }
                if (conflicts.isEmpty()) {
                    start(items, destination, isCopy, fromClipboard, onStarted)
                    return@runOnUiThread
                }
                requestDialog(FileDialog.TransferConflict(conflicts.map { it.name }) { overwrite ->
                    start(if (overwrite) items else items - conflicts.toSet(), destination, isCopy, fromClipboard, onStarted)
                })
            }
        }
    }

    private fun start(items: List<FileEntry>, destination: String, isCopy: Boolean, fromClipboard: Boolean, onStarted: () -> Unit) {
        if (items.isEmpty()) return
        // without the permission the copy still runs, just without its progress notification
        activity.withNotificationPermission {
            if (TransferManager.start(activity.applicationContext, items, destination, isMove = !isCopy, fromClipboard = fromClipboard)) {
                onStarted()
            } else {
                activity.toast(R.string.transfer_already_running)
            }
        }
    }

    private fun File.totalSize(): Long = if (isDirectory) walkTopDown().filter { it.isFile }.sumOf { it.length() } else length()
}
