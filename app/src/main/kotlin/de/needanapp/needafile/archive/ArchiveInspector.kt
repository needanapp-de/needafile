package de.needanapp.needafile.archive

import de.needanapp.needafile.R
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.PropID
import java.io.File
import java.io.FileInputStream

/** What an archive contains and how it is protected, read without a password. */
data class ArchiveInfo(
    val kind: ArchiveKind,
    val encryption: ArchiveEncryption,
    /** Null when the file list itself is encrypted. */
    val fileCount: Int?,
    val folderCount: Int?,
    val unpackedSize: Long?,
    /** The compression method as 7-Zip names it, e.g. "LZMA2:24" or "Deflate". */
    val method: String?,
    /** The format's version where it matters, e.g. 5 for RAR 5 or 4 for the older RAR format. */
    val formatVersion: Int? = null,
)

sealed interface ArchiveEncryption {
    /** Whether even the file list needs the password. */
    val hidesFileNames: Boolean
        get() = this is SevenZipAes && fileNamesEncrypted || this is RarAes && fileNamesEncrypted

    data object None : ArchiveEncryption

    /** The old PKWARE encryption of zip, easy to break. */
    data object ZipCrypto : ArchiveEncryption

    /** WinZip AES; [version] 2 (AE-2) leaves out the CRC, which would reveal something about the content. */
    data class ZipAes(val keyBits: Int, val version: Int) : ArchiveEncryption

    /** 7z always uses AES-256; the key comes from the password through 2^[cyclesPower] SHA-256 rounds. */
    data class SevenZipAes(val fileNamesEncrypted: Boolean, val cyclesPower: Int?) : ArchiveEncryption

    /** RAR 5 uses AES-256, RAR 3 and 4 AES-128; [fileNamesEncrypted] when the whole archive header is encrypted. */
    data class RarAes(val keyBits: Int, val fileNamesEncrypted: Boolean) : ArchiveEncryption
}

object ArchiveInspector {
    private const val SEVEN_ZIP_AES_METHOD = "7zAES"
    private const val JOINED_PREFIX = "joined"
    private const val RAR_AES_METHOD = "AES:"
    private val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

    fun inspect(path: String): ArchiveInfo {
        val selected = File(path)
        val kind = ArchiveKind.of(selected.name) ?: throw ArchiveException(R.string.archive_unsupported, selected.name)
        val archive = ArchiveVolumes.firstVolume(selected)
        return when {
            kind == ArchiveKind.ZIP -> inspectZip(archive.path)
            // the content of compressed tars and single files is only known after decompressing
            kind.isTarCompressed || kind.isSingleStream -> ArchiveInfo(kind, ArchiveEncryption.None, null, null, null, null)
            else -> inspectWithSevenZip(archive, kind)
        }
    }

    private fun inspectZip(path: String): ArchiveInfo {
        val headers = if (isSplitZip(File(path))) {
            // zip4j can't read every split zip, the joined one it can
            val joined = File.createTempFile(JOINED_PREFIX, ".zip")
            try {
                SplitZipJoiner.join(File(path), joined)
                ZipFile(joined).use { it.fileHeaders }
            } finally {
                joined.delete()
            }
        } else {
            ZipFile(path).use { it.fileHeaders }
        }
        val encrypted = headers.filter { it.isEncrypted }
        val encryption = when {
            encrypted.isEmpty() -> ArchiveEncryption.None
            encrypted.any { it.encryptionMethod == EncryptionMethod.AES } -> {
                val aes = encrypted.first { it.encryptionMethod == EncryptionMethod.AES }.aesExtraDataRecord
                val bits = when (aes?.aesKeyStrength) {
                    AesKeyStrength.KEY_STRENGTH_128 -> 128
                    AesKeyStrength.KEY_STRENGTH_192 -> 192
                    else -> 256
                }
                ArchiveEncryption.ZipAes(bits, aes?.aesVersion?.versionNumber ?: 2)
            }
            else -> ArchiveEncryption.ZipCrypto
        }
        val folders = headers.count { it.isDirectory }
        return ArchiveInfo(
            kind = ArchiveKind.ZIP,
            encryption = encryption,
            fileCount = headers.size - folders,
            folderCount = folders,
            unpackedSize = headers.sumOf { it.uncompressedSize },
            method = headers.firstOrNull { !it.isDirectory }?.let(::zipMethodName),
        )
    }

    // with AES, zip keeps the real compression method in the AES extra field
    private fun zipMethodName(header: FileHeader): String? {
        val method = if (header.compressionMethod == CompressionMethod.AES_INTERNAL_ONLY) {
            header.aesExtraDataRecord?.compressionMethod
        } else {
            header.compressionMethod
        }
        return method?.name?.lowercase()?.replaceFirstChar { it.uppercase() }
    }

    private fun inspectWithSevenZip(file: File, kind: ArchiveKind): ArchiveInfo {
        val opened = try {
            ArchiveOpener.open(file, kind, null)
        } catch (e: ArchivePasswordException) {
            // only archives with encrypted file names need the password to be opened at all
            val encryption = when (kind) {
                ArchiveKind.RAR -> if (isRar5(file)) ArchiveEncryption.RarAes(256, true) else ArchiveEncryption.RarAes(128, true)
                else -> ArchiveEncryption.SevenZipAes(true, null)
            }
            val version = if (kind == ArchiveKind.RAR) rarVersion(isRar5(file)) else null
            return ArchiveInfo(kind, encryption, null, null, null, null, version)
        }

        opened.use {
            val archive = opened.archive
            val count = archive.numberOfItems
            val folders = (0 until count).count { archive.getProperty(it, PropID.IS_FOLDER) == true }
            val size = (0 until count).sumOf { (archive.getProperty(it, PropID.SIZE) as? Long) ?: 0L }
            val firstFile = (0 until count).firstOrNull { archive.getProperty(it, PropID.IS_FOLDER) != true }
            val method = firstFile?.let { archive.getStringProperty(it, PropID.METHOD) }
            val isEncrypted = (0 until count).any { archive.getProperty(it, PropID.ENCRYPTED) == true }
            val isRar5 = archive.archiveFormat == ArchiveFormat.RAR5
            val encryption = when {
                !isEncrypted -> ArchiveEncryption.None
                kind == ArchiveKind.SEVEN_ZIP -> ArchiveEncryption.SevenZipAes(false, method?.let(::parseCyclesPower))
                kind == ArchiveKind.RAR -> ArchiveEncryption.RarAes(if (isRar5) 256 else 128, false)
                else -> ArchiveEncryption.SevenZipAes(false, null)
            }
            return ArchiveInfo(
                kind = kind,
                encryption = encryption,
                fileCount = count - folders,
                folderCount = folders,
                unpackedSize = size,
                // the encryption is shown on its own row
                method = method?.split(' ')?.filterNot { it.startsWith(SEVEN_ZIP_AES_METHOD) || it.startsWith(RAR_AES_METHOD) }?.joinToString(" "),
                formatVersion = if (kind == ArchiveKind.RAR) rarVersion(isRar5) else null,
            )
        }
    }

    private fun rarVersion(isRar5: Boolean) = if (isRar5) 5 else 4

    private fun isRar5(file: File): Boolean {
        val start = ByteArray(RAR5_SIGNATURE.size)
        val read = FileInputStream(file).use { it.read(start) }
        return read == start.size && start.contentEquals(RAR5_SIGNATURE)
    }

    // 7-Zip lists the key derivation as "7zAES:19" (2^19 rounds); without a number it's the default of 19
    private fun parseCyclesPower(method: String): Int? {
        val aes = method.split(' ').firstOrNull { it.startsWith(SEVEN_ZIP_AES_METHOD) } ?: return null
        return aes.substringAfter(':', "").takeWhile { it.isDigit() }.toIntOrNull() ?: DEFAULT_CYCLES_POWER
    }

    const val DEFAULT_CYCLES_POWER = 19
}
