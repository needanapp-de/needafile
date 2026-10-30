package de.needanapp.needafile.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class EntryOrderTest {
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun tearDown() = Locale.setDefault(defaultLocale)

    private fun file(name: String, size: Long = 0, modified: Long = 0) = FileEntry("/x/$name", size = size, modified = modified)
    private fun folder(name: String) = FileEntry("/x/$name", isDirectory = true)
    private fun List<FileEntry>.sortedNames(sorting: Int) = sortedWith(entryOrder(sorting)).map { it.name }

    @Test
    fun foldersComeFirstInEveryDirection() {
        val entries = listOf(file("a.txt", size = 9), folder("z"), file("b.txt", size = 1), folder("c"))
        assertEquals(listOf("c", "z", "a.txt", "b.txt"), entries.sortedNames(Sorting.DEFAULT))
        assertEquals(listOf("z", "c", "b.txt", "a.txt"), entries.sortedNames(Sorting.DEFAULT or Sorting.DESCENDING))
        assertEquals(setOf("c", "z"), entries.sortedNames(Sorting.BY_SIZE or Sorting.DESCENDING).take(2).toSet())
    }

    @Test
    fun sizeAndDateSortTheFiles() {
        val entries = listOf(file("big", size = 900), file("small", size = 5), file("old", size = 50, modified = 1), file("new", size = 60, modified = 9))
        assertEquals(listOf("small", "old", "new", "big"), entries.sortedNames(Sorting.BY_SIZE))
        assertEquals(listOf("big", "new", "old", "small"), entries.sortedNames(Sorting.BY_SIZE or Sorting.DESCENDING))
        assertEquals("new", entries.sortedNames(Sorting.BY_DATE or Sorting.DESCENDING).first())
    }

    @Test
    fun extensionGroupsFileTypes() {
        val entries = listOf(file("b.txt"), file("a.zip"), file("c.jpg"), file("ohne"))
        assertEquals(listOf("ohne", "c.jpg", "b.txt", "a.zip"), entries.sortedNames(Sorting.BY_EXTENSION))
    }

    @Test
    fun numbersCompareByValueOnlyWhenAsked() {
        val entries = listOf(file("Datei 10"), file("Datei 2"), file("Datei 1"))
        assertEquals(listOf("Datei 1", "Datei 2", "Datei 10"), entries.sortedNames(Sorting.DEFAULT))
        assertEquals(listOf("Datei 1", "Datei 10", "Datei 2"), entries.sortedNames(Sorting.BY_NAME))
    }

    @Test
    fun namesIgnoreCaseAndAccents() {
        val entries = listOf(file("Zebra"), file("äpfel"), file("Birne"))
        assertEquals(listOf("äpfel", "Birne", "Zebra"), entries.sortedNames(Sorting.DEFAULT))
    }

    @Test
    fun naturalOrderHandlesLeadingZerosAndLongNumbers() {
        assertTrue(NaturalOrder.compare("bild007", "bild8") < 0)
        assertTrue(NaturalOrder.compare("x99999999999999999999", "x100000000000000000000") < 0)
        assertEquals(0, NaturalOrder.compare("a1b", "a1b"))
        assertTrue(NaturalOrder.compare("a", "a1") < 0)
    }

    @Test
    fun sizesUseStepsOfThousand() {
        assertEquals("0 B", 0L.formatSize())
        assertEquals("999 B", 999L.formatSize())
        assertEquals("1 kB", 1000L.formatSize())
        assertEquals("1,5 MB", 1_500_000L.formatSize())
        assertEquals("2 GB", 2_000_000_000L.formatSize())
    }
}
