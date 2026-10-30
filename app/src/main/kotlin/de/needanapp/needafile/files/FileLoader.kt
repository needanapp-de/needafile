package de.needanapp.needafile.files

import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.Sorting
import de.needanapp.needafile.core.displayPath
import de.needanapp.needafile.core.entryCount
import de.needanapp.needafile.core.getMimeType
import de.needanapp.needafile.core.getParentPath
import de.needanapp.needafile.core.inBackground
import de.needanapp.needafile.core.isRestrictedFolder
import de.needanapp.needafile.core.mediaStoreModifiedTimes
import de.needanapp.needafile.core.needsRoot
import de.needanapp.needafile.core.totalSize
import de.needanapp.needafile.core.withoutAccents
import de.needanapp.needafile.root.RootShell
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.transfer.TransferManager
import java.io.File

/** A folder like Android/data that Android keeps apps out of; the system's own file app can still show it. */
class RestrictedFolderException(val path: String) : Exception(path)

/**
 * Reads folders of every kind – phone storage, SD cards and USB drives, root, servers and archives – and searches
 * local folders. The work runs in the background; every callback comes on the main thread.
 */
class FileLoader(private val activity: BaseActivity) {
    /**
     * Lists [path]. [onLoaded] gets the entries that fit [wantedMimeTypes] (folders always fit); with [withChildCounts]
     * the number of entries in each local folder follows in [onChildCounts], since counting can take a while.
     */
    fun load(
        path: String,
        showHidden: Boolean,
        wantedMimeTypes: List<String>,
        withChildCounts: Boolean,
        onLoaded: (List<FileEntry>) -> Unit,
        onChildCounts: (Map<String, Int>) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        fun show(entries: List<FileEntry>, error: Throwable? = null) = activity.runOnUiThread {
            onLoaded(entries.filter { entry -> wantedMimeTypes.any { fitsMimeType(it, entry.path, entry.isDirectory) } })
            if (error != null) onError(error)
        }

        inBackground {
            if (activity.isFinishing || activity.isDestroyed) return@inBackground
            val withSizes = activity.config.getFolderSorting(path) and Sorting.BY_SIZE != 0
            try {
                when {
                    path.isVirtualPath() -> show(remoteEntries(path, showHidden))
                    // the list explains it and offers the system's file app instead
                    activity.isRestrictedFolder(path) -> show(emptyList(), RestrictedFolderException(path))
                    activity.config.enableRootAccess && activity.needsRoot(path) -> show(RootShell.list(path, showHidden, withSizes))
                    else -> {
                        val entries = localEntries(path, showHidden, withSizes)
                        show(entries)
                        if (withChildCounts) {
                            val counts = entries.filter { it.isDirectory }.associate { it.path to File(it.path).entryCount(showHidden) }
                            activity.runOnUiThread { onChildCounts(counts) }
                        }
                    }
                }
            } catch (e: Exception) {
                show(emptyList(), e)
            }
        }
    }

    /** A folder on a server or inside an archive. */
    private fun remoteEntries(path: String, showHidden: Boolean): List<FileEntry> =
        TransferManager.getRemoteFileSystem(activity, path).listFiles(path.getVirtualInnerPath())
            .filter { showHidden || !it.name.startsWith(".") }
            .map { FileEntry(buildVirtualPathLike(path, it.path), it.name, it.isDirectory, 0, it.size, it.lastModified) }

    /** A folder on the phone, an SD card or a USB drive; change times come from MediaStore where it has them. */
    private fun localEntries(path: String, showHidden: Boolean, withSizes: Boolean): List<FileEntry> {
        val files = File(path).listFiles() ?: return emptyList()
        val knownTimes = activity.mediaStoreModifiedTimes(path)
        return files.filter { showHidden || !it.name.startsWith(".") }
            .map { it.toEntry(withFolderSize = withSizes, countHidden = showHidden, modified = knownTimes[it.absolutePath]) }
    }

    /**
     * Finds files and folders below [path] whose name contains [text] (any name when empty) and that fit [filter]. The
     * results come grouped by folder, each group led by a section title. Local folders only.
     */
    fun search(text: String, filter: SearchFilter?, path: String, showHidden: Boolean, isCancelled: () -> Boolean, onResult: (List<FileEntry>) -> Unit) {
        inBackground {
            val wanted = text.withoutAccents()
            val start = File(path)
            val found = start.walkTopDown()
                .onEnter { !isCancelled() && (it == start || showHidden || !it.isHidden) }
                .filter { it != start && (showHidden || !it.isHidden) }
                .filter { file -> filter?.matches(file.name, file.isDirectory) != false }
                .filter { wanted.isEmpty() || it.name.withoutAccents().contains(wanted, ignoreCase = true) }
                .map { it.toEntry(withFolderSize = false, countHidden = showHidden, modified = null) }
                .toList()
            if (isCancelled()) return@inBackground

            // a folder that matches and the matching files inside it share one heading
            val matchingFolders = found.filter { it.isDirectory }.mapTo(HashSet()) { it.path }
            val filesByFolder = found.filterNot { it.isDirectory }.groupBy { it.path.getParentPath() }
            val results = (matchingFolders + filesByFolder.keys).sorted().flatMap { folder ->
                val heading = FileEntry(folder, activity.displayPath(folder), isDirectory = folder in matchingFolders, isSectionTitle = true)
                listOf(heading) + filesByFolder[folder].orEmpty()
            }
            activity.runOnUiThread { onResult(results) }
        }
    }

    private fun File.toEntry(withFolderSize: Boolean, countHidden: Boolean, modified: Long?): FileEntry {
        val folder = isDirectory
        val size = if (!folder) length() else if (withFolderSize) totalSize(countHidden) else 0L
        return FileEntry(absolutePath, name, folder, 0, size, modified ?: lastModified())
    }

    companion object {
        /** Whether a file fits the mime type another app asked for when picking, e.g. "image/ *"; folders always do. */
        fun fitsMimeType(wanted: String, path: String, isDirectory: Boolean): Boolean {
            if (isDirectory || wanted.isEmpty() || wanted == "*/*") return true
            val actual = path.getMimeType().lowercase()
            val asked = wanted.lowercase()
            return if (asked.endsWith("/*")) actual.substringBefore('/') == asked.substringBefore('/') else actual == asked
        }
    }
}
