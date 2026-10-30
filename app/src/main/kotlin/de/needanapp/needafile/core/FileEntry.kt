package de.needanapp.needafile.core

import java.io.File

/**
 * A file or folder as the lists show it: on the phone, on a server or inside an archive. [children] counts the entries
 * of a folder where that is known; [isSectionTitle] marks the folder headings between search results.
 */
data class FileEntry(
    val path: String,
    val name: String = path.getFilenameFromPath(),
    val isDirectory: Boolean = false,
    val children: Int = 0,
    val size: Long = 0L,
    val modified: Long = 0L,
    val isSectionTitle: Boolean = false,
) {
    /** The folder this entry lies in. */
    val parentPath: String get() = path.getParentPath()
}

fun File.toFileEntry() = FileEntry(absolutePath, name, isDirectory, 0, length(), lastModified())

/** What a file takes on disk, or everything inside a folder; hidden entries only with [countHidden]. Walks the whole tree. */
fun File.totalSize(countHidden: Boolean): Long =
    if (isDirectory) visibleFiles(countHidden).sumOf { it.length() } else length()

/** How many files lie in this folder and the ones below it; a file counts as one. */
fun File.fileCount(countHidden: Boolean): Int = if (isDirectory) visibleFiles(countHidden).count() else 1

/** How many entries this folder holds directly. */
fun File.entryCount(countHidden: Boolean): Int = list()?.count { countHidden || !it.startsWith('.') } ?: 0

private fun File.visibleFiles(countHidden: Boolean): Sequence<File> {
    fun shown(file: File) = countHidden || !file.name.startsWith('.')
    return walkTopDown().onEnter { it == this || shown(it) }.filter { it.isFile && shown(it) }
}
