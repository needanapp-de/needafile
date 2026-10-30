package de.needanapp.needafile.archive

import androidx.annotation.StringRes
import de.needanapp.needafile.R
import net.lingala.zip4j.model.enums.CompressionLevel

/**
 * How strongly a new archive is compressed: as deflate level for zip, where [STORE] packs without compression (method
 * "stored"), and as 7-Zip level (-mx) for 7z, where 0 copies the files.
 */
enum class CompressionStrength(
    @StringRes val label: Int,
    @StringRes val summary: Int,
    val zipLevel: CompressionLevel?,
    val sevenZipLevel: Int,
) {
    STORE(R.string.compression_store, R.string.compression_store_summary, null, 0),
    FAST(R.string.compression_fast, R.string.compression_fast_summary, CompressionLevel.FASTEST, 1),
    NORMAL(R.string.compression_normal, R.string.compression_normal_summary, CompressionLevel.NORMAL, 5),
    MAXIMUM(R.string.compression_maximum, R.string.compression_maximum_summary, CompressionLevel.MAXIMUM, 7),
    ULTRA(R.string.compression_ultra, R.string.compression_ultra_summary, CompressionLevel.ULTRA, 9),
}

/**
 * Part sizes offered for split zips, in decimal units like the sizes shown in the app. 4 GB stays below 4 GiB, the file
 * size limit of FAT32 memory cards and USB sticks.
 */
val ZIP_PART_SIZES = listOf(10_000_000L, 100_000_000L, 1_000_000_000L, 4_000_000_000L)
