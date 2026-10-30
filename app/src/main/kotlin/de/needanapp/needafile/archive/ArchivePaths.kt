package de.needanapp.needafile.archive

import android.net.Uri
import de.needanapp.needafile.remote.isRemotePath
import java.io.File

/**
 * Files inside an archive are addressed as "archive://<location of the archive>/<path inside>", the location being
 * encoded so it fits into one path segment. The location is a phone path, a server path ("remote://…") or again an
 * archive path for an archive inside an archive. Like server paths they flow through the regular path based code of
 * the files tab.
 */
const val ARCHIVE_PATH_PREFIX = "archive://"

fun String.isArchivePath() = startsWith(ARCHIVE_PATH_PREFIX)

fun buildArchivePath(location: String, innerPath: String): String {
    val inner = if (innerPath.startsWith("/")) innerPath else "/$innerPath"
    return ARCHIVE_PATH_PREFIX + Uri.encode(location) + inner
}

/** The top of [archive] seen as a folder; a later part of a split archive on the phone stands for the whole archive. */
fun archiveRootPath(archive: String): String {
    val location = if (archive.isRemotePath() || archive.isArchivePath()) archive else ArchiveVolumes.firstVolume(File(archive)).path
    return buildArchivePath(location, "/")
}

/** Where the archive itself lies. */
fun String.getArchiveLocation(): String = Uri.decode(removePrefix(ARCHIVE_PATH_PREFIX).substringBefore('/'))

/** The path inside the archive, always starting with "/". */
fun String.getArchiveInnerPath(): String {
    val withoutPrefix = removePrefix(ARCHIVE_PATH_PREFIX)
    val slashIndex = withoutPrefix.indexOf('/')
    return if (slashIndex == -1) "/" else withoutPrefix.substring(slashIndex)
}
