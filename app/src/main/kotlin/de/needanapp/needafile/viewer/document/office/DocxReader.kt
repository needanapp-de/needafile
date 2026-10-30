package de.needanapp.needafile.viewer.document.office

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import org.xmlpull.v1.XmlPullParser

/**
 * Word documents (DOCX) as simple HTML, read only: headings, paragraphs, bold/italic/underlined/struck text, raised
 * and lowered text, lists, tables, links, bookmarks, footnotes and endnotes, and pictures stored in the file. Fonts,
 * colors and page layout aren't taken over; the viewer shows the text in the app's style.
 */
internal class DocxReader {
    fun read(resolver: ContentResolver, uri: Uri): String {
        val pkg = OfficePackage.read(resolver, uri) { it.startsWith("word/") }
        val styles = pkg.parser(STYLES)?.let(::readStyles) ?: Styles(emptyMap())
        val lists = pkg.parser(NUMBERING)?.let(::readNumbering) ?: Numbering(emptyMap(), emptyMap())
        val document = Part(pkg, DOCUMENT, styles, lists)
        val body = document.convert(pkg.requireParser(DOCUMENT))

        // the notes the text refers to, numbered in the order they appear
        val notes = StringBuilder()
        for ((kind, partName) in listOf(NoteKind.FOOTNOTE to FOOTNOTES, NoteKind.ENDNOTE to ENDNOTES)) {
            val wanted = document.noteReferences.filter { it.kind == kind }
            if (wanted.isEmpty()) continue
            val texts = pkg.parser(partName)?.let { Part(pkg, partName, styles, lists).convertNotes(it) }.orEmpty()
            wanted.forEach { reference ->
                val text = texts[reference.id] ?: return@forEach
                notes.append("<li id=\"${reference.anchor}\">$text</li>")
            }
        }
        return if (notes.isEmpty()) body else "$body<hr><ol>$notes</ol>"
    }

    /** What a style says: the formats it switches on ("b", "i", "u", "s"), its heading level, the style it builds on. */
    private class StyleInfo(val formats: Map<String, Boolean>, val headingLevel: Int?, val basedOn: String?)

    private class Styles(private val styles: Map<String, StyleInfo>) {
        fun formats(id: String?): Set<String> {
            val result = HashMap<String, Boolean>()
            // the style's own settings win over those it builds on
            chain(id).asReversed().forEach { result.putAll(it.formats) }
            return result.filterValues { it }.keys
        }

        fun headingLevel(id: String?): Int? = chain(id).firstNotNullOfOrNull { it.headingLevel }

        private fun chain(id: String?): List<StyleInfo> {
            val chain = ArrayList<StyleInfo>()
            var current = id
            while (current != null && chain.size < MAX_STYLE_DEPTH) {
                val style = styles[current] ?: break
                chain.add(style)
                current = style.basedOn
            }
            return chain
        }
    }

    private fun readStyles(parser: XmlPullParser): Styles {
        val styles = HashMap<String, StyleInfo>()
        var id: String? = null
        var name: String? = null
        var basedOn: String? = null
        var outlineLevel: Int? = null
        val formats = HashMap<String, Boolean>()
        parser.walk(
            onStart = {
                when (parser.name) {
                    "w:style" -> {
                        id = parser.attribute("styleId")
                        name = null
                        basedOn = null
                        outlineLevel = null
                        formats.clear()
                    }
                    "w:name" -> name = parser.attribute("val")
                    "w:basedOn" -> basedOn = parser.attribute("val")
                    "w:outlineLvl" -> outlineLevel = parser.attribute("val")?.toIntOrNull()
                    else -> formatOf(parser)?.let { (format, on) -> if (id != null) formats[format] = on }
                }
            },
            onEnd = {
                val styleId = id
                if (parser.name == "w:style" && styleId != null) {
                    // style names are English in every file ("heading 1"), the ids may be translated
                    val level = name?.let { HEADING_NAME.matchEntire(it.lowercase()) }?.groupValues?.get(1)?.toIntOrNull()
                        ?: if (name.equals("title", ignoreCase = true)) 1 else null
                    val heading = (level ?: outlineLevel?.takeIf { it < MAX_OUTLINE_LEVEL }?.plus(1))?.coerceIn(1, MAX_HEADING)
                    styles[styleId] = StyleInfo(formats.toMap(), heading, basedOn)
                    id = null
                }
            },
        )
        return Styles(styles)
    }

    /** Which list levels count up (1., a., i.) instead of showing bullets. */
    private class Numbering(private val abstractOf: Map<String, String>, private val ordered: Map<Pair<String, Int>, Boolean>) {
        fun isOrdered(numId: String, level: Int) = abstractOf[numId]?.let { ordered[it to level] } ?: false
    }

    private fun readNumbering(parser: XmlPullParser): Numbering {
        val abstractOf = HashMap<String, String>()
        val ordered = HashMap<Pair<String, Int>, Boolean>()
        var abstractId: String? = null
        var level = 0
        var numId: String? = null
        parser.walk(onStart = {
            when (parser.name) {
                "w:abstractNum" -> abstractId = parser.attribute("abstractNumId")
                "w:lvl" -> level = parser.attribute("ilvl")?.toIntOrNull() ?: 0
                "w:numFmt" -> abstractId?.let { ordered[it to level] = parser.attribute("val") !in UNORDERED_FORMATS }
                "w:num" -> {
                    abstractId = null
                    numId = parser.attribute("numId")
                }
                "w:abstractNumId" -> numId?.let { id -> parser.attribute("val")?.let { abstractOf[id] = it } }
            }
        })
        return Numbering(abstractOf, ordered)
    }

    private enum class NoteKind { FOOTNOTE, ENDNOTE }

    private class NoteReference(val kind: NoteKind, val id: String, val anchor: String)

    /** Where converted HTML goes: the body, a table cell or a note, each with its own open lists. */
    private class Container {
        val html = StringBuilder()
        val openLists = ArrayList<String>()
        // the Word list the open lists belong to
        var listId: String? = null

        fun closeLists(downTo: Int = 0) {
            while (openLists.size > downTo) html.append("</${openLists.removeAt(openLists.lastIndex)}>")
            if (openLists.isEmpty()) listId = null
        }
    }

    /** A paragraph while it is read: its settings come first in the file, its tag is only known at its end. */
    private class Paragraph {
        val html = StringBuilder()
        var styleId: String? = null
        var numId: String? = null
        var listLevel = 0
        var outlineLevel: Int? = null
    }

    /** A run of equally formatted text; its formats come before its text. */
    private class Run {
        val html = StringBuilder()
        var styleId: String? = null
        val direct = HashMap<String, Boolean>()
    }

    /** One XML part of the document (the text itself or the notes) with the relationships it refers through. */
    private class Part(
        private val pkg: OfficePackage,
        partName: String,
        private val styles: Styles,
        private val numbering: Numbering,
    ) {
        private val links = readRelationships(pkg, partName)
        val noteReferences = ArrayList<NoteReference>()

        private val containers = ArrayList<Container>()
        private val paragraphs = ArrayList<Paragraph>()
        private val cellSpans = ArrayList<Int>()
        private var run: Run? = null
        private var skipDepth = 0
        private var inText = false
        private var pictureBytes = 0L

        fun convert(parser: XmlPullParser): String {
            containers.add(Container())
            walk(parser)
            return containers.first().also { it.closeLists() }.html.toString()
        }

        /** The notes of a footnotes or endnotes part by their id; separators and other special notes are left out. */
        fun convertNotes(parser: XmlPullParser): Map<String, String> {
            val notes = HashMap<String, String>()
            var noteId: String? = null
            parser.walk(
                onStart = {
                    if (parser.name == "w:footnote" || parser.name == "w:endnote") {
                        noteId = parser.attribute("id").takeIf { parser.attribute("type") == null }
                        containers.add(Container())
                    } else if (noteId != null) {
                        start(parser)
                    }
                },
                onEnd = {
                    if (parser.name == "w:footnote" || parser.name == "w:endnote") {
                        val container = containers.removeAt(containers.lastIndex).also { it.closeLists() }
                        noteId?.let { notes[it] = container.html.toString() }
                        noteId = null
                    } else if (noteId != null) {
                        end(parser)
                    }
                },
                onText = { if (noteId != null) text(parser) },
            )
            return notes
        }

        private fun walk(parser: XmlPullParser) = parser.walk(onStart = { start(parser) }, onEnd = { end(parser) }, onText = { text(parser) })

        private fun start(parser: XmlPullParser) {
            val name = parser.name
            if (skipDepth > 0 || name in SKIPPED) {
                skipDepth++
                return
            }
            val paragraph = paragraphs.lastOrNull()
            val run = run
            when (name) {
                "w:p" -> paragraphs.add(Paragraph())
                "w:pStyle" -> paragraph?.styleId = parser.attribute("val")
                "w:numId" -> paragraph?.numId = parser.attribute("val")?.takeIf { it != "0" }
                "w:ilvl" -> paragraph?.listLevel = parser.attribute("val")?.toIntOrNull() ?: 0
                "w:outlineLvl" -> paragraph?.outlineLevel = parser.attribute("val")?.toIntOrNull()
                "w:r" -> this.run = Run()
                "w:rStyle" -> run?.styleId = parser.attribute("val")
                "w:t" -> inText = true
                "w:tab" -> if (run != null) run.html.append("&emsp;")
                "w:br", "w:cr" -> if (run != null && parser.attribute("type") != "page") run.html.append("<br>")
                "w:noBreakHyphen" -> run?.html?.append("-")
                "w:hyperlink" -> {
                    val target = parser.attribute("anchor")?.let { "#${anchorId(it)}" }
                        ?: parser.attribute("id")?.let { links[it]?.takeIf { link -> link.external }?.target }
                    paragraph?.html?.append(if (target != null) "<a href=\"${escape(target)}\">" else "<a>")
                }
                "w:bookmarkStart" -> parser.attribute("name")?.takeIf { !it.startsWith("_") }?.let {
                    paragraph?.html?.append("<a id=\"${escape(anchorId(it))}\"></a>")
                }
                "w:footnoteReference", "w:endnoteReference" -> parser.attribute("id")?.let { id ->
                    val kind = if (name == "w:footnoteReference") NoteKind.FOOTNOTE else NoteKind.ENDNOTE
                    val reference = NoteReference(kind, id, "${kind.name.lowercase()}-$id")
                    noteReferences.add(reference)
                    run?.html?.append("<sup><a href=\"#${reference.anchor}\">${noteReferences.size}</a></sup>")
                }
                "a:blip" -> picture(parser.attribute("embed"))
                "v:imagedata" -> picture(parser.attribute("id"))
                "w:tbl" -> {
                    container().closeLists()
                    container().html.append("<table>")
                }
                "w:tr" -> container().html.append("<tr>")
                "w:tc" -> {
                    containers.add(Container())
                    cellSpans.add(1)
                }
                "w:gridSpan" -> if (cellSpans.isNotEmpty()) cellSpans[cellSpans.lastIndex] = parser.attribute("val")?.toIntOrNull() ?: 1
                else -> formatOf(parser)?.let { (format, on) -> run?.direct?.put(format, on) }
            }
        }

        private fun end(parser: XmlPullParser) {
            if (skipDepth > 0) {
                skipDepth--
                return
            }
            when (parser.name) {
                "w:p" -> paragraphs.removeLastOrNull()?.let(::finishParagraph)
                "w:r" -> run?.let(::finishRun).also { run = null }
                "w:t" -> inText = false
                "w:hyperlink" -> paragraphs.lastOrNull()?.html?.append("</a>")
                "w:tbl" -> container().html.append("</table>")
                "w:tr" -> container().html.append("</tr>")
                "w:tc" -> {
                    if (containers.size < 2) return
                    val cell = containers.removeAt(containers.lastIndex).also { it.closeLists() }
                    val span = cellSpans.removeLastOrNull() ?: 1
                    container().html.append(if (span > 1) "<td colspan=\"$span\">" else "<td>").append(cell.html).append("</td>")
                }
            }
        }

        private fun text(parser: XmlPullParser) {
            if (skipDepth == 0 && inText) run?.html?.append(escape(parser.text))
        }

        private fun container() = containers.last()

        private fun finishRun(run: Run) {
            if (run.html.isEmpty()) return
            val formats = HashMap<String, Boolean>()
            styles.formats(run.styleId).forEach { formats[it] = true }
            formats.putAll(run.direct)
            val on = FORMAT_ORDER.filter { formats[it] == true }
            val target = paragraphs.lastOrNull()?.html ?: container().html
            on.forEach { target.append("<$it>") }
            target.append(run.html)
            on.asReversed().forEach { target.append("</$it>") }
        }

        private fun finishParagraph(paragraph: Paragraph) {
            val container = container()
            val numId = paragraph.numId
            if (numId == null) {
                container.closeLists()
                val level = styles.headingLevel(paragraph.styleId)
                    ?: paragraph.outlineLevel?.takeIf { it < MAX_OUTLINE_LEVEL }?.plus(1)?.coerceIn(1, MAX_HEADING)
                val tag = level?.let { "h$it" } ?: "p"
                // empty paragraphs are the word processor's spacing, the viewer has its own
                if (paragraph.html.isNotBlank()) container.html.append("<$tag>").append(paragraph.html).append("</$tag>")
                return
            }
            // a list right after another one starts afresh
            if (container.listId != null && container.listId != numId) container.closeLists()
            container.listId = numId
            // one list per level; a deeper level opens a list inside the current one
            val depth = paragraph.listLevel.coerceIn(0, MAX_LIST_DEPTH) + 1
            container.closeLists(downTo = depth)
            while (container.openLists.size < depth) {
                val tag = if (numbering.isOrdered(numId, container.openLists.size)) "ol" else "ul"
                container.openLists.add(tag)
                container.html.append("<$tag>")
            }
            container.html.append("<li>").append(paragraph.html).append("</li>")
        }

        /** A picture stored in the file, as data the web view can show; nothing from outside is loaded. */
        private fun picture(relationshipId: String?) {
            val link = relationshipId?.let { links[it] }?.takeIf { !it.external } ?: return
            val bytes = pkg[link.target] ?: return
            val type = PICTURE_TYPES[link.target.substringAfterLast('.').lowercase()] ?: return
            if (bytes.size > MAX_PICTURE_BYTES || pictureBytes + bytes.size > MAX_PICTURES_BYTES) return
            pictureBytes += bytes.size
            val target = run?.html ?: paragraphs.lastOrNull()?.html ?: container().html
            target.append("<img src=\"data:$type;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}\">")
        }
    }

    /** Where a relationship points: a part of the file (its full name) or, when [external], an address. */
    private class Link(val target: String, val external: Boolean)

    private companion object {
        const val DOCUMENT = "word/document.xml"
        const val STYLES = "word/styles.xml"
        const val NUMBERING = "word/numbering.xml"
        const val FOOTNOTES = "word/footnotes.xml"
        const val ENDNOTES = "word/endnotes.xml"
        const val MAX_HEADING = 6
        const val MAX_OUTLINE_LEVEL = 9
        const val MAX_STYLE_DEPTH = 20
        const val MAX_LIST_DEPTH = 8
        const val MAX_PICTURE_BYTES = 5L * 1024 * 1024
        const val MAX_PICTURES_BYTES = 20L * 1024 * 1024
        val HEADING_NAME = Regex("heading (\\d)")
        val UNORDERED_FORMATS = setOf("bullet", "none")
        val FORMAT_ORDER = listOf("b", "i", "u", "s", "sup", "sub")
        val PICTURE_TYPES = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "bmp" to "image/bmp",
            "webp" to "image/webp",
        )

        // not part of the running text: deleted text of tracked changes, the fallback copy of drawings (the same
        // content again), comments, and the settings of paragraphs and sections that hold no text
        val SKIPPED = setOf("w:del", "w:moveFrom", "mc:Fallback", "w:commentReference", "w:sectPr", "w:instrText", "w:delText")

        /** The format a run or style setting switches on or off, like `<w:b/>` or `<w:u w:val="none"/>`. */
        fun formatOf(parser: XmlPullParser): Pair<String, Boolean>? {
            val value = parser.attribute("val")
            val toggled = value == null || value !in OFF_VALUES
            return when (parser.name) {
                "w:b" -> "b" to toggled
                "w:i" -> "i" to toggled
                "w:strike", "w:dstrike" -> "s" to toggled
                "w:u" -> "u" to (value != "none" && value !in OFF_VALUES)
                "w:vertAlign" -> when (value) {
                    "superscript" -> "sup" to true
                    "subscript" -> "sub" to true
                    else -> null
                }
                else -> null
            }
        }

        val OFF_VALUES = setOf("0", "false", "off")

        /** The relationships of [partName] by their id, with targets resolved to part names. */
        fun readRelationships(pkg: OfficePackage, partName: String): Map<String, Link> {
            val folder = partName.substringBeforeLast('/')
            val relsName = "$folder/_rels/${partName.substringAfterLast('/')}.rels"
            val parser = pkg.parser(relsName) ?: return emptyMap()
            val links = HashMap<String, Link>()
            parser.walk(onStart = {
                if (parser.localName == "Relationship") {
                    val id = parser.attribute("Id")
                    val target = parser.attribute("Target")
                    if (id != null && target != null) {
                        val external = parser.attribute("TargetMode") == "External"
                        links[id] = Link(if (external) target else resolvePart(folder, target), external)
                    }
                }
            })
            return links
        }

        /** "media/image1.png" next to "word/document.xml" is "word/media/image1.png"; "/x" starts at the top. */
        fun resolvePart(folder: String, target: String): String {
            val parts = ArrayList<String>()
            if (!target.startsWith("/")) parts.addAll(folder.split('/').filter { it.isNotEmpty() })
            target.split('/').filter { it.isNotEmpty() && it != "." }.forEach { if (it == "..") parts.removeLastOrNull() else parts.add(it) }
            return parts.joinToString("/")
        }

        /** Bookmark names as ids that stay valid in HTML. */
        fun anchorId(name: String) = "bookmark-" + name.replace(Regex("[^A-Za-z0-9_-]"), "_")

        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
