package de.needanapp.needafile.viewer.document.office

import android.content.ContentResolver
import android.net.Uri
import de.needanapp.needafile.viewer.document.Sheet
import org.xmlpull.v1.XmlPullParser
import java.math.BigDecimal
import java.math.MathContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.floor

/**
 * Excel workbooks (XLSX), read only: every sheet with the values Excel last calculated. Numbers, dates, times and
 * percentages are shown in the user's format; other number formats (currency, fixed decimals) as plain numbers.
 */
internal class XlsxReader(private val trueText: String, private val falseText: String) {
    fun read(resolver: ContentResolver, uri: Uri): List<Sheet> {
        val pkg = OfficePackage.read(resolver, uri) { it.startsWith("xl/") && (it.endsWith(".xml") || it.endsWith(".rels")) }
        val strings = sharedStrings(pkg)
        val styles = cellStyles(pkg)
        val targets = relationships(pkg)
        val (sheets, date1904) = workbook(pkg)
        return sheets.mapNotNull { (name, relationId) ->
            val target = targets[relationId] ?: return@mapNotNull null
            val part = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
            val parser = pkg.parser(part) ?: return@mapNotNull null
            readSheet(name, parser, strings, styles, date1904)
        }
    }

    /** Names and relation ids of the sheets in their order, and whether dates count from 1904 (old Mac files). */
    private fun workbook(pkg: OfficePackage): Pair<List<Pair<String, String>>, Boolean> {
        val parser = pkg.requireParser("xl/workbook.xml")
        val sheets = ArrayList<Pair<String, String>>()
        var date1904 = false
        parser.walk(onStart = {
            when (parser.localName) {
                "sheet" -> {
                    val id = parser.attribute("id")
                    if (id != null) sheets.add((parser.attribute("name") ?: "") to id)
                }
                "workbookPr" -> date1904 = parser.attribute("date1904").let { it == "1" || it == "true" }
            }
        })
        return sheets to date1904
    }

    private fun relationships(pkg: OfficePackage): Map<String, String> {
        val parser = pkg.parser("xl/_rels/workbook.xml.rels") ?: return emptyMap()
        val targets = HashMap<String, String>()
        parser.walk(onStart = {
            if (parser.localName == "Relationship") {
                val id = parser.attribute("Id")
                val target = parser.attribute("Target")
                if (id != null && target != null) targets[id] = target
            }
        })
        return targets
    }

    /** The texts cells refer to by number; rich text runs joined, phonetic hints left out. */
    private fun sharedStrings(pkg: OfficePackage): List<String> {
        val parser = pkg.parser("xl/sharedStrings.xml") ?: return emptyList()
        val strings = ArrayList<String>()
        val current = StringBuilder()
        var inText = false
        var inPhonetic = false
        parser.walk(
            onStart = {
                when (parser.localName) {
                    "si" -> current.setLength(0)
                    "t" -> inText = !inPhonetic
                    "rPh" -> inPhonetic = true
                }
            },
            onEnd = {
                when (parser.localName) {
                    "si" -> strings.add(current.toString())
                    "t" -> inText = false
                    "rPh" -> inPhonetic = false
                }
            },
            onText = { if (inText) current.append(parser.text) },
        )
        return strings
    }

    /** For each cell style: how its number is meant (date, time, percentage, plain). */
    private fun cellStyles(pkg: OfficePackage): List<NumberKind> {
        val parser = pkg.parser("xl/styles.xml") ?: return emptyList()
        val customFormats = HashMap<Int, String>()
        val kinds = ArrayList<NumberKind>()
        var inCellFormats = false
        parser.walk(
            onStart = {
                when (parser.localName) {
                    "numFmt" -> {
                        val id = parser.attribute("numFmtId")?.toIntOrNull()
                        val code = parser.attribute("formatCode")
                        if (id != null && code != null) customFormats[id] = code
                    }
                    "cellXfs" -> inCellFormats = true
                    "xf" -> if (inCellFormats) {
                        val id = parser.attribute("numFmtId")?.toIntOrNull() ?: 0
                        kinds.add(numberKind(id, customFormats[id]))
                    }
                }
            },
            onEnd = { if (parser.localName == "cellXfs") inCellFormats = false },
        )
        return kinds
    }

    private fun readSheet(name: String, parser: XmlPullParser, strings: List<String>, styles: List<NumberKind>, date1904: Boolean): Sheet {
        val rows = ArrayList<List<String>>()
        var row: MutableList<String>? = null
        var column = 0
        var cellType: String? = null
        var cellStyle = 0
        var value: StringBuilder? = null
        var inlineText: StringBuilder? = null
        var inValue = false
        var inInlineText = false
        var width = 0

        fun set(index: Int, text: String) {
            val cells = row ?: return
            if (index >= MAX_COLUMNS || text.isEmpty()) return
            while (cells.size <= index) cells.add("")
            cells[index] = text
            width = maxOf(width, index + 1)
        }

        parser.walk(
            onStart = {
                when (parser.localName) {
                    "row" -> {
                        // rows can be left out; their number says where this one belongs
                        val number = parser.attribute("r")?.toIntOrNull()
                        if (number != null) while (rows.size < number - 1 && rows.size < MAX_ROWS) rows.add(emptyList())
                        row = ArrayList()
                        column = 0
                    }
                    "c" -> {
                        column = parser.attribute("r")?.let(::columnIndex) ?: column
                        cellType = parser.attribute("t")
                        cellStyle = parser.attribute("s")?.toIntOrNull() ?: 0
                        value = StringBuilder()
                        inlineText = null
                    }
                    "v" -> inValue = true
                    "is" -> inlineText = StringBuilder()
                    "t" -> inInlineText = inlineText != null
                }
            },
            onEnd = {
                when (parser.localName) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        val raw = value?.toString().orEmpty()
                        val text = when (cellType) {
                            "s" -> raw.toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
                            "inlineStr" -> inlineText?.toString().orEmpty()
                            "b" -> if (raw == "1") trueText else falseText
                            "str", "e" -> raw
                            else -> formatNumber(raw, styles.getOrElse(cellStyle) { NumberKind.PLAIN }, date1904)
                        }
                        set(column, text)
                        column++
                    }
                    "row" -> {
                        if (rows.size < MAX_ROWS) rows.add(row.orEmpty())
                        row = null
                    }
                }
            },
            onText = {
                if (inValue) value?.append(parser.text)
                if (inInlineText) inlineText?.append(parser.text)
            },
        )
        // empty rows at the end (often formatted but unused) aren't shown
        while (rows.isNotEmpty() && rows.last().all { it.isEmpty() }) rows.removeAt(rows.lastIndex)
        return Sheet(name, rows, width)
    }

    private fun formatNumber(raw: String, kind: NumberKind, date1904: Boolean): String {
        val number = raw.toBigDecimalOrNull() ?: return raw
        return when (kind) {
            NumberKind.DATE, NumberKind.DATE_TIME, NumberKind.TIME -> formatDate(number.toDouble(), kind, date1904) ?: plain(number)
            NumberKind.PERCENT -> NumberFormat.getPercentInstance().apply { maximumFractionDigits = 2 }.format(number)
            NumberKind.PLAIN -> plain(number)
        }
    }

    private fun plain(number: BigDecimal): String = NumberFormat.getInstance().apply {
        maximumFractionDigits = MAX_FRACTION_DIGITS
        isGroupingUsed = false
    }.format(number.round(MathContext(SIGNIFICANT_DIGITS)))

    /** Excel counts days from 30 Dec 1899 (1 Jan 1904 in old Mac files); the fraction is the time of day. */
    private fun formatDate(serial: Double, kind: NumberKind, date1904: Boolean): String? {
        if (serial < 0 || serial > MAX_SERIAL) return null
        val base = if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 30)
        val days = floor(serial).toLong()
        val seconds = Math.round((serial - days) * SECONDS_PER_DAY)
        val dateTime = LocalDateTime.of(base, java.time.LocalTime.MIDNIGHT).plusDays(days).plusSeconds(seconds)
        val formatter = when {
            kind == NumberKind.TIME || days == 0L && seconds > 0 -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            kind == NumberKind.DATE_TIME || seconds > 0 -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        }
        return dateTime.format(formatter)
    }

    private enum class NumberKind { PLAIN, DATE, DATE_TIME, TIME, PERCENT }

    private fun numberKind(id: Int, customCode: String?): NumberKind {
        when (id) {
            in 14..17, 36, in 50..58 -> return NumberKind.DATE
            22 -> return NumberKind.DATE_TIME
            in 18..21, in 45..47 -> return NumberKind.TIME
            9, 10 -> return NumberKind.PERCENT
        }
        val code = customCode ?: return NumberKind.PLAIN
        // look only at the format itself, not at quoted text, colors or conditions in brackets
        val bare = code.replace(Regex("\"[^\"]*\"|\\[[^]]*]|\\\\."), "").lowercase()
        val hasDate = bare.any { it == 'd' || it == 'y' } || bare.contains('m') && !bare.contains(':')
        val hasTime = bare.contains('h') || bare.contains('s') && bare.contains(':')
        return when {
            hasDate && hasTime -> NumberKind.DATE_TIME
            hasDate -> NumberKind.DATE
            hasTime -> NumberKind.TIME
            bare.contains('%') -> NumberKind.PERCENT
            else -> NumberKind.PLAIN
        }
    }

    /** "C12" → 2 (the column, from 0). */
    private fun columnIndex(reference: String): Int? {
        var index = 0
        var letters = 0
        for (char in reference) {
            if (char !in 'A'..'Z') break
            index = index * ALPHABET + (char - 'A' + 1)
            letters++
        }
        return if (letters == 0) null else index - 1
    }

    private companion object {
        const val MAX_ROWS = 100_000
        const val MAX_COLUMNS = 500
        const val MAX_FRACTION_DIGITS = 10
        const val SIGNIFICANT_DIGITS = 15
        const val SECONDS_PER_DAY = 86_400
        const val MAX_SERIAL = 2_958_465.0 // 31 Dec 9999
        const val ALPHABET = 26
    }
}
