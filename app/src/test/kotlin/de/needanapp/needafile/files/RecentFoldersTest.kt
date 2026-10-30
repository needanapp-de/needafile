package de.needanapp.needafile.files

import org.junit.Assert.assertEquals
import org.junit.Test

/** Recent folders keep the deepest ones visited, newest first, without duplicates and without the way up. */
class RecentFoldersTest {
    private val dcim = "/storage/emulated/0/DCIM"
    private val camera = "$dcim/Camera"
    private val screenshots = "$dcim/Screenshots"

    private fun paths(recent: List<RecentFolder>) = recent.map { it.path }

    @Test
    fun deeperFolderReplacesTheOneItLiesIn() {
        val recent = recordRecentFolder(recordRecentFolder(emptyList(), dcim, 1), camera, 2)
        assertEquals(listOf(camera), paths(recent))
    }

    @Test
    fun goingUpChangesNothing() {
        val recent = recordRecentFolder(emptyList(), camera, 1)
        assertEquals(recent, recordRecentFolder(recent, dcim, 2))
    }

    @Test
    fun siblingsStaySideBySideNewestFirst() {
        val recent = recordRecentFolder(recordRecentFolder(emptyList(), camera, 1), screenshots, 2)
        assertEquals(listOf(screenshots, camera), paths(recent))
    }

    @Test
    fun openingAgainMovesToTheFront() {
        val recent = recordRecentFolder(recordRecentFolder(recordRecentFolder(emptyList(), camera, 1), screenshots, 2), camera, 3)
        assertEquals(listOf(camera, screenshots), paths(recent))
        assertEquals(3L, recent.first().time)
    }

    @Test
    fun similarNamesAreNotNested() {
        val recent = recordRecentFolder(recordRecentFolder(emptyList(), "$dcim/Cam", 1), camera, 2)
        assertEquals(listOf(camera, "$dcim/Cam"), paths(recent))
    }

    @Test
    fun keepsAtMostFive() {
        val recent = (1..7).fold(emptyList<RecentFolder>()) { list, i -> recordRecentFolder(list, "/storage/emulated/0/f$i", i.toLong()) }
        assertEquals((7 downTo 3).map { "/storage/emulated/0/f$it" }, paths(recent))
    }
}
