package de.needanapp.needafile.viewer.document

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * The bytes of a text file as text: by its byte order mark when it has one, otherwise as UTF-8 when they are valid
 * UTF-8, otherwise as Windows-1252, which covers most older files from Western Europe.
 */
internal fun decodeText(bytes: ByteArray): String {
    fun startsWith(vararg mark: Int) = bytes.size >= mark.size && mark.indices.all { bytes[it].toInt() and 0xFF == mark[it] }
    val marked = when {
        startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
        startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
        startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
        else -> null
    }
    if (marked != null) return String(bytes, marked.second, bytes.size - marked.second, marked.first)
    val strictUtf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        strictUtf8.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charset.forName(FALLBACK_CHARSET))
    }
}

/** Markdown with the GitHub additions (tables, strikethrough, task lists, bare links) as cleaned HTML. */
internal fun markdownToHtml(text: String): String {
    val parser = Parser.builder().extensions(MARKDOWN_EXTENSIONS).build()
    // HTML written into the Markdown shows as text, the document can't bring its own markup along
    val renderer = HtmlRenderer.builder().extensions(MARKDOWN_EXTENSIONS).escapeHtml(true).sanitizeUrls(true).build()
    return HtmlCleaner.clean(renderer.render(parser.parse(text)), isFragment = true)
}

/**
 * A CSV or TSV file as one table. The separator is guessed from the first lines: comma, semicolon, tab or bar,
 * whichever splits them most evenly. Quotes that don't close are taken as they are instead of failing.
 */
internal fun readCsv(text: String, name: String): Sheet {
    val format = CSVFormat.DEFAULT.builder()
        .setDelimiter(guessSeparator(text, name))
        .setLenientEof(true)
        .setTrailingData(true)
        .get()
    val rows = CSVParser.parse(text, format).use { parser -> parser.map { it.toList() } }
    return Sheet("", rows, rows.maxOfOrNull { it.size } ?: 0)
}

/** The separator that gives the first lines the same, largest number of columns. */
internal fun guessSeparator(text: String, name: String): Char {
    if (name.endsWith(".tsv", ignoreCase = true)) return '\t'
    val lines = text.lineSequence().filter { it.isNotBlank() }.take(SAMPLE_LINES).toList()
    var best = ','
    var bestScore = 0
    for (candidate in SEPARATORS) {
        val counts = lines.map { countOutsideQuotes(it, candidate) }
        val first = counts.firstOrNull() ?: 0
        if (first == 0) continue
        // lines that agree with the first one count most, the number of columns decides between equals
        val score = counts.count { it == first } * SAMPLE_LINES * 100 + first
        if (score > bestScore) {
            best = candidate
            bestScore = score
        }
    }
    return best
}

private fun countOutsideQuotes(line: String, separator: Char): Int {
    var quoted = false
    var count = 0
    for (char in line) {
        when {
            char == '"' -> quoted = !quoted
            char == separator && !quoted -> count++
        }
    }
    return count
}

/**
 * Makes HTML from documents safe for the viewer: no scripts, forms, frames, styles or event handlers; text, headings,
 * lists, tables, links and images stay. Images may be embedded (data:), links to the web or mail open outside.
 */
internal object HtmlCleaner {
    private val allowed: Safelist = Safelist.relaxed()
        .addTags(
            "hr", "s", "del", "ins", "mark", "abbr", "kbd", "samp", "var", "figure", "figcaption", "section", "article",
            "header", "footer", "main", "aside", "nav", "input",
        )
        .addAttributes(":all", "id", "title", "lang", "dir")
        .addAttributes("input", "type", "checked", "disabled")
        .addProtocols("img", "src", "data")
        // jumps within the document ("#footnote-1") keep working
        .preserveRelativeLinks(true)

    fun clean(html: String, isFragment: Boolean): String {
        val dirty = if (isFragment) Jsoup.parseBodyFragment(html, BASE_URI) else Jsoup.parse(html, BASE_URI)
        val clean = Cleaner(allowed).clean(dirty)
        // only the check boxes of task lists, and those can't be ticked
        clean.select("input:not([type=checkbox])").remove()
        clean.select("input").attr("disabled", "disabled")
        clean.outputSettings().prettyPrint(false)
        return clean.body().html()
    }

    /** Whether a link from a document may open in another app: web pages and mail only. */
    fun isExternalLink(url: String): Boolean = url.trim().substringBefore(':', "").lowercase() in EXTERNAL_SCHEMES
}

private val MARKDOWN_EXTENSIONS = listOf(
    TablesExtension.create(),
    StrikethroughExtension.create(),
    TaskListItemsExtension.create(),
    AutolinkExtension.create(),
)
private val SEPARATORS = listOf(',', ';', '\t', '|')
private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto")
private const val SAMPLE_LINES = 10
private const val FALLBACK_CHARSET = "windows-1252"
private const val BASE_URI = "https://document.needafile.invalid/"
