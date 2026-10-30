package de.needanapp.needafile.vault

import org.cryptomator.cryptolib.api.AuthenticationFailedException
import org.cryptomator.cryptolib.api.Cryptor
import org.cryptomator.cryptolib.api.FileHeader
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Reads a file of an unlocked vault at any position without decrypting it as a whole: only the chunks (32 KiB each) a
 * read touches are fetched and decrypted, a few more ahead for players and viewers reading on, and the last ones are
 * kept for small reads in a row. [size] is the cleartext size. For one thread at a time.
 */
class VaultFileReader internal constructor(
    private val storage: VaultStorage,
    private val contentPath: String,
    private val cryptor: Cryptor,
    val size: Long,
) {
    private val contentCryptor = cryptor.fileContentCryptor()
    private val headerSize = cryptor.fileHeaderCryptor().headerSize()
    private val clearChunkSize = contentCryptor.cleartextChunkSize()
    private val cipherChunkSize = contentCryptor.ciphertextChunkSize()
    private val chunkCount = (size + clearChunkSize - 1) / clearChunkSize
    private val cipherEnd = headerSize + contentCryptor.ciphertextSize(size)

    private val header: FileHeader by lazy {
        try {
            cryptor.fileHeaderCryptor().decryptHeader(ByteBuffer.wrap(fetch(0, headerSize)))
        } catch (e: AuthenticationFailedException) {
            throw IOException("Damaged header", e)
        }
    }

    private val chunks = object : LinkedHashMap<Long, ByteArray>(KEPT_CHUNKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = this.size > KEPT_CHUNKS
    }

    /** Copies up to [length] bytes from [position] into [target]; returns how many, 0 at the end of the file. */
    fun read(position: Long, length: Int, target: ByteArray): Int {
        if (position >= size || length <= 0) return 0
        val end = minOf(position + length, size)
        var at = position
        var copied = 0
        while (at < end) {
            val index = at / clearChunkSize
            val chunk = chunk(index)
            val inChunk = (at - index * clearChunkSize).toInt()
            val count = minOf(chunk.size - inChunk, (end - at).toInt())
            if (count <= 0) throw IOException("Chunk $index is too short")
            System.arraycopy(chunk, inChunk, target, copied, count)
            at += count
            copied += count
        }
        return copied
    }

    fun close() = chunks.clear()

    private fun chunk(index: Long): ByteArray = chunks[index] ?: run {
        decryptFrom(index)
        chunks[index] ?: throw IOException("Chunk $index is missing")
    }

    /** Fetches the chunks from [first] on, as many as one request brings, and keeps them decrypted. */
    private fun decryptFrom(first: Long) {
        val count = minOf(READ_AHEAD_CHUNKS.toLong(), chunkCount - first).toInt()
        val start = headerSize + first * cipherChunkSize
        val bytes = fetch(start, minOf(count.toLong() * cipherChunkSize, cipherEnd - start).toInt())
        var offset = 0
        var index = first
        while (offset < bytes.size) {
            val length = minOf(cipherChunkSize, bytes.size - offset)
            val clear = try {
                contentCryptor.decryptChunk(ByteBuffer.wrap(bytes.copyOfRange(offset, offset + length)), index, header, true)
            } catch (e: AuthenticationFailedException) {
                throw IOException("Damaged chunk $index", e)
            }
            chunks[index] = ByteArray(clear.remaining()).also { clear.get(it) }
            offset += length
            index++
        }
    }

    /** [length] encrypted bytes from [offset] on. */
    private fun fetch(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var filled = 0
        try {
            storage.readFrom(contentPath, offset) { input ->
                // a request tried again on a new connection starts over
                filled = 0
                while (filled < length) {
                    val count = input.read(bytes, filled, length - filled)
                    if (count < 0) break
                    filled += count
                }
            }
        } catch (e: Exception) {
            // some servers (FTP) complain when a transfer stops early; what arrived is all that was needed
            if (filled < length) throw e
        }
        if (filled < length) throw IOException("File ends early")
        return bytes
    }

    private companion object {
        /** 512 KiB per request, enough for a player to read on for a while. */
        const val READ_AHEAD_CHUNKS = 16
        const val KEPT_CHUNKS = 64
    }
}
