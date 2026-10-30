package de.needanapp.needafile.viewer.document.office

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import org.xmlpull.v1.XmlPullParser

/**
 * OpenDocument texts (ODT, e.g. from LibreOffice) as simple HTML, read only: headings, paragraphs, bold/italic/
 * underlined/struck text, lists, tables, links and pictures stored in the file. Layout (fonts, colors, page
 * setup) isn't taken over; the viewer shows the text in the app's style.
 */
internal class OdtReader {
    fun read(resolver: ContentResolver, uri: Uri): String {
        val pkg = OfficePackage.read(resolver, uri) { it == CONTENT || it == STYLES || it.startsWith("Pictures/") }
        val styles = HashMap<String, StyleInfo>()
        // named styles, then the automatic ones in the text itself (where most bold and italic text comes from)
        pkg.parser(STYLES, namespaces = true)?.let { readStyles(it, styles) }
        pkg.parser(CONTENT, namespaces = true)?.let { readStyles(it, styles) }
        val parser = pkg.requireParser(CONTENT, namespaces = true)
        return Converter(parser, Styles(styles), pkg).convert()
    }

    /** What a style says about the text: formats ("b", "i", "u", "s"), heading level, the style it builds on. */
    private class StyleInfo(val formats: Set<String>, val outlineLevel: Int?, val parent: String?)

    /** Styles with what they take over from their parents. */
    private class Styles(private val styles: Map<String, StyleInfo>) {
        fun formats(name: String?): Set<String> = chain(name).flatMapTo(HashSet()) { it.formats }

        fun outlineLevel(name: String?): Int? = chain(name).firstNotNullOfOrNull { it.outlineLevel }

        private fun chain(name: String?): List<StyleInfo> {
            val chain = ArrayList<StyleInfo>()
            var current = name
            while (current != null && chain.size < MAX_STYLE_DEPTH) {
                val style = styles[current] ?: break
                chain.add(style)
                current = style.parent
            }
            return chain
        }
    }

    private fun readStyles(parser: XmlPullParser, styles: MutableMap<String, StyleInfo>) {
        var name: String? = null
        var parent: String? = null
        var outlineLevel: Int? = null
        val formats = HashSet<String>()
        parser.walk(
            onStart = {
                when {
                    parser.namespace == STYLE_NS && parser.name == "style" -> {
                        name = parser.getAttributeValue(STYLE_NS, "name")
                        parent = parser.getAttributeValue(STYLE_NS, "parent-style-name")
                        // headings carry their level; files converted from HTML only have the style name
                        outlineLevel = parser.getAttributeValue(STYLE_NS, "default-outline-level")?.toIntOrNull()
                            ?: name?.let { HEADING_STYLE.matchEntire(it) }?.groupValues?.get(1)?.toIntOrNull()
                        formats.clear()
                    }
                    parser.namespace == STYLE_NS && parser.name == "text-properties" && name != null -> {
                        if (parser.getAttributeValue(FO, "font-weight") == "bold") formats.add("b")
                        if (parser.getAttributeValue(FO, "font-style") == "italic") formats.add("i")
                        parser.getAttributeValue(STYLE_NS, "text-underline-style")?.let { if (it != "none") formats.add("u") }
                        parser.getAttributeValue(STYLE_NS, "text-line-through-style")?.let { if (it != "none") formats.add("s") }
                    }
                }
            },
            onEnd = {
                if (parser.namespace == STYLE_NS && parser.name == "style") {
                    name?.let { styles[it] = StyleInfo(formats.toSet(), outlineLevel, parent) }
                    name = null
                }
            },
        )
    }

    private class Converter(private val parser: XmlPullParser, private val styles: Styles, private val pkg: OfficePackage) {
        private val html = StringBuilder()
        // what each open element wrote, so its end can close it again
        private val closers = ArrayDeque<String>()
        private var inBody = false
        private var skipDepth = 0
        private var pictureBytes = 0L

        fun convert(): String {
            parser.walk(onStart = ::start, onEnd = ::end, onText = ::text)
            return html.toString()
        }

        private fun start() {
            val ns = parser.namespace
            val name = parser.name
            if (ns == OFFICE && name == "text") inBody = true
            if (!inBody) return
            if (skipDepth > 0 || ns == OFFICE && name == "annotation" || ns == TEXT && name in skipped) {
                skipDepth++
                closers.addLast("")
                return
            }
            val closer = when {
                ns == TEXT && name == "h" -> {
                    val level = (parser.getAttributeValue(TEXT, "outline-level")?.toIntOrNull() ?: 1).coerceIn(1, MAX_HEADING)
                    open("h$level", styled(), "")
                }
                ns == TEXT && name == "p" -> {
                    // a paragraph in a heading style is shown as a heading, as the word processor does
                    val level = styles.outlineLevel(styleName())?.coerceIn(1, MAX_HEADING)
                    open(level?.let { "h$it" } ?: "p", styled(), "")
                }
                ns == TEXT && name == "span" -> open(null, styled(), "")
                ns == TEXT && name == "list" -> open("ul", emptySet(), "")
                ns == TEXT && (name == "list-item" || name == "list-header") -> open("li", emptySet(), "")
                ns == TEXT && name == "a" -> {
                    val href = parser.getAttributeValue(XLINK, "href").orEmpty()
                    open("a", emptySet(), " href=\"${escape(href)}\"")
                }
                ns == TEXT && name == "s" -> {
                    html.append("&nbsp;".repeat((parser.getAttributeValue(TEXT, "c")?.toIntOrNull() ?: 1).coerceIn(1, MAX_SPACES)))
                    ""
                }
                ns == TEXT && name == "tab" -> {
                    html.append("&emsp;")
                    ""
                }
                ns == TEXT && name == "line-break" -> {
                    html.append("<br>")
                    ""
                }
                ns == TABLE && name == "table" -> open("table", emptySet(), "")
                ns == TABLE && name == "table-row" -> open("tr", emptySet(), "")
                ns == TABLE && name == "table-cell" -> {
                    val columns = parser.getAttributeValue(TABLE, "number-columns-spanned")?.toIntOrNull() ?: 1
                    val rows = parser.getAttributeValue(TABLE, "number-rows-spanned")?.toIntOrNull() ?: 1
                    val span = (if (columns > 1) " colspan=\"$columns\"" else "") + (if (rows > 1) " rowspan=\"$rows\"" else "")
                    open("td", emptySet(), span)
                }
                ns == DRAW && name == "image" -> {
                    picture(parser.getAttributeValue(XLINK, "href"))
                    ""
                }
                else -> ""
            }
            closers.addLast(closer)
        }

        private fun end() {
            if (!inBody) return
            if (parser.namespace == OFFICE && parser.name == "text") {
                inBody = false
                return
            }
            val closer = closers.removeLastOrNull() ?: return
            if (skipDepth > 0) {
                skipDepth--
                return
            }
            html.append(closer)
        }

        private fun text() {
            if (inBody && skipDepth == 0 && closers.isNotEmpty()) html.append(escape(parser.text))
        }

        /** Writes the opening tags and returns the closing ones. */
        private fun open(tag: String?, inline: Set<String>, attributes: String): String {
            val formatting = listOf("b", "i", "u", "s").filter { it in inline }
            tag?.let { html.append("<$it$attributes>") }
            formatting.forEach { html.append("<$it>") }
            return formatting.reversed().joinToString("") { "</$it>" } + (tag?.let { "</$it>" } ?: "")
        }

        private fun styleName(): String? = parser.getAttributeValue(TEXT, "style-name")

        private fun styled(): Set<String> = styles.formats(styleName())

        /** A picture stored in the file, as data the web view can show; nothing from outside is loaded. */
        private fun picture(href: String?) {
            if (href == null || !href.startsWith("Pictures/")) return
            val bytes = pkg[href] ?: return
            val type = when (href.substringAfterLast('.').lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                else -> return
            }
            if (bytes.size > MAX_PICTURE_BYTES || pictureBytes + bytes.size > MAX_PICTURES_BYTES) return
            pictureBytes += bytes.size
            html.append("<img src=\"data:$type;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}\">")
        }

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    private companion object {
        const val CONTENT = "content.xml"
        const val STYLES = "styles.xml"
        const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
        const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
        const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
        const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
        const val STYLE_NS = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
        const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
        const val XLINK = "http://www.w3.org/1999/xlink"
        const val MAX_HEADING = 6
        const val MAX_SPACES = 100
        const val MAX_PICTURE_BYTES = 5L * 1024 * 1024
        const val MAX_PICTURES_BYTES = 20L * 1024 * 1024
        const val MAX_STYLE_DEPTH = 20
        val HEADING_STYLE = Regex("Heading_20_(\\d+)")

        // not part of the running text: footnote bodies, change tracking, the table of contents' source
        val skipped = setOf("note", "tracked-changes", "table-of-content-source", "sequence-decls", "variable-decls")
    }
}
