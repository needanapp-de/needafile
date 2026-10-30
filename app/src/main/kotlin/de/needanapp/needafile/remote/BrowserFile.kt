package de.needanapp.needafile.remote

/** A single entry (file or directory) in the server/phone transfer panes, remote or local. */
data class BrowserFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
) {
    companion object {
        /** Folders first, then case-insensitive by name. */
        val DEFAULT_ORDER: Comparator<BrowserFile> =
            compareByDescending<BrowserFile> { it.isDirectory }.thenBy { it.name.lowercase() }
    }
}
