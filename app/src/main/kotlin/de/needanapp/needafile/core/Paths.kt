package de.needanapp.needafile.core

import android.os.StatFs
import android.webkit.MimeTypeMap
import java.text.Normalizer
import java.util.Locale

/** The last part of a path: "c.txt" for "/a/b/c.txt". */
fun String.getFilenameFromPath() = substringAfterLast('/')

/** What follows the last dot; a name without one comes back whole. */
fun String.getFilenameExtension() = substringAfterLast('.')

/** The folder a path lies in: "/a/b" for "/a/b/c", "" for "/c"; a path without a slash stays as it is. */
fun String.getParentPath(): String {
    val slash = lastIndexOf('/')
    return if (slash < 0) this else substring(0, slash)
}

/** Characters no file system on Android or a server likes in a name. */
fun String.isAValidFilename() = none { it in INVALID_FILENAME_CHARS }

fun String.areDigitsOnly() = isNotEmpty() && all { it in '0'..'9' }

/** Without diacritics, e.g. "č" becomes "c", for searching and sorting. */
fun String.withoutAccents(): String = Normalizer.normalize(this, Normalizer.Form.NFD).replace(DIACRITICS, "")

fun String.isZipFile() = endsWith(".zip", true)

/** Whether a folder on the way to this path is hidden (starts with a dot). */
fun String.isPathInHiddenFolder() = split('/').drop(1).dropLast(1).any { it.length > 1 && it.startsWith('.') && it != ".." }

fun String.isImageFast() = PHOTO_EXTENSIONS.any { endsWith(it, true) }

fun String.isVideoFast() = VIDEO_EXTENSIONS.any { endsWith(it, true) }

fun String.isAudioFast() = AUDIO_EXTENSIONS.any { endsWith(it, true) }

/** The mime type by the file's extension, empty when it isn't known. */
fun String.getMimeType(): String {
    val extension = getFilenameFromPath().substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension.isEmpty()) return ""
    return EXTRA_MIME_TYPES[extension] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).orEmpty()
}

/** The best mime type for a group of files: their common one, "type/ *" for a shared type, otherwise any. */
fun List<String>.getCommonMimeType(): String {
    val types = map { it.getMimeType().ifEmpty { ANY_MIME_TYPE } }.distinct()
    return when {
        types.size == 1 -> types.first()
        types.map { it.substringBefore('/') }.distinct().size == 1 -> "${types.first().substringBefore('/')}/*"
        else -> ANY_MIME_TYPE
    }
}

/** Free bytes on the volume holding this path, -1 if unknown. */
fun String.getAvailableStorageB(): Long = try {
    StatFs(this).availableBytes
} catch (e: IllegalArgumentException) {
    -1L
}

const val ANY_MIME_TYPE = "*/*"

private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
private val INVALID_FILENAME_CHARS = charArrayOf('/', '\n', '\r', '\t', '\u0000', '`', '?', '*', '\\', '<', '>', '|', '"', ':')

private val PHOTO_EXTENSIONS = arrayOf(
    ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif", ".avif", ".jxl", ".svg", ".dng", ".raw", ".tif", ".tiff",
)
private val VIDEO_EXTENSIONS = arrayOf(".mp4", ".mkv", ".webm", ".avi", ".3gp", ".mov", ".m4v", ".3gpp", ".ts", ".mpg", ".mpeg", ".wmv")
private val AUDIO_EXTENSIONS = arrayOf(
    ".mp3", ".wav", ".wma", ".ogg", ".m4a", ".opus", ".flac", ".aac", ".m4b", ".amr", ".mid", ".midi", ".ape", ".oga",
)

/** Types Android's own table doesn't know or names differently. */
private val EXTRA_MIME_TYPES = mapOf(
    "7z" to "application/x-7z-compressed",
    "rar" to "application/vnd.rar",
    "gz" to "application/gzip",
    "tgz" to "application/gzip",
    "bz2" to "application/x-bzip2",
    "xz" to "application/x-xz",
    "lzma" to "application/x-lzma",
    "z" to "application/x-compress",
    "iso" to "application/x-iso9660-image",
    "cab" to "application/vnd.ms-cab-compressed",
    "arj" to "application/x-arj",
    "lzh" to "application/x-lzh-compressed",
    "lha" to "application/x-lha",
    "cpio" to "application/x-cpio",
    "rpm" to "application/x-rpm",
    "deb" to "application/vnd.debian.binary-package",
    "chm" to "application/vnd.ms-htmlhelp",
    "xar" to "application/x-xar",
    "wim" to "application/x-ms-wim",
    "md" to "text/markdown",
    "markdown" to "text/markdown",
    "csv" to "text/csv",
    "tsv" to "text/tab-separated-values",
    "json" to "application/json",
    "log" to "text/plain",
    "ini" to "text/plain",
    "conf" to "text/plain",
    "cfg" to "text/plain",
    "yml" to "text/plain",
    "yaml" to "text/plain",
    "toml" to "text/plain",
    "properties" to "text/plain",
    "kt" to "text/plain",
    "kts" to "text/plain",
    "gradle" to "text/plain",
    "sh" to "text/x-sh",
    "py" to "text/x-python",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "avif" to "image/avif",
    "jxl" to "image/jxl",
    "opus" to "audio/ogg",
    "apk" to "application/vnd.android.package-archive",
)
