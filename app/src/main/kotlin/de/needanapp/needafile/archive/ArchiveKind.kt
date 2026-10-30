package de.needanapp.needafile.archive

import java.io.File
import java.util.Locale

/**
 * Archive and compression formats needafile can extract and browse; [ZIP] and [SEVEN_ZIP] can also be created.
 * [isSingleStream] formats hold one compressed file instead of a file tree.
 */
enum class ArchiveKind(
    val extensions: List<String>,
    val isTarCompressed: Boolean = false,
    val isSingleStream: Boolean = false,
) {
    ZIP(listOf(".zip")),
    SEVEN_ZIP(listOf(".7z")),
    RAR(listOf(".rar")),
    TAR(listOf(".tar")),
    TAR_GZIP(listOf(".tar.gz", ".tgz"), isTarCompressed = true),
    TAR_BZIP2(listOf(".tar.bz2", ".tbz2", ".tbz"), isTarCompressed = true),
    TAR_XZ(listOf(".tar.xz", ".txz"), isTarCompressed = true),
    GZIP(listOf(".gz"), isSingleStream = true),
    BZIP2(listOf(".bz2"), isSingleStream = true),
    XZ(listOf(".xz"), isSingleStream = true),
    Z(listOf(".z"), isSingleStream = true),
    LZMA(listOf(".lzma"), isSingleStream = true),
    ISO(listOf(".iso")),
    CAB(listOf(".cab")),
    ARJ(listOf(".arj")),
    LZH(listOf(".lzh", ".lha")),
    WIM(listOf(".wim")),
    CPIO(listOf(".cpio")),
    RPM(listOf(".rpm")),
    DEB(listOf(".deb")),
    CHM(listOf(".chm")),
    XAR(listOf(".xar"));

    val canBeEncrypted get() = this == ZIP || this == SEVEN_ZIP || this == RAR

    companion object {
        // longest extensions first, so "x.tar.gz" isn't taken for a plain ".gz"
        private val byExtension = entries.flatMap { kind -> kind.extensions.map { it to kind } }.sortedByDescending { it.first.length }

        fun of(path: String): ArchiveKind? = ArchiveVolumes.kindOfVolume(path)
            ?: byExtension.firstOrNull { path.endsWith(it.first, ignoreCase = true) }?.second

        /** The file name without the archive extension, e.g. "photos" for "photos.tar.gz" or "photos.part2.rar". */
        fun baseName(fileName: String): String {
            ArchiveVolumes.baseNameOfVolume(fileName)?.let { return it }
            val extension = byExtension.firstOrNull { fileName.endsWith(it.first, ignoreCase = true) }?.first ?: return fileName
            return fileName.dropLast(extension.length).ifEmpty { fileName }
        }
    }
}

fun String.isArchiveFile() = ArchiveKind.of(this) != null

/** The earlier parts of a split zip (name.z01, name.z02 …) next to its last part [zip] (name.zip), in order. */
fun splitZipParts(zip: File): List<File> {
    val base = zip.name.substringBeforeLast('.')
    return generateSequence(1) { it + 1 }
        .map { File(zip.parentFile, "%s.z%02d".format(Locale.ROOT, base, it)) }
        .takeWhile { it.exists() }
        .toList() + zip
}

/** Whether [zip] is the last part of a split zip, i.e. name.z01 lies next to it. */
fun isSplitZip(zip: File) = splitZipParts(zip).size > 1
