package de.needanapp.needafile.viewer.document.office

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import de.needanapp.needafile.viewer.document.DocumentDamagedException
import de.needanapp.needafile.viewer.document.DocumentUnreadableException
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * The parts of an Office file (DOCX, XLSX, ODS, ODT are ZIP archives of XML files) that a reader needs, read into
 * memory. Before that, [checkLimits] refuses files that would unpack to too much or bring XML tricks along.
 */
internal class OfficePackage private constructor(private val entries: Map<String, ByteArray>) {
    operator fun get(name: String): ByteArray? = entries[name]

    fun names(): Set<String> = entries.keys

    /** A parser over a part the file must have. */
    fun requireParser(name: String, namespaces: Boolean = false): XmlPullParser = parser(name, namespaces) ?: throw DocumentDamagedException()

    /** A parser over [name]; null when the part is missing. */
    fun parser(name: String, namespaces: Boolean = false): XmlPullParser? {
        val bytes = entries[name] ?: return null
        return Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, namespaces)
            setInput(ByteArrayInputStream(bytes), null)
        }
    }

    companion object {
        /** Reads the parts of [uri] for which [wanted] is true. */
        fun read(resolver: ContentResolver, uri: Uri, wanted: (String) -> Boolean): OfficePackage {
            open(resolver, uri).use(::checkLimits)
            val entries = HashMap<String, ByteArray>()
            ZipInputStream(open(resolver, uri).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && wanted(entry.name)) entries[entry.name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
            // every Office file has the parts its reader asks for; none at all means it isn't one
            if (entries.isEmpty()) throw DocumentDamagedException()
            return OfficePackage(entries)
        }

        /**
         * Goes once through the archive in [input] without keeping anything: more than [MAX_PARTS] parts or more than
         * [MAX_UNPACKED_BYTES] unpacked make it [DocumentUnreadableException], XML declaring a DOCTYPE (the way to
         * entity expansion tricks, which Office files never need) and an archive without parts [DocumentDamagedException].
         */
        fun checkLimits(input: InputStream) {
            var parts = 0
            var unpacked = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++parts > MAX_PARTS) throw DocumentUnreadableException()
                    val isXml = entry.name.endsWith(".xml", ignoreCase = true) || entry.name.endsWith(".rels", ignoreCase = true)
                    // the end of the previous read, so a declaration split between two reads is found as well
                    var carried = ""
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        unpacked += read
                        if (unpacked > MAX_UNPACKED_BYTES) throw DocumentUnreadableException()
                        if (isXml) {
                            // zero bytes out, so UTF-16 text reads like ASCII too
                            val text = carried + String(buffer, 0, read, Charsets.ISO_8859_1).replace("\u0000", "")
                            if (text.contains(DOCTYPE, ignoreCase = true)) throw DocumentDamagedException()
                            carried = text.takeLast(DOCTYPE.length - 1)
                        }
                    }
                }
            }
            if (parts == 0) throw DocumentDamagedException()
        }

        private fun open(resolver: ContentResolver, uri: Uri) = resolver.openInputStream(uri) ?: throw IOException(uri.toString())

        private const val MAX_PARTS = 10_000
        private const val MAX_UNPACKED_BYTES = 256L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private const val DOCTYPE = "<!DOCTYPE"
    }
}

/** The element's name without its namespace prefix ("x:row" → "row"); OOXML files use different prefixes. */
internal val XmlPullParser.localName: String get() = name.substringAfter(':')

/** An attribute by its name without prefix. */
internal fun XmlPullParser.attribute(localName: String): String? {
    for (index in 0 until attributeCount) {
        if (getAttributeName(index).substringAfter(':') == localName) return getAttributeValue(index)
    }
    return null
}

/** Goes through the document, calling [onStart] for every start tag and [onText] for text; [onEnd] for end tags. */
internal inline fun XmlPullParser.walk(onStart: () -> Unit, onEnd: () -> Unit = {}, onText: () -> Unit = {}) {
    var event = eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> onStart()
            XmlPullParser.END_TAG -> onEnd()
            XmlPullParser.TEXT -> onText()
        }
        event = next()
    }
}
