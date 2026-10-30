package de.needanapp.needafile.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random

/** Reading vault files piece by piece, as other apps do through the vault file provider, gives the same bytes. */
class VaultFileReaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    // around the chunk size (32 KiB) and a few chunks beyond the read-ahead
    private val sizes = listOf(0, 1, 32_767, 32_768, 32_769, 65_536, 1_000_000)

    @Test
    fun readsEveryRangeLikeTheWholeFile() {
        val vault = newVault()
        val random = Random(7)
        sizes.forEach { size ->
            val content = upload(vault, size)
            val reader = vault.openReader("/datei-$size.bin", size.toLong())
            assertEquals(size.toLong(), reader.size)
            // whole file in reads of a player's size, then jumps back and forth
            assertArrayEquals(content, readAll(reader, 131_072))
            repeat(200) {
                val position = if (size == 0) 0 else random.nextInt(size)
                val length = random.nextInt(1, 70_000)
                val buffer = ByteArray(length)
                val count = reader.read(position.toLong(), length, buffer)
                val expected = content.copyOfRange(position, minOf(position + length, size))
                assertEquals(expected.size, count)
                assertArrayEquals(expected, buffer.copyOf(count))
            }
            assertEquals(0, reader.read(size.toLong(), 10, ByteArray(10)))
        }
        vault.close()
    }

    @Test
    fun serverStoppingEarlyStillGivesTheBytes() {
        val folder = temp.newFolder("tresor-ftp")
        val (opened, _) = VaultAccess.create(LocalVaultStorage(folder), PASSWORD)
        // like FTP: stopping a transfer halfway makes the server answer with an error afterwards
        val storage = object : VaultStorage by LocalVaultStorage(folder) {
            override fun readFrom(path: String, offset: Long, block: (InputStream) -> Unit) {
                LocalVaultStorage(folder).readFrom(path, offset) { input ->
                    block(input)
                    if (input.read() >= 0) throw IOException("426 Transfer aborted")
                }
            }
        }
        val vault = VaultFileSystem(storage, opened)
        val content = upload(vault, 200_000)
        assertArrayEquals(content, readAll(vault.openReader("/datei-200000.bin", 200_000), 50_000))
        vault.close()
    }

    private fun newVault(): VaultFileSystem {
        val folder = temp.newFolder()
        val (opened, _) = VaultAccess.create(LocalVaultStorage(folder), PASSWORD)
        return VaultFileSystem(LocalVaultStorage(folder), opened)
    }

    private fun upload(vault: VaultFileSystem, size: Int): ByteArray {
        val content = Random(size).nextBytes(size)
        val file = File(temp.newFolder(), "datei-$size.bin").apply { writeBytes(content) }
        vault.upload(listOf(file), "/", { false }, {})
        return content
    }

    private fun readAll(reader: VaultFileReader, step: Int): ByteArray {
        val result = ByteArray(reader.size.toInt())
        val buffer = ByteArray(step)
        var at = 0L
        while (true) {
            val count = reader.read(at, step, buffer)
            if (count == 0) break
            System.arraycopy(buffer, 0, result, at.toInt(), count)
            at += count
        }
        return result
    }

    private companion object {
        const val PASSWORD = "lese-test-passwort"
    }
}
