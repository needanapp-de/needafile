package de.needanapp.needafile.transfer

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import de.needanapp.needafile.R
import java.io.File

/** What the job in [TransferManager] does; picks the texts of the paste bar, the notification and the result. */
enum class TransferKind(
    @StringRes val progress: Int,
    @PluralsRes val done: Int,
    @StringRes val cancelled: Int,
    @StringRes val failed: Int,
) {
    COPY(R.string.transfer_progress, R.plurals.transfer_done, R.string.transfer_cancelled, R.string.transfer_failed),
    MOVE(R.string.transfer_progress_move, R.plurals.transfer_moved, R.string.transfer_move_cancelled, R.string.transfer_move_failed),
    COMPRESS(R.string.compress_progress, R.plurals.compress_done, R.string.compress_cancelled, R.string.compress_failed),

    /** Items copied out of an archive. */
    EXTRACT(R.string.extract_progress, R.plurals.extract_done, R.string.extract_cancelled, R.string.extract_failed),

    /** Whole archives extracted next to themselves. */
    DECOMPRESS(R.string.extract_progress, R.plurals.decompress_done, R.string.extract_cancelled, R.string.extract_failed),

    /** A deletion big enough to show its progress, see [DeleteJob]; it counts files and folders instead of bytes. */
    DELETE(R.string.delete_progress, R.plurals.delete_done, R.string.delete_cancelled, R.string.delete_failed),
    ;

    /** How far [progress] has come, from 0 to 1; null while that is unknown. */
    fun fraction(progress: TransferProgress?): Float? = when {
        progress == null -> null
        this == DELETE -> if (progress.fileCount > 0) progress.fileIndex.toFloat() / progress.fileCount else null
        progress.bytesTotal > 0 -> progress.bytesDone.toFloat() / progress.bytesTotal
        else -> null
    }
}

/**
 * Work other than copying that [TransferService] runs the same way, with progress, notification and cancelling:
 * packing and unpacking archives, deleting. It can't pause.
 */
fun interface TransferJob {
    /** Does the work; stops with a TransferCancelledException once [isCancelled] turns true. */
    fun run(isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit): Result

    /**
     * [itemCount] for the result message (0 shows none), [changed] files and folders for the media scan, [bytes] the
     * work freed, told in the message too.
     */
    class Result(val itemCount: Int, val changed: List<File>, val bytes: Long = 0)
}
