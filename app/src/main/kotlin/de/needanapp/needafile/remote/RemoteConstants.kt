package de.needanapp.needafile.remote

const val SERVER_TYPE_SFTP = "sftp"
const val SERVER_TYPE_SMB = "smb"
const val SERVER_TYPE_WEBDAV = "webdav"
const val SERVER_TYPE_FTP = "ftp"

const val AUTH_PASSWORD = 0
const val AUTH_KEY = 1

/** FTPS after connecting ("AUTH TLS", usually port 21) or from the start (usually port 990). */
const val FTP_TLS_EXPLICIT = 0
const val FTP_TLS_IMPLICIT = 1
