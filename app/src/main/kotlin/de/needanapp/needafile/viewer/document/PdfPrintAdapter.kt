package de.needanapp.needafile.viewer.document

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import de.needanapp.needafile.core.inBackground
import java.io.FileOutputStream
import java.io.IOException

/** Hands a PDF to the system's print dialog unchanged; the printer or "save as PDF" gets the file's own pages. */
internal class PdfPrintAdapter(private val context: Context, private val uri: Uri, private val name: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
        // the pages don't depend on paper or margins, the file is printed as it is
        callback.onLayoutFinished(info, oldAttributes == null)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) = inBackground {
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException(name)
            input.use {
                FileOutputStream(destination.fileDescriptor).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (cancellationSignal.isCanceled) {
                            callback.onWriteCancelled()
                            return@inBackground
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: IOException) {
            callback.onWriteFailed(e.message)
        }
    }
}

private const val BUFFER_SIZE = 64 * 1024
