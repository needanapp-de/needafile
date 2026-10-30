package de.needanapp.needafile.vault

import de.needanapp.needafile.R
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One file or folder among the encrypted files of a vault. */
data class StoredEntry(val name: String, val isDirectory: Boolean, val size: Long, val modified: Long)

/**
 * Where the encrypted files of a vault lie: its folder on the phone or on a server. Paths are relative to that folder
 * and use "/", e.g. "d/AB/CDEF…/name.c9r"; "" is the folder itself. Every call blocks.
 */
interface VaultStorage : Closeable {
    /** The content of the folder [path], null when there is no such folder. */
    fun list(path: String): List<StoredEntry>?

    /** The file or folder [path], null when there is none. */
    fun entry(path: String): StoredEntry?

    /** Hands [block] the content of the file [path]; [block] reads it to the end. */
    fun read(path: String, block: (InputStream) -> Unit)

    /** Hands [block] the content of the file [path] from [offset] on; [block] may stop early (some servers then complain). */
    fun readFrom(path: String, offset: Long, block: (InputStream) -> Unit)

    /** Creates or replaces the file [path] with the [length] bytes [block] writes; it gets the time [modified]. */
    fun write(path: String, length: Long, modified: Long, block: (OutputStream) -> Unit)

    /** Creates the folder [path] with the folders above it; existing ones are fine. */
    fun makeFolder(path: String)

    fun deleteFile(path: String)

    /** Deletes the folder [path] with all its content. */
    fun deleteTree(path: String)

    /** Moves [path] to [newPath], which must not exist yet. */
    fun move(path: String, newPath: String)

    override fun close() = Unit
}

/** The small file [path] (a key file, a folder id…) as bytes; larger ones than [maxSize] count as damaged. */
fun VaultStorage.readBytes(path: String, maxSize: Int = SMALL_FILE_LIMIT): ByteArray {
    val bytes = ByteArrayOutputStream()
    var tooBig = false
    read(path) { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            // read to the end anyway, some servers (FTP) mind a transfer stopped halfway
            if (bytes.size() + count > maxSize) tooBig = true else bytes.write(buffer, 0, count)
        }
    }
    if (tooBig) throw VaultException(R.string.vault_damaged)
    return bytes.toByteArray()
}

fun VaultStorage.writeBytes(path: String, bytes: ByteArray) = write(path, bytes.size.toLong(), System.currentTimeMillis()) { it.write(bytes) }

/** The vault's folder on the phone (internal storage, SD card, USB drive). */
class LocalVaultStorage(private val root: File) : VaultStorage {
    private fun file(path: String) = if (path.isEmpty()) root else File(root, path)

    override fun list(path: String): List<StoredEntry>? = file(path).listFiles()?.map { it.toEntry() }

    override fun entry(path: String): StoredEntry? = file(path).takeIf { it.exists() }?.toEntry()

    private fun File.toEntry() = StoredEntry(name, isDirectory, if (isDirectory) 0 else length(), lastModified())

    override fun read(path: String, block: (InputStream) -> Unit) {
        FileInputStream(file(path)).use(block)
    }

    override fun readFrom(path: String, offset: Long, block: (InputStream) -> Unit) {
        FileInputStream(file(path)).use { input ->
            input.channel.position(offset)
            block(input)
        }
    }

    override fun write(path: String, length: Long, modified: Long, block: (OutputStream) -> Unit) {
        val target = file(path)
        FileOutputStream(target).use(block)
        target.setLastModified(modified)
    }

    override fun makeFolder(path: String) {
        val folder = file(path)
        if (!folder.mkdirs() && !folder.isDirectory) throw IOException("Can't create ${folder.path}")
    }

    override fun deleteFile(path: String) {
        val target = file(path)
        if (!target.delete() && target.exists()) throw IOException("Can't delete ${target.path}")
    }

    override fun deleteTree(path: String) {
        val target = file(path)
        if (!target.deleteRecursively() && target.exists()) throw IOException("Can't delete ${target.path}")
    }

    override fun move(path: String, newPath: String) {
        val source = file(path)
        val target = file(newPath)
        if (target.exists() || !source.renameTo(target)) throw IOException("Can't move ${source.path} to ${target.path}")
    }
}

/** Passes everything on but leaves closing to the one who opened the stream. */
internal class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

    override fun close() = flush()
}

/** Key files, folder ids and long names are a few hundred bytes. */
private const val SMALL_FILE_LIMIT = 64 * 1024
internal const val BUFFER_SIZE = 64 * 1024
