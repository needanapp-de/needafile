package de.needanapp.needafile.transfer

/**
 * Snapshot of a running copy between server and phone. [fileIndex] is 1-based. A deletion counts what is gone in
 * [fileIndex] and how many of the selected entries are gone completely in [itemsDone].
 */
data class TransferProgress(
    val fileName: String,
    val fileIndex: Int,
    val fileCount: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val itemsDone: Int = 0,
)
