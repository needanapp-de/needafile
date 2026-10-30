package de.needanapp.needafile.backup

import de.needanapp.needafile.R
import de.needanapp.needafile.errors.LocalizedException
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts backups with a password: the key comes from Argon2id, the content (compressed) is sealed with AES-256-GCM.
 * The file starts with a header naming the format and the key derivation's parameters; the header is authenticated
 * along with the content, so neither can be changed unnoticed.
 *
 * Layout: "NFBK", format version, KDF id, memory (KiB, int), iterations (int), parallelism, salt length, salt,
 * nonce length, nonce, then the encrypted content with the GCM tag at its end.
 */
internal object BackupCrypto {
    fun encrypt(content: ByteArray, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val params = KdfParams(MEMORY_KIB, ITERATIONS, PARALLELISM, salt)
        val header = header(params, nonce)
        val key = deriveKey(password, params)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(compress(content))
        } finally {
            key.fill(0)
        }
    }

    /** Whether [start], the first bytes of a file, are those of a backup. */
    fun hasBackupHeader(start: ByteArray) = start.size >= MAGIC.size && start.copyOf(MAGIC.size).contentEquals(MAGIC)

    /** The content of [file]; throws a [BackupException] for files that aren't backups and for a wrong password. */
    fun decrypt(file: ByteArray, password: CharArray): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(file))
        val params: KdfParams
        val nonce: ByteArray
        try {
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            if (!magic.contentEquals(MAGIC)) throw BackupException(R.string.backup_error_not_a_backup)
            val version = input.readUnsignedByte()
            if (version != FORMAT_VERSION) throw BackupException(R.string.backup_error_newer_version)
            if (input.readUnsignedByte() != KDF_ARGON2ID) throw BackupException(R.string.backup_error_newer_version)
            val memory = input.readInt()
            val iterations = input.readInt()
            val parallelism = input.readUnsignedByte()
            val salt = ByteArray(input.readUnsignedByte()).also(input::readFully)
            nonce = ByteArray(input.readUnsignedByte()).also(input::readFully)
            // a manipulated header must not make the key derivation take all memory or forever
            val isSane = memory in MIN_MEMORY_KIB..MAX_MEMORY_KIB && iterations in 1..MAX_ITERATIONS && parallelism in 1..MAX_PARALLELISM &&
                salt.size >= MIN_SALT_SIZE && nonce.size == NONCE_SIZE
            if (!isSane) throw BackupException(R.string.backup_error_damaged)
            params = KdfParams(memory, iterations, parallelism, salt)
        } catch (e: EOFException) {
            throw BackupException(R.string.backup_error_not_a_backup)
        }

        val headerSize = file.size - input.available()
        val key = deriveKey(password, params)
        val content = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(file, 0, headerSize)
            cipher.doFinal(file, headerSize, file.size - headerSize)
        } catch (e: AEADBadTagException) {
            // GCM can't tell a wrong password from changed content; the password is by far the likelier reason
            throw BackupException(R.string.backup_error_wrong_password)
        } finally {
            key.fill(0)
        }
        return try {
            decompress(content)
        } catch (e: Exception) {
            throw BackupException(R.string.backup_error_damaged)
        }
    }

    private fun header(params: KdfParams, nonce: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write(MAGIC)
            writeByte(FORMAT_VERSION)
            writeByte(KDF_ARGON2ID)
            writeInt(params.memoryKib)
            writeInt(params.iterations)
            writeByte(params.parallelism)
            writeByte(params.salt.size)
            write(params.salt)
            writeByte(nonce.size)
            write(nonce)
            flush()
        }
        return bytes.toByteArray()
    }

    private fun deriveKey(password: CharArray, params: KdfParams): ByteArray {
        val argon2 = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(params.memoryKib)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .withSalt(params.salt)
            .build()
        val key = ByteArray(KEY_SIZE)
        Argon2BytesGenerator().apply { init(argon2) }.generateBytes(password, key)
        return key
    }

    private fun compress(content: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(content) }
        return bytes.toByteArray()
    }

    /** Stops at [MAX_CONTENT_SIZE], so a small file can't unpack into more than the app's memory. */
    private fun decompress(content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPInputStream(content.inputStream()).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > MAX_CONTENT_SIZE) throw BackupException(R.string.backup_error_damaged)
            }
        }
        return output.toByteArray()
    }

    private class KdfParams(val memoryKib: Int, val iterations: Int, val parallelism: Int, val salt: ByteArray)

    private val MAGIC = "NFBK".toByteArray(Charsets.US_ASCII)
    val MAGIC_SIZE = MAGIC.size
    private const val FORMAT_VERSION = 1
    private const val KDF_ARGON2ID = 1
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 32
    private const val TAG_BITS = 128
    private const val SALT_SIZE = 16
    private const val MIN_SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val MEMORY_KIB = 64 * 1024
    private const val ITERATIONS = 3
    private const val PARALLELISM = 1
    private const val MIN_MEMORY_KIB = 8 * 1024
    private const val MAX_MEMORY_KIB = 256 * 1024
    private const val MAX_ITERATIONS = 10
    private const val MAX_PARALLELISM = 4
    private const val BUFFER_SIZE = 64 * 1024
    const val MAX_CONTENT_SIZE = 64 * 1024 * 1024
}

/** A backup that can't be read; the message says why (not a backup, wrong password, damaged, too new). */
class BackupException(messageRes: Int) : LocalizedException(messageRes)
