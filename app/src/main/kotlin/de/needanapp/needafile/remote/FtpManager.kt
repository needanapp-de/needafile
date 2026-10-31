package de.needanapp.needafile.remote

import de.needanapp.needafile.R
import de.needanapp.needafile.errors.LocalizedException
import de.needanapp.needafile.security.KnownCertificates
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPCmd
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** The server answered an FTP command with an error; [reply] is its answer, e.g. "550 Permission denied". */
class FtpReplyException(private val reply: String) : LocalizedException(R.string.ftp_error_reply, reply), ServerErrorReply {
    // 4xx answers are "transient negative completion" by the standard, e.g. a lost data connection
    override val isTemporary get() = reply.startsWith('4')
}

/**
 * FTP client on Apache Commons Net for a single [RemoteServer] (see [RemoteFileSystem]). It always tries FTPS first,
 * explicit ("AUTH TLS") or implicit as set in [RemoteServer.ftpTls]. A server without TLS is only used unencrypted
 * once the user allowed it ([RemoteServer.allowPlain]); until then connecting throws [PlainFtpRequiredException].
 * Certificates Android doesn't trust need the user's confirmation, see [KnownCertificates].
 *
 * Browsing reuses one kept-open connection, transfers run on their own connection (FTP can't do both on one).
 * Every call blocks, so callers must run them off the main thread and call [close] when done.
 */
class FtpManager(private val server: RemoteServer, private val knownCertificates: KnownCertificates) : ServerFileSystem {
    private var session: FTPClient? = null
    private val trust by lazy { knownCertificates.Trust(server.host, server.port) }

    private fun connect(): FTPClient {
        val implicit = server.ftpTls == FTP_TLS_IMPLICIT
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust.trustManager), null) }
        val ftps = SessionReusingFtpsClient(implicit, context)
        ftps.hostnameVerifier = trust.hostnameVerifier
        ftps.enabledProtocols = FTPS_PROTOCOLS
        configure(ftps)
        try {
            ftps.connect(server.host, server.port)
        } catch (e: IOException) {
            // a refused "AUTH TLS" (an error answer instead of 234) means the server has no TLS at all
            val hasNoTls = !implicit && ftps.isConnected && FTPReply.isNegativePermanent(ftps.replyCode)
            disconnectQuietly(ftps)
            trust.rejectionOf(e)?.let { throw UntrustedCertificateException(it, e) }
            if (!hasNoTls) throw e
            if (!server.allowPlain) throw PlainFtpRequiredException(server.id, server.host, e)
            return connectPlain()
        }

        try {
            login(ftps)
            ftps.execPBSZ(0)
            ftps.execPROT("P")
            ftps.protectData()
            return ftps
        } catch (e: Exception) {
            disconnectQuietly(ftps)
            throw e
        }
    }

    private fun connectPlain(): FTPClient {
        val ftp = FTPClient()
        configure(ftp)
        try {
            ftp.connect(server.host, server.port)
            login(ftp)
            return ftp
        } catch (e: Exception) {
            disconnectQuietly(ftp)
            throw e
        }
    }

    private fun configure(ftp: FTPClient) {
        ftp.connectTimeout = TIMEOUT_MS
        ftp.defaultTimeout = TIMEOUT_MS
        ftp.setDataTimeout(Duration.ofMillis(TIMEOUT_MS.toLong()))
        ftp.controlEncoding = Charsets.UTF_8.name()
        ftp.autodetectUTF8 = true
        // EPSV only tells a port, so servers behind NAT work; commons-net falls back to PASV when it's missing
        ftp.isUseEPSVwithIPv4 = true
    }

    private fun login(ftp: FTPClient) {
        if (!FTPReply.isPositiveCompletion(ftp.replyCode) && ftp.replyCode != FTPReply.SECURITY_MECHANISM_IS_OK) {
            throw FtpReplyException(ftp.replyString.trim())
        }
        val user = server.username.ifEmpty { ANONYMOUS }
        val password = if (server.username.isEmpty() && server.password.isEmpty()) ANONYMOUS_PASSWORD else server.password
        if (!ftp.login(user, password)) throw LocalizedException(R.string.remote_error_wrong_credentials)
        ftp.enterLocalPassiveMode()
        check(ftp, ftp.setFileType(FTP.BINARY_FILE_TYPE))
    }

    private fun disconnectQuietly(ftp: FTPClient) {
        try {
            if (ftp.isConnected) ftp.disconnect()
        } catch (ignored: Exception) {
        }
    }

    /** Runs [block] on a fresh connection that is closed afterwards. */
    private fun <T> useConnection(block: (FTPClient) -> T): T {
        val ftp = connect()
        try {
            return block(ftp)
        } finally {
            try {
                ftp.logout()
            } catch (ignored: Exception) {
            }
            disconnectQuietly(ftp)
        }
    }

    /** Runs [block] on the kept-open browsing connection, reconnecting once if it went stale. */
    @Synchronized
    private fun <T> withSession(block: (FTPClient) -> T): T {
        val existing = session?.takeIf { it.isConnected }
        if (existing != null) {
            try {
                return block(existing)
            } catch (e: FtpReplyException) {
                throw e
            } catch (e: TransferCancelledException) {
                throw e
            } catch (e: IOException) {
                // the connection may have dropped (network change, server idle timeout), retry on a fresh one
            }
        }

        closeSession()
        val ftp = connect()
        session = ftp
        return block(ftp)
    }

    @Synchronized
    override fun close() = closeSession()

    private fun closeSession() {
        session?.let { disconnectQuietly(it) }
        session = null
    }

    override fun testConnection() {
        useConnection { check(it, it.sendNoOp()) }
    }

    override fun listFiles(path: String): List<BrowserFile> = withSession { list(it, path) }

    private fun list(ftp: FTPClient, path: String): List<BrowserFile> {
        // MLSD gives exact UTC times, LIST the server's local time without a zone; RFC 3659 announces both as "MLST"
        val hasMlsd = ftp.hasFeature(FTPCmd.MLSD) || ftp.hasFeature(FTPCmd.MLST)
        val entries: Array<FTPFile> = if (hasMlsd) ftp.mlistDir(path) else ftp.listFiles(path)
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) throw FtpReplyException(ftp.replyString.trim())
        return entries.filterNotNull()
            .filter { it.name != "." && it.name != ".." && it.name.isNotEmpty() }
            .map { entry ->
                val name = entry.name.substringAfterLast('/')
                BrowserFile(
                    name = name,
                    path = joinRemotePath(path, name),
                    isDirectory = entry.isDirectory,
                    size = if (entry.isDirectory) 0 else entry.size.coerceAtLeast(0),
                    lastModified = entry.timestamp?.timeInMillis ?: 0L,
                )
            }
            .sortedWith(BrowserFile.DEFAULT_ORDER)
    }

    override fun openTransfer(): TransferConnection = FtpTransfer(connect())

    /** A connection of its own for one copy; downloads continue with REST, uploads by appending (APPE). */
    private inner class FtpTransfer(private val ftp: FTPClient) : TransferConnection {
        override fun list(path: String) = list(ftp, path)

        override fun sizeOf(path: String): Long? {
            if (ftp.hasFeature(FTPCmd.MLST)) {
                val entry = ftp.mlistFile(path) ?: return null
                return entry.size.takeIf { entry.isFile }
            }
            return ftp.getSize(path)?.trim()?.toLongOrNull()
        }

        // existing folders are fine, the error answer is ignored
        override fun makeFolder(path: String) {
            ftp.makeDirectory(path)
        }

        override fun read(path: String, offset: Long, block: (InputStream) -> Unit) {
            ftp.restartOffset = offset
            val input = ftp.retrieveFileStream(path) ?: throw FtpReplyException(ftp.replyString.trim())
            input.use(block)
            check(ftp, ftp.completePendingCommand())
        }

        override fun write(path: String, offset: Long, length: Long, modified: Long, block: (OutputStream) -> Unit) {
            val output = (if (offset > 0) ftp.appendFileStream(path) else ftp.storeFileStream(path)) ?: throw FtpReplyException(ftp.replyString.trim())
            output.use(block)
            check(ftp, ftp.completePendingCommand())
            // keeps the file's time where the server supports it
            runCatching { ftp.setModificationTime(path, MDTM_FORMAT.format(Instant.ofEpochMilli(modified))) }
        }

        // appending continues at the end, which is where the server stopped receiving
        override val canContinueWrite = true

        override fun deleteFile(path: String) = check(ftp, ftp.deleteFile(path))

        override fun close() {
            try {
                ftp.logout()
            } catch (ignored: Exception) {
            }
            disconnectQuietly(ftp)
        }
    }

    override fun delete(paths: List<String>, onDeleted: (String) -> Unit) = withSession { ftp ->
        paths.forEach { path ->
            if (path.trim('/').isEmpty()) throw LocalizedException(R.string.remote_error_root_delete)
            deleteRecursively(ftp, path, 0, onDeleted)
        }
    }

    override val deletesFileByFile get() = true

    /** Files go at once; for folders (where deleting as a file fails) the content goes first. */
    private fun deleteRecursively(ftp: FTPClient, path: String, depth: Int, onDeleted: (String) -> Unit) {
        if (ftp.deleteFile(path)) {
            onDeleted(path)
            return
        }
        val fileReply = ftp.replyString.trim()
        val children = try {
            list(ftp, path)
        } catch (e: FtpReplyException) {
            throw FtpReplyException(fileReply)
        }
        if (depth > MAX_FOLDER_DEPTH) throw LocalizedException(R.string.remote_error_folders_too_deep, path)
        children.forEach { child ->
            if (child.isDirectory) {
                deleteRecursively(ftp, child.path, depth + 1, onDeleted)
            } else {
                check(ftp, ftp.deleteFile(child.path))
                onDeleted(child.path)
            }
        }
        check(ftp, ftp.removeDirectory(path))
        onDeleted(path)
    }

    override fun move(path: String, newPath: String) = withSession { ftp ->
        check(ftp, ftp.rename(path, newPath))
    }

    override fun createFolder(parentPath: String, name: String): String = withSession { ftp ->
        val path = joinRemotePath(parentPath, name)
        check(ftp, ftp.makeDirectory(path))
        path
    }

    private fun check(ftp: FTPClient, success: Boolean) {
        if (!success) throw FtpReplyException(ftp.replyString.trim())
    }

    /**
     * FTPS whose data connections resume the TLS session of the control connection, which many servers require
     * (vsftpd, ProFTPD). Android caches TLS sessions by host and port, and a TLS socket layered on another one takes
     * the port of the socket below. So each data connection is opened plain, told to report the control connection's
     * port, and then secured with the control connection's host: the cached session is found and resumed.
     *
     * Both use TLS 1.2: TLS 1.3 sessions can be resumed once per ticket only, and uploads never read the new tickets
     * the server sends, so a few uploads in a row would use them up.
     */
    private class SessionReusingFtpsClient(implicit: Boolean, private val context: SSLContext) : FTPSClient(implicit, context) {
        private var isDataProtected = false

        /** Called after "PROT P": data connections are secured from now on, by this class instead of commons-net. */
        fun protectData() {
            setSocketFactory(null)
            isDataProtected = true
        }

        override fun _openDataConnection_(command: String?, arg: String?): Socket? {
            val socket = super._openDataConnection_(command, arg) ?: return null
            if (!isDataProtected || socket is SSLSocket) return socket
            val host = _hostname_ ?: remoteAddress.hostAddress
            val isUpload = command?.uppercase() in UPLOAD_COMMANDS
            val secured = context.socketFactory.createSocket(ControlPortSocket(socket, remotePort, isUpload), host, remotePort, true) as SSLSocket
            try {
                secured.useClientMode = true
                secured.enabledProtocols = FTPS_PROTOCOLS
                secured.startHandshake()
            } catch (e: IOException) {
                runCatching { secured.close() }
                throw e
            }
            return secured
        }
    }

    /** Passes everything on to [socket]; subclasses change single answers. */
    private open class DelegatingSocket(protected val socket: Socket) : Socket() {
        override fun getPort() = socket.port
        override fun getInputStream(): InputStream = socket.getInputStream()
        override fun getOutputStream(): OutputStream = socket.getOutputStream()
        override fun getInetAddress(): InetAddress? = socket.inetAddress
        override fun getLocalAddress(): InetAddress? = socket.localAddress
        override fun getLocalPort() = socket.localPort
        override fun getRemoteSocketAddress(): SocketAddress? = socket.remoteSocketAddress
        override fun getLocalSocketAddress(): SocketAddress? = socket.localSocketAddress
        override fun isConnected() = socket.isConnected
        override fun isBound() = socket.isBound
        override fun isClosed() = socket.isClosed
        override fun isInputShutdown() = socket.isInputShutdown
        override fun isOutputShutdown() = socket.isOutputShutdown
        override fun shutdownInput() = socket.shutdownInput()
        override fun shutdownOutput() = socket.shutdownOutput()
        override fun close() = socket.close()
        override fun setSoTimeout(timeout: Int) {
            socket.soTimeout = timeout
        }
        override fun getSoTimeout() = socket.soTimeout
        override fun setTcpNoDelay(on: Boolean) {
            socket.tcpNoDelay = on
        }
        override fun getTcpNoDelay() = socket.tcpNoDelay
        override fun setKeepAlive(on: Boolean) {
            socket.keepAlive = on
        }
        override fun getKeepAlive() = socket.keepAlive
        override fun setSendBufferSize(size: Int) {
            socket.sendBufferSize = size
        }
        override fun getSendBufferSize() = socket.sendBufferSize
        override fun setReceiveBufferSize(size: Int) {
            socket.receiveBufferSize = size
        }
        override fun getReceiveBufferSize() = socket.receiveBufferSize
        override fun setSoLinger(on: Boolean, linger: Int) = socket.setSoLinger(on, linger)
        override fun getSoLinger() = socket.soLinger
        override fun toString() = socket.toString()
    }

    /**
     * The plain data connection [socket] below its TLS layer, reporting [controlPort] as its port. For uploads
     * ([closesGracefully]) it closes so the server gets every byte and the TLS goodbye: the TLS layer sends its
     * close_notify and then closes this socket at once, and data the server sent meanwhile (session tickets) left unread
     * would turn that into a reset, which can make the server lose the end of the upload. So the sending side is shut
     * down first, then the server's own close is awaited.
     */
    private class ControlPortSocket(socket: Socket, private val controlPort: Int, private val closesGracefully: Boolean) : DelegatingSocket(socket) {
        override fun getPort() = controlPort

        override fun close() {
            if (!closesGracefully || socket.isClosed) {
                socket.close()
                return
            }
            try {
                socket.shutdownOutput()
                socket.soTimeout = CLOSE_TIMEOUT_MS
                val input = socket.getInputStream()
                val buffer = ByteArray(CLOSE_BUFFER_SIZE)
                while (input.read(buffer) >= 0) {
                    // the server's goodbye, skipped until it closes
                }
            } catch (ignored: IOException) {
            } finally {
                socket.close()
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 20_000
        private const val MAX_FOLDER_DEPTH = 100
        private const val ANONYMOUS = "anonymous"
        private val UPLOAD_COMMANDS = setOf("STOR", "APPE", "STOU")
        private val FTPS_PROTOCOLS = arrayOf("TLSv1.2")
        private const val CLOSE_TIMEOUT_MS = 10_000
        private const val CLOSE_BUFFER_SIZE = 4096
        private const val ANONYMOUS_PASSWORD = "anonymous@"
        private val MDTM_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)
    }
}
