package de.needanapp.needafile.remote

import java.util.UUID

/**
 * A saved remote server connection (SFTP, SMB or WebDAV, see [type]). Secret fields (password,
 * privateKey, passphrase) are held in plain text only in memory; they are encrypted before being
 * persisted (see RemoteServersStore + CryptoHelper).
 *
 * - SFTP uses [host], [port], [username] and password or key auth, starting in [initialPath].
 * - SMB uses [host], [port], [username] (empty = guest), [password] and [domain]. Server paths are
 *   "/<share>/<folder>…", "/" lists the shares. [share] is an optional share to start in.
 * - WebDAV uses [url] (e.g. https://cloud.example.com/remote.php/dav/files/me/) plus [username] and
 *   [password]. Server paths are relative to that URL, [host] only mirrors the URL's host for display.
 * - FTP uses [host], [port], [username] (empty = anonymous), [password] and [initialPath]. It always tries FTPS first
 *   ([ftpTls] explicit or implicit); [allowPlain] lets it fall back to unencrypted FTP when the server has no TLS,
 *   which the user has to agree to once per server.
 */
data class RemoteServer(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var host: String = "",
    var port: Int = 22,
    var username: String = "",
    var authType: Int = AUTH_PASSWORD,
    var password: String = "",
    var privateKey: String = "",
    var passphrase: String = "",
    var initialPath: String = "/",
    var type: String = SERVER_TYPE_SFTP,
    var domain: String = "",
    var share: String = "",
    var url: String = "",
    var ftpTls: Int = FTP_TLS_EXPLICIT,
    var allowPlain: Boolean = false,
) {
    fun getSummary(): String {
        val user = if (username.isEmpty()) "" else "$username@"
        return when (type) {
            SERVER_TYPE_WEBDAV -> url
            SERVER_TYPE_SMB -> "smb://$user$host" + (if (port != DEFAULT_SMB_PORT) ":$port" else "") + (if (share.isNotEmpty()) "/$share" else "")
            SERVER_TYPE_FTP -> {
                val defaultPort = if (ftpTls == FTP_TLS_IMPLICIT) DEFAULT_FTPS_IMPLICIT_PORT else DEFAULT_FTP_PORT
                (if (allowPlain) "ftp://" else "ftps://") + user + host + if (port != defaultPort) ":$port" else ""
            }
            else -> "sftp://$user$host" + if (port != DEFAULT_SFTP_PORT) ":$port" else ""
        }
    }

    fun getDisplayName() = name.ifEmpty { host.ifEmpty { url } }

    /** The start folder as a path usable in the files tab (see RemotePaths). */
    fun getStartPath() = buildRemotePath(id, initialPath.ifEmpty { "/" })

    companion object {
        const val DEFAULT_SFTP_PORT = 22
        const val DEFAULT_SMB_PORT = 445
        const val DEFAULT_FTP_PORT = 21
        const val DEFAULT_FTPS_IMPLICIT_PORT = 990
    }
}
