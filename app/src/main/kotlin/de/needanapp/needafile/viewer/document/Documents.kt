package de.needanapp.needafile.viewer.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import de.needanapp.needafile.R
import de.needanapp.needafile.core.filePathOf
import de.needanapp.needafile.permissions.StorageAccess
import de.needanapp.needafile.viewer.document.office.DocxReader
import de.needanapp.needafile.viewer.document.office.OdsReader
import de.needanapp.needafile.viewer.document.office.OdtReader
import de.needanapp.needafile.viewer.document.office.XlsxReader
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import org.xmlpull.v1.XmlPullParserException

/** The kinds of documents the viewer shows; plain text goes to the text editor instead. */
internal enum class ViewerDocumentKind { PDF, DOCX, ODT, HTML, MARKDOWN, CSV, XLSX, ODS, TEXT }

/** A document from needafile or another app: a file when there is one, otherwise only a content address. */
internal data class ViewerDocument(val path: String?, val uri: Uri, val name: String, val mimeType: String, val kind: ViewerDocumentKind)

/** One table: a CSV file or a sheet of a spreadsheet. The first row is shown as the header. */
internal data class Sheet(val name: String, val rows: List<List<String>>, val columnCount: Int)

internal sealed interface DocumentContent {
    data class Pdf(val document: ViewerDocument) : DocumentContent
    data class Web(val html: String) : DocumentContent
    data class Table(val sheets: List<Sheet>) : DocumentContent
}

/** A document that can't be shown, e.g. far too large or damaged. */
internal class DocumentUnreadableException(cause: Throwable? = null) : IOException(cause)

/** A file that isn't what its name says, e.g. a damaged download or a renamed file. */
internal class DocumentDamagedException(cause: Throwable? = null) : IOException(cause)

internal object Documents {
    private val textBased = setOf(ViewerDocumentKind.HTML, ViewerDocumentKind.MARKDOWN, ViewerDocumentKind.CSV, ViewerDocumentKind.TEXT)

    /** Largest text-based document (Markdown, CSV, HTML) that is shown; larger ones are better opened elsewhere. */
    private const val MAX_TEXT_BYTES = 16L * 1024 * 1024

    private val kindsByExtension = mapOf(
        "pdf" to ViewerDocumentKind.PDF,
        "docx" to ViewerDocumentKind.DOCX,
        "odt" to ViewerDocumentKind.ODT,
        "xlsx" to ViewerDocumentKind.XLSX,
        "ods" to ViewerDocumentKind.ODS,
        "html" to ViewerDocumentKind.HTML,
        "htm" to ViewerDocumentKind.HTML,
        "xhtml" to ViewerDocumentKind.HTML,
        "md" to ViewerDocumentKind.MARKDOWN,
        "markdown" to ViewerDocumentKind.MARKDOWN,
        "mdown" to ViewerDocumentKind.MARKDOWN,
        "mkd" to ViewerDocumentKind.MARKDOWN,
        "mkdn" to ViewerDocumentKind.MARKDOWN,
        "csv" to ViewerDocumentKind.CSV,
        "tsv" to ViewerDocumentKind.CSV,
        "txt" to ViewerDocumentKind.TEXT,
        "text" to ViewerDocumentKind.TEXT,
    )
    private val kindsByType = mapOf(
        "application/pdf" to ViewerDocumentKind.PDF,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to ViewerDocumentKind.DOCX,
        "application/vnd.oasis.opendocument.text" to ViewerDocumentKind.ODT,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to ViewerDocumentKind.XLSX,
        "application/vnd.oasis.opendocument.spreadsheet" to ViewerDocumentKind.ODS,
        "text/html" to ViewerDocumentKind.HTML,
        "application/xhtml+xml" to ViewerDocumentKind.HTML,
        "text/markdown" to ViewerDocumentKind.MARKDOWN,
        "text/x-markdown" to ViewerDocumentKind.MARKDOWN,
        "text/csv" to ViewerDocumentKind.CSV,
        "text/comma-separated-values" to ViewerDocumentKind.CSV,
        "text/tab-separated-values" to ViewerDocumentKind.CSV,
        "text/plain" to ViewerDocumentKind.TEXT,
    )

    /** The kind of document by its file name, or by its type when the name has no known extension. */
    fun kindOf(name: String, mimeType: String = ""): ViewerDocumentKind? =
        kindsByExtension[name.substringAfterLast('.', "").lowercase()] ?: kindsByType[mimeType.substringBefore(';').trim().lowercase()]

    /** What is behind [uri]; null when the viewer can't show it. */
    fun resolve(context: Context, uri: Uri, mimeType: String?): ViewerDocument? {
        val path = when {
            uri.scheme == "file" -> uri.path
            StorageAccess.isGranted() -> runCatching { context.filePathOf(uri) }.getOrNull()
            else -> null
        }?.takeIf { File(it).canRead() }
        val name = path?.let { File(it).name } ?: displayName(context, uri)
        val type = mimeType ?: context.contentResolver.getType(uri).orEmpty()
        val kind = kindOf(name, type) ?: return null
        return ViewerDocument(path, path?.let { Uri.fromFile(File(it)) } ?: uri, name, type, kind)
    }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()

    /** Reads [document] for showing it; runs on a background thread. */
    fun load(context: Context, document: ViewerDocument): DocumentContent {
        if (document.kind in textBased) checkSize(context, document)
        return try {
            when (document.kind) {
                ViewerDocumentKind.PDF -> DocumentContent.Pdf(document)
                ViewerDocumentKind.XLSX, ViewerDocumentKind.ODS, ViewerDocumentKind.ODT -> loadOffice(context, document)
                ViewerDocumentKind.DOCX -> DocumentContent.Web(HtmlCleaner.clean(DocxReader().read(context.contentResolver, document.uri), isFragment = true))
                ViewerDocumentKind.HTML -> DocumentContent.Web(HtmlCleaner.clean(readText(context, document), isFragment = false))
                ViewerDocumentKind.CSV -> DocumentContent.Table(listOf(readCsv(readText(context, document), document.name)))
                ViewerDocumentKind.MARKDOWN, ViewerDocumentKind.TEXT -> DocumentContent.Web(markdownToHtml(readText(context, document)))
            }
        } catch (e: ZipException) {
            throw DocumentDamagedException(e)
        } catch (e: XmlPullParserException) {
            throw DocumentDamagedException(e)
        }
    }

    private fun loadOffice(context: Context, document: ViewerDocument): DocumentContent {
        val resolver = context.contentResolver
        return when (document.kind) {
            ViewerDocumentKind.XLSX -> table(XlsxReader(context.getString(R.string.document_true), context.getString(R.string.document_false)).read(resolver, document.uri))
            ViewerDocumentKind.ODS -> table(OdsReader().read(resolver, document.uri))
            else -> DocumentContent.Web(HtmlCleaner.clean(OdtReader().read(resolver, document.uri), isFragment = true))
        }
    }

    // a workbook without sheets shows as one empty table
    private fun table(sheets: List<Sheet>) = DocumentContent.Table(sheets.ifEmpty { listOf(Sheet("", emptyList(), 0)) })

    private fun readText(context: Context, document: ViewerDocument): String = decodeText(open(context, document).use { it.readBytes() })

    private fun open(context: Context, document: ViewerDocument) =
        context.contentResolver.openInputStream(document.uri) ?: throw IOException(document.name)

    private fun checkSize(context: Context, document: ViewerDocument) {
        val size = document.path?.let { File(it).length() } ?: runCatching {
            context.contentResolver.openAssetFileDescriptor(document.uri, "r")?.use { it.length }
        }.getOrNull() ?: return
        if (size > MAX_TEXT_BYTES) throw DocumentUnreadableException()
    }
}
