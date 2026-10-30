package de.needanapp.needafile.viewer.document

import de.needanapp.needafile.viewer.document.office.OfficePackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentReadingTest {
    @Test
    fun textByByteOrderMarkUtf8OrWindows1252() {
        assertEquals("Grüße", decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Grüße".toByteArray(Charsets.UTF_8)))
        assertEquals("Grüße", decodeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Grüße".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Grüße", decodeText("Grüße".toByteArray(Charsets.UTF_8)))
        assertEquals("Grüße €", decodeText("Grüße €".toByteArray(charset("windows-1252"))))
        assertEquals("", decodeText(ByteArray(0)))
    }

    @Test
    fun separatorIsGuessedFromTheFirstLines() {
        assertEquals(';', guessSeparator("Name;Preis;Anzahl\nApfel;1,20;3\nBirne;0,99;5", "liste.csv"))
        assertEquals(',', guessSeparator("a,b,c\n1,2,3", "x.csv"))
        assertEquals('\t', guessSeparator("a\tb\n1\t2", "x.csv"))
        assertEquals('\t', guessSeparator("a,b\n1,2", "x.tsv"))
        // commas inside quotes don't count
        assertEquals(';', guessSeparator("\"Müller, Hans\";42\n\"Meier, Eva\";17", "x.csv"))
        assertEquals(',', guessSeparator("nur eine Spalte\nzweite Zeile", "x.csv"))
    }

    @Test
    fun csvBecomesOneTable() {
        val sheet = readCsv("Name;Ort\n\"Müller; Hans\";Köln\nEva;\"Bad \"\"Tölz\"\"\";Extra", "x.csv")
        assertEquals(listOf(listOf("Name", "Ort"), listOf("Müller; Hans", "Köln"), listOf("Eva", "Bad \"Tölz\"", "Extra")), sheet.rows)
        assertEquals(3, sheet.columnCount)
        assertEquals(0, readCsv("", "leer.csv").columnCount)
    }

    @Test
    fun cleanerKeepsTheDocumentAndDropsActiveParts() {
        val html = HtmlCleaner.clean(
            """<h1 onclick="x()">Titel</h1><script>alert(1)</script><p style="color:red">Text <a href="https://example.org">Link</a>
               <a href="javascript:alert(1)">böse</a> <a href="#fn1">1</a></p><img src="data:image/png;base64,AAAA">
               <iframe src="https://example.org"></iframe><form><input type="text"></form><table><tr><td colspan="2">Z</td></tr></table>""",
            isFragment = true,
        )
        assertTrue(html.contains("<h1>Titel</h1>"))
        assertFalse(html.contains("script") || html.contains("onclick") || html.contains("style=") || html.contains("iframe"))
        assertTrue(html.contains("href=\"https://example.org\""))
        assertFalse(html.contains("javascript"))
        assertTrue(html.contains("href=\"#fn1\""))
        assertTrue(html.contains("src=\"data:image/png;base64,AAAA\""))
        assertFalse(html.contains("<input"))
        assertTrue(html.contains("colspan=\"2\""))
    }

    @Test
    fun onlyWebAndMailLinksLeaveTheViewer() {
        assertTrue(HtmlCleaner.isExternalLink("https://needanapp.de"))
        assertTrue(HtmlCleaner.isExternalLink("HTTP://example.org"))
        assertTrue(HtmlCleaner.isExternalLink("mailto:a@b.de"))
        assertFalse(HtmlCleaner.isExternalLink("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(HtmlCleaner.isExternalLink("file:///sdcard/x"))
        assertFalse(HtmlCleaner.isExternalLink("content://x/y"))
        assertFalse(HtmlCleaner.isExternalLink("javascript:alert(1)"))
    }

    @Test
    fun markdownWithTheGithubAdditions() {
        val html = markdownToHtml(
            """
            # Titel

            | A | B |
            |---|---|
            | 1 | ~~2~~ |

            - [x] erledigt
            - [ ] offen

            Siehe https://example.org und <b onclick="x()">roh</b>
            """.trimIndent(),
        )
        assertTrue(html.contains("<h1>Titel</h1>"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<del>2</del>"))
        assertTrue(html.contains("type=\"checkbox\"") && html.contains("disabled"))
        assertTrue(html.contains("href=\"https://example.org\""))
        // HTML inside Markdown shows as text
        assertFalse(html.contains("<b "))
        assertTrue(html.contains("&lt;b"))
    }

    @Test
    fun officeArchivesWithinLimitsPass() {
        OfficePackage.checkLimits(ByteArrayInputStream(zip("word/document.xml" to "<?xml version=\"1.0\"?><w:document/>")))
    }

    @Test(expected = DocumentDamagedException::class)
    fun officeXmlWithDoctypeIsRefused() {
        OfficePackage.checkLimits(ByteArrayInputStream(zip("word/document.xml" to "<?xml version=\"1.0\"?><!doctype x [<!ENTITY a \"b\">]><x/>")))
    }

    @Test(expected = DocumentDamagedException::class)
    fun somethingThatIsNoArchiveIsRefused() {
        OfficePackage.checkLimits(ByteArrayInputStream("kein zip".toByteArray()))
    }

    private fun zip(vararg parts: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            parts.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
