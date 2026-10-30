package de.needanapp.needafile.remote

/**
 * Files on servers are addressed as "remote://<serverId>/<path on the server>", so they can flow
 * through the regular path based code of the files tab (tabs, sorting, clipboard…), whatever the
 * protocol of the server is.
 */
const val REMOTE_PATH_PREFIX = "remote://"

/** Used before SMB and WebDAV existed, still found in saved tabs. */
private const val LEGACY_REMOTE_PATH_PREFIX = "sftp://"

fun String.isRemotePath() = startsWith(REMOTE_PATH_PREFIX)

fun String.migrateLegacyRemotePath() = if (startsWith(LEGACY_REMOTE_PATH_PREFIX)) {
    REMOTE_PATH_PREFIX + removePrefix(LEGACY_REMOTE_PATH_PREFIX)
} else {
    this
}

fun buildRemotePath(serverId: String, serverPath: String): String {
    val path = if (serverPath.startsWith("/")) serverPath else "/$serverPath"
    return "$REMOTE_PATH_PREFIX$serverId$path"
}

fun String.getRemoteServerId() = removePrefix(REMOTE_PATH_PREFIX).substringBefore('/')

/** The path on the server, always starting with "/". */
fun String.getRemoteServerPath(): String {
    val withoutPrefix = removePrefix(REMOTE_PATH_PREFIX)
    val slashIndex = withoutPrefix.indexOf('/')
    return if (slashIndex == -1) "/" else withoutPrefix.substring(slashIndex)
}
