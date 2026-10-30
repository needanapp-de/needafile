package de.needanapp.needafile.files

/** A folder opened at [time], in milliseconds since 1970. */
data class RecentFolder(val path: String, val time: Long)

/**
 * [recent] with [path] opened at [time] in front, at most [MAX_RECENT_FOLDERS]. The folders it lies in drop out, it
 * says more than they do; going up into a folder an entry lies in changes nothing.
 */
fun recordRecentFolder(recent: List<RecentFolder>, path: String, time: Long): List<RecentFolder> {
    if (recent.any { it.path.isInside(path) }) return recent
    val rest = recent.filterNot { it.path == path || path.isInside(it.path) }
    return (listOf(RecentFolder(path, time)) + rest).take(MAX_RECENT_FOLDERS)
}

private fun String.isInside(folder: String) = startsWith(folder.trimEnd('/') + "/")

const val MAX_RECENT_FOLDERS = 5
