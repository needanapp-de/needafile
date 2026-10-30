package de.needanapp.needafile.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picked types narrow the search to their extensions, whatever the case; folders never fit a kind of file. */
class SearchFilterTest {
    private val jpg = FileTypeChoice("jpg", setOf("jpg", "jpeg"))
    private val png = FileTypeChoice("png")

    @Test
    fun typesMatchTheirExtensions() {
        val filter = SearchFilter(FileCategory.IMAGES, setOf(jpg, png))
        assertTrue(filter.matches("Urlaub.JPEG", isDirectory = false))
        assertTrue(filter.matches("bild.jpg", isDirectory = false))
        assertTrue(filter.matches("screen.png", isDirectory = false))
        assertFalse(filter.matches("film.mp4", isDirectory = false))
        assertFalse(filter.matches("jpg", isDirectory = false))
    }

    @Test
    fun foldersNeverMatch() {
        assertFalse(SearchFilter(FileCategory.IMAGES, setOf(jpg)).matches("fotos.jpg", isDirectory = true))
        assertFalse(SearchFilter(FileCategory.ARCHIVES).matches("backup.zip", isDirectory = true))
    }

    @Test
    fun categoryAloneFindsItsKind() {
        val archives = SearchFilter(FileCategory.ARCHIVES)
        assertTrue(archives.matches("backup.7z", isDirectory = false))
        assertTrue(archives.matches("daten.tar.gz", isDirectory = false))
        assertTrue(SearchFilter(FileCategory.APPS).matches("app.xapk", isDirectory = false))
    }
}
