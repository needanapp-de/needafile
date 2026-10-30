package de.needanapp.needafile.viewer.document.office

import android.content.ContentResolver
import android.net.Uri
import de.needanapp.needafile.viewer.document.Sheet
import org.xmlpull.v1.XmlPullParser

/**
 * OpenDocument spreadsheets (ODS, e.g. from LibreOffice), read only: every sheet with the values as the program
 * displayed them. Rows and cells repeated thousands of times (empty space to the edge of the sheet) are left out.
 */
internal class OdsReader {
    fun read(resolver: ContentResolver, uri: Uri): List<Sheet> {
        val pkg = OfficePackage.read(resolver, uri) { it == CONTENT }
        val parser = pkg.requireParser(CONTENT, namespaces = true)
        val sheets = ArrayList<Sheet>()
        var sheetName = ""
        var rows = ArrayList<List<String>>()
        // empty rows and cells are only added once something follows them
        var pendingRows = 0
        var row: MutableList<String>? = null
        var pendingCells = 0
        var rowRepeat = 1
        var cellRepeat = 1
        var cell: StringBuilder? = null
        var paragraphs = 0
        // text counts only inside paragraphs, not in comments or between elements
        var paragraphDepth = 0
        var annotationDepth = 0
        var width = 0

        parser.walk(
            onStart = {
                when {
                    parser.isTable("table") -> {
                        sheetName = parser.getAttributeValue(TABLE, "name").orEmpty()
                        rows = ArrayList()
                        pendingRows = 0
                        width = 0
                    }
                    parser.isTable("table-row") -> {
                        row = ArrayList()
                        pendingCells = 0
                        rowRepeat = parser.getAttributeValue(TABLE, "number-rows-repeated")?.toIntOrNull() ?: 1
                    }
                    parser.isTable("table-cell") || parser.isTable("covered-table-cell") -> {
                        cell = StringBuilder()
                        paragraphs = 0
                        cellRepeat = parser.getAttributeValue(TABLE, "number-columns-repeated")?.toIntOrNull() ?: 1
                    }
                    parser.namespace == OFFICE && parser.name == "annotation" -> annotationDepth++
                    parser.isText("p") -> {
                        paragraphDepth++
                        if (cell != null && annotationDepth == 0 && paragraphs++ > 0) cell?.append('\n')
                    }
                    cell != null && parser.isText("s") -> cell?.append(" ".repeat(parser.getAttributeValue(TEXT, "c")?.toIntOrNull() ?: 1))
                    cell != null && parser.isText("tab") -> cell?.append('\t')
                    cell != null && parser.isText("line-break") -> cell?.append('\n')
                }
            },
            onEnd = {
                when {
                    parser.namespace == OFFICE && parser.name == "annotation" -> annotationDepth--
                    parser.isText("p") -> paragraphDepth--
                    parser.isTable("table-cell") || parser.isTable("covered-table-cell") -> {
                        val text = cell?.toString().orEmpty()
                        val cells = row
                        if (cells != null) {
                            if (text.isEmpty()) {
                                pendingCells += cellRepeat
                            } else {
                                repeat(pendingCells.coerceAtMost(MAX_COLUMNS - cells.size).coerceAtLeast(0)) { cells.add("") }
                                pendingCells = 0
                                repeat(cellRepeat.coerceAtMost(MAX_COLUMNS - cells.size).coerceAtLeast(0)) { cells.add(text) }
                                width = maxOf(width, cells.size)
                            }
                        }
                        cell = null
                    }
                    parser.isTable("table-row") -> {
                        val cells = row.orEmpty()
                        if (cells.isEmpty()) {
                            pendingRows += rowRepeat
                        } else {
                            repeat(pendingRows.coerceAtMost(MAX_ROWS - rows.size).coerceAtLeast(0)) { rows.add(emptyList()) }
                            pendingRows = 0
                            repeat(rowRepeat.coerceAtMost(MAX_ROWS - rows.size).coerceAtLeast(0)) { rows.add(cells) }
                        }
                        row = null
                    }
                    parser.isTable("table") -> sheets.add(Sheet(sheetName, rows, width))
                }
            },
            onText = { if (paragraphDepth > 0 && annotationDepth == 0) cell?.append(parser.text) },
        )
        return sheets
    }

    private fun XmlPullParser.isTable(name: String) = namespace == TABLE && this.name == name

    private fun XmlPullParser.isText(name: String) = namespace == TEXT && this.name == name

    private companion object {
        const val CONTENT = "content.xml"
        const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
        const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
        const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
        const val MAX_ROWS = 100_000
        const val MAX_COLUMNS = 500
    }
}
