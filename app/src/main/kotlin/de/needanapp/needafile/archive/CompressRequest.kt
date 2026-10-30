package de.needanapp.needafile.archive

import net.lingala.zip4j.model.enums.AesKeyStrength

/**
 * What to create: [destination] ends with the format's extension; [encryptFileNames] only applies to 7z with a password,
 * [aesKeyStrength] and [partSize] (split into parts of that many bytes) only to zip.
 */
data class CompressRequest(
    val destination: String,
    val format: ArchiveKind,
    val password: String?,
    val encryptFileNames: Boolean,
    val strength: CompressionStrength = CompressionStrength.MAXIMUM,
    val aesKeyStrength: AesKeyStrength = AesKeyStrength.KEY_STRENGTH_256,
    val partSize: Long? = null,
    /** 7z only: compress the files together in one solid block. */
    val solid: Boolean = false,
)

/**
 * The choices of the compress sheet besides name, folder and password; the last ones are offered again next time. At
 * first 7z with maximum compression, not solid, so single files come out of it quickly.
 */
data class CompressOptions(
    val format: ArchiveKind = ArchiveKind.SEVEN_ZIP,
    val isProtected: Boolean = false,
    val encryptFileNames: Boolean = false,
    val strength: CompressionStrength = CompressionStrength.MAXIMUM,
    val aesKeyStrength: AesKeyStrength = AesKeyStrength.KEY_STRENGTH_256,
    val isSplit: Boolean = false,
    val partSize: Long = ZIP_PART_SIZES[1],
    val isSolid: Boolean = false,
)
