package de.needanapp.needafile.root

import de.needanapp.needafile.core.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Paths reach the shell literally, and the listing script's lines turn into the right entries. */
class RootShellTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun quotingWrapsEverythingInSingleQuotes() {
        assertEquals("'/data/my folder'", RootShell.shellQuote("/data/my folder"))
        assertEquals("'price \$HOME \$(id)'", RootShell.shellQuote("price \$HOME \$(id)"))
        assertEquals("'`reboot`'", RootShell.shellQuote("`reboot`"))
        assertEquals("'first\nsecond'", RootShell.shellQuote("first\nsecond"))
        assertEquals("''", RootShell.shellQuote(""))
    }

    @Test
    fun singleQuotesAreClosedEscapedAndReopened() {
        assertEquals("'it'\\''s'", RootShell.shellQuote("it's"))
        assertEquals("''\\'''\\'''", RootShell.shellQuote("''"))
    }

    @Test
    fun quotedTextComesBackUnchangedFromARealShell() {
        assumeTrue(File(SHELL).canExecute())
        val names = listOf("it's", "a b\tc", "\$HOME", "`id`", "\$(id)", "line\nbreak", "back\\slash", "\"double\"", "*", "-n")
        for (name in names) {
            val result = RootShell.runShell(listOf(SHELL), "printf '%s' ${RootShell.shellQuote(name)}", TIMEOUT_SECONDS)
            assertEquals(name, result.output)
        }
    }

    @Test
    fun linesBecomeEntriesWithTheirNamesIntact() {
        val output = "d/0/1700000000/4/my folder\nf/2048/1700000001/0/tab\tand  spaces .txt\n"
        val entries = RootShell.parseListing(output, "/data/local", withHidden = false)
        assertEquals(
            listOf(
                FileEntry("/data/local/my folder", "my folder", true, 4, 0, 1_700_000_000_000),
                FileEntry("/data/local/tab\tand  spaces .txt", "tab\tand  spaces .txt", false, 0, 2048, 1_700_000_001_000),
            ),
            entries,
        )
    }

    @Test
    fun secondsBecomeMilliseconds() {
        val entry = RootShell.parseListing("f/1/1234567890/0/a", "/data", withHidden = false).single()
        assertEquals(1_234_567_890_000L, entry.modified)
    }

    @Test
    fun hiddenEntriesOnlyWhenWanted() {
        val output = "f/1/1/0/.nomedia\nd/0/1/0/.config\nf/1/1/0/visible\n"
        assertEquals(listOf("visible"), RootShell.parseListing(output, "/data", withHidden = false).map { it.name })
        assertEquals(
            listOf(".nomedia", ".config", "visible"),
            RootShell.parseListing(output, "/data", withHidden = true).map { it.name },
        )
    }

    @Test
    fun pathsInTheRootFolderHaveASingleSlash() {
        val output = "d/0/1/12/system\nf/30/1/0/adb_keys\n"
        assertEquals(listOf("/system", "/adb_keys"), RootShell.parseListing(output, "/", withHidden = false).map { it.path })
        assertEquals("/data/app", RootShell.parseListing("d/0/1/0/app", "/data/", withHidden = false).single().path)
    }

    @Test
    fun linesThatCannotBeTakenApartAreSkipped() {
        val output = listOf(
            "f/1/1/0/first",
            "rest of a name with a line break",
            "x/1/1/0/unknown type",
            "f/size/1/0/no number",
            "d/0/1/0/",
            "d/0/1/0/..",
            "f/1/1/0/a/b",
            "",
            "f/1/1/0/last",
        ).joinToString("\n")
        assertEquals(listOf("first", "last"), RootShell.parseListing(output, "/data", withHidden = true).map { it.name })
    }

    @Test
    fun listingScriptReadsARealFolder() {
        assumeTrue(File(SHELL).canExecute())
        assumeTrue(RootShell.runShell(listOf(SHELL), "stat -L -c '%s/%Y/%n' -- /", TIMEOUT_SECONDS).completed)
        val folder = temporaryFolder.newFolder("list me")
        File(folder, "sub dir").mkdir()
        File(folder, "sub dir/one").writeText("1")
        File(folder, "sub dir/.two").writeText("2")
        File(folder, "empty").mkdir()
        File(folder, "it's \$x `y`\tz").writeText("12345")
        File(folder, "line\nbreak").writeText("")
        File(folder, ".hidden").writeText("")
        Files.createSymbolicLink(File(folder, "link").toPath(), File(folder, "sub dir").toPath())
        Files.createSymbolicLink(File(folder, "broken").toPath(), File(folder, "nowhere").toPath())
        File(folder, "it's \$x `y`\tz").setLastModified(1_000_000_000_000)

        val all = list(folder.path, withHidden = true, withSizes = true).associateBy { it.name }
        assertEquals(setOf("sub dir", "empty", "it's \$x `y`\tz", ".hidden", "link", "broken"), all.keys)
        assertEquals(FileEntry("${folder.path}/sub dir", "sub dir", true, 2, 0, all.getValue("sub dir").modified), all["sub dir"])
        assertTrue(all.getValue("link").isDirectory)
        assertFalse(all.getValue("broken").isDirectory)
        assertEquals(0, all.getValue("empty").children)
        val file = all.getValue("it's \$x `y`\tz")
        assertEquals(5L, file.size)
        assertEquals(1_000_000_000_000L, file.modified)

        val visible = list(folder.path, withHidden = false, withSizes = false).associateBy { it.name }
        assertNull(visible[".hidden"])
        assertEquals(1, visible.getValue("sub dir").children)
        assertEquals(0L, visible.getValue("it's \$x `y`\tz").size)

        assertTrue(list(File(folder, "empty").path, withHidden = true, withSizes = true).isEmpty())
        val missing = RootShell.runShell(listOf(SHELL), RootShell.listScript("${folder.path}/missing", true, true), TIMEOUT_SECONDS)
        assertFalse(missing.completed)
    }

    @Test
    fun longestMountPointDecides() {
        val mounts = """
            /dev/block/dm-0 / ext4 ro,seclabel,relatime 0 0
            tmpfs /mnt tmpfs rw,nosuid 0 0
            /dev/block/dm-1 /vendor ext4 ro,relatime 0 0
            /dev/block/dm-9 /data f2fs rw,lazytime 0 0
            /dev/block/dm-2 /vendor/firmware\040files vfat ro 0 0
        """.trimIndent()
        assertEquals("/", RootShell.readOnlyMountPoint("/system/app/Foo", mounts))
        assertEquals("/vendor", RootShell.readOnlyMountPoint("/vendor/etc/hosts", mounts))
        assertEquals("/vendor/firmware files", RootShell.readOnlyMountPoint("/vendor/firmware files/a.bin", mounts))
        assertNull(RootShell.readOnlyMountPoint("/data/local/tmp", mounts))
        assertNull(RootShell.readOnlyMountPoint("/vendorx/etc", mounts.replace("/ ext4 ro", "/ ext4 rw")))
    }

    @Test
    fun sharedStorageIsNeverRemounted() {
        val mounts = "/dev/block/dm-0 / ext4 ro 0 0\n"
        assertNull(RootShell.readOnlyMountPoint("/storage/emulated/0/a", mounts))
        assertNull(RootShell.readOnlyMountPoint("/sdcard", mounts))
        assertNull(RootShell.readOnlyMountPoint("/data/media/0/a", mounts))
    }

    private fun list(folder: String, withHidden: Boolean, withSizes: Boolean): List<FileEntry> {
        val result = RootShell.runShell(listOf(SHELL), RootShell.listScript(folder, withHidden, withSizes), TIMEOUT_SECONDS)
        assertTrue(result.errors, result.completed)
        return RootShell.parseListing(result.output, folder, withHidden)
    }

    private companion object {
        const val SHELL = "/bin/sh"
        const val TIMEOUT_SECONDS = 30L
    }
}
