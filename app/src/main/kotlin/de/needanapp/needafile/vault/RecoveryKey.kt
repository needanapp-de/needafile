package de.needanapp.needafile.vault

import com.google.common.hash.Hashing

/**
 * The recovery key of a vault as Cryptomator writes it: the 64 key bytes plus two check bytes (the start of their
 * CRC32), 66 bytes in all, as 44 words of a list of 4096, each word standing for 12 bits. With it a forgotten password
 * can be replaced, also in Cryptomator itself. The word list is Cryptomator's own (GPL-3.0).
 */
object RecoveryKey {
    private const val KEY_SIZE = 64
    private const val CHECK_SIZE = 2
    private const val WORD_COUNT = 4096

    private val words: List<String> by lazy {
        val stream = RecoveryKey::class.java.getResourceAsStream("recovery_words.txt") ?: error("word list missing")
        stream.bufferedReader(Charsets.US_ASCII).useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() }.take(WORD_COUNT).toList() }
            .also { check(it.size == WORD_COUNT) }
    }
    private val indices: Map<String, Int> by lazy { words.withIndex().associate { (index, word) -> word to index } }

    /** The words for the vault key [rawKey]. */
    fun encode(rawKey: ByteArray): String {
        require(rawKey.size == KEY_SIZE)
        val padded = rawKey.copyOf(KEY_SIZE + CHECK_SIZE)
        try {
            Hashing.crc32().hashBytes(rawKey).asBytes().copyInto(padded, KEY_SIZE, 0, CHECK_SIZE)
            return (padded.indices step 3).flatMap { i ->
                val b1 = padded[i].toInt() and 0xFF
                val b2 = padded[i + 1].toInt() and 0xFF
                val b3 = padded[i + 2].toInt() and 0xFF
                listOf(words[(b1 shl 4) or (b2 shr 4)], words[((b2 and 0x0F) shl 8) or b3])
            }.joinToString(" ")
        } finally {
            padded.fill(0)
        }
    }

    /** The vault key written as [text], null when it isn't a complete recovery key or a word is mistyped. */
    fun decode(text: String): ByteArray? {
        val given = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (given.size != (KEY_SIZE + CHECK_SIZE) / 3 * 2) return null
        val padded = ByteArray(KEY_SIZE + CHECK_SIZE)
        try {
            for (pair in given.indices step 2) {
                val first = indices[given[pair]] ?: return null
                val second = indices[given[pair + 1]] ?: return null
                val offset = pair / 2 * 3
                padded[offset] = (first shr 4).toByte()
                padded[offset + 1] = (((first and 0x0F) shl 4) or (second shr 8)).toByte()
                padded[offset + 2] = second.toByte()
            }
            val rawKey = padded.copyOf(KEY_SIZE)
            val check = Hashing.crc32().hashBytes(rawKey).asBytes().copyOf(CHECK_SIZE)
            if (!check.contentEquals(padded.copyOfRange(KEY_SIZE, KEY_SIZE + CHECK_SIZE))) {
                rawKey.fill(0)
                return null
            }
            return rawKey
        } finally {
            padded.fill(0)
        }
    }

    /** Whether [word] is one of the list, to point out mistyped words while typing. */
    fun isWord(word: String) = word.lowercase() in indices
}
