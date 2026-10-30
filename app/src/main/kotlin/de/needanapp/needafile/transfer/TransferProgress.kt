package de.needanapp.needafile.transfer

/** Snapshot of a running copy between server and phone. [fileIndex] is 1-based. */
data class TransferProgress(
    val fileName: String,
    val fileIndex: Int,
    val fileCount: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
)
