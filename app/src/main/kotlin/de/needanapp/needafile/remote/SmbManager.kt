package de.needanapp.needafile.remote

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.NamedPipe
import com.hierynomus.smbj.share.PipeShare
import com.rapid7.client.dcerpc.Interface
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.RPCTransport
import de.needanapp.needafile.R
import de.needanapp.needafile.errors.LocalizedException
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Thin SMBJ-based SMB 2/3 client for a single [RemoteServer] (see [RemoteFileSystem]). Server paths are
 * "/<share>/<folder>/…", "/" lists the shares. Browsing reuses one kept-open connection, transfers run
 * on their own connection so browsing stays responsive meanwhile. Every call blocks, so callers must
 * run them off the main thread and call [close] when done.
 */
class SmbManager(private val server: RemoteServer) : ServerFileSystem {
    private var browsingSession: SmbSession? = null

    /** An authenticated connection; SMBJ's session keeps the shares opened on it. */
    private class SmbSession(private val client: SMBClient, val session: Session) : Closeable {
        val isConnected get() = session.connection.isConnected

        fun share(name: String): DiskShare {
            val share = session.connectShare(name)
            if (share !is DiskShare) {
                share.close()
                throw LocalizedException(R.string.remote_error_not_a_file_share, name)
            }
            return share
        }

        /** Drops the connection without logging off first, so a dead network can't stall it. */
        override fun close() = client.close()
    }

    private fun connect(timeoutMs: Long): SmbSession {
        val config = SmbConfig.builder()
            .withSocketFactory(ProxySocketFactory(CONNECT_TIMEOUT_MS))
            .withTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .withEncryptData(true) // SMB 3 servers then encrypt everything, shares requiring encryption need it
            .withDirectoryLeasingEnabled(false) // SMBJ would serve listings from a cache otherwise
            .build()
        val client = SMBClient(config)
        try {
            val connection = client.connect(server.host, server.port.takeIf { it > 0 } ?: RemoteServer.DEFAULT_SMB_PORT)
            return SmbSession(client, authenticate(connection))
        } catch (e: Exception) {
            client.close()
            throw e
        }
    }

    /** An empty user name logs in as guest, or anonymously for servers that only allow that. */
    private fun authenticate(connection: Connection): Session {
        if (server.username.isEmpty()) {
            return try {
                connection.authenticate(AuthenticationContext.guest())
            } catch (e: Exception) {
                try {
                    connection.authenticate(AuthenticationContext.anonymous())
                } catch (ignored: Exception) {
                    throw if (e.isLogonFailure()) LocalizedException(R.string.remote_error_guest_not_allowed, cause = e) else e
                }
            }
        }

        // "DOMAIN\user" works as well as a separate domain
        val (domain, user) = if (server.domain.isEmpty() && '\\' in server.username) {
            server.username.substringBefore('\\') to server.username.substringAfter('\\')
        } else {
            server.domain to server.username
        }
        try {
            return connection.authenticate(AuthenticationContext(user, server.password.toCharArray(), domain.ifEmpty { null }))
        } catch (e: Exception) {
            throw if (e.isLogonFailure()) LocalizedException(R.string.remote_error_wrong_credentials, cause = e) else e
        }
    }

    private fun Exception.isLogonFailure() = this is SMBApiException && status in LOGON_FAILURE_STATUSES

    /** Runs [block] on the kept-open browsing connection, reconnecting once if it went stale. */
    @Synchronized
    private fun <T> withSession(block: (SmbSession) -> T): T {
        val existing = browsingSession?.takeIf { it.isConnected }
        if (existing != null) {
            try {
                return block(existing)
            } catch (e: Exception) {
                if (!isConnectionLost(e)) throw e
                // the connection may have dropped (network change, server idle timeout), retry on a fresh one
            }
        }

        closeSession()
        val session = connect(BROWSE_TIMEOUT_MS)
        browsingSession = session
        return block(session)
    }

    /** Closes the kept-open browsing connection. */
    @Synchronized
    override fun close() = closeSession()

    private fun closeSession() {
        try {
            browsingSession?.close()
        } catch (ignored: Exception) {
        }
        browsingSession = null
    }

    /** Verifies host/credentials (and the configured share, if any) by opening a session; throws on failure. */
    override fun testConnection() {
        connect(BROWSE_TIMEOUT_MS).use { smb ->
            val share = getConfiguredShare()
            if (share.isNotEmpty()) {
                smb.share(share)
            }
        }
    }

    override fun listFiles(path: String): List<BrowserFile> = withSession { smb ->
        val files = if (splitPath(path).first.isEmpty()) listShares(smb) else listFolder(smb, path)
        files.sortedWith(BrowserFile.DEFAULT_ORDER)
    }

    private fun listFolder(smb: SmbSession, path: String): List<BrowserFile> {
        val (share, sharePath) = splitPath(path)
        return smb.share(share).list(sharePath)
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { toBrowserFile(it, joinRemotePath(path, it.fileName)) }
    }

    private fun toBrowserFile(info: FileIdBothDirectoryInformation, path: String): BrowserFile {
        val isDirectory = info.fileAttributes.has(FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
        return BrowserFile(
            name = info.fileName,
            path = path,
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else info.endOfFile,
            lastModified = info.lastWriteTime.toEpochMillis(),
        )
    }

    /** The folders shown at "/": the server's visible disk shares plus the share from the settings. */
    private fun listShares(smb: SmbSession): List<BrowserFile> {
        val configured = getConfiguredShare()
        val names = try {
            enumerateShares(smb.session)
        } catch (e: Exception) {
            if (isConnectionLost(e)) throw e
            if (configured.isEmpty()) {
                throw LocalizedException(R.string.remote_error_share_list_failed, e.message ?: e.javaClass.simpleName, cause = e)
            }
            emptyList()
        }

        val shares = if (configured.isEmpty() || names.any { it.equals(configured, ignoreCase = true) }) names else names + configured
        return shares.map { BrowserFile(it, "/$it", isDirectory = true, size = 0, lastModified = 0) }
    }

    /** Asks the server service (SRVSVC) for its shares, leaving out hidden ("…$"), printer and IPC ones. */
    private fun enumerateShares(session: Session): List<String> =
        (session.connectShare("IPC$") as PipeShare).use { ipc ->
            ipc.open(
                "srvsvc",
                SMB2ImpersonationLevel.Impersonation,
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                null,
            ).use { pipe ->
                val transport = PipeTransport(pipe)
                transport.bind(Interface.SRVSVC_V3_0, Interface.NDR_32BIT_V2)
                val shares = try {
                    ServerService(transport).shares1
                } catch (e: NoClassDefFoundError) {
                    // dcerpc reports malformed answers with java.rmi exceptions, Android has no java.rmi
                    throw LocalizedException(R.string.remote_error_malformed_share_list, cause = e)
                }
                shares.filter { (it.type and STYPE_MASK) == STYPE_DISKTREE && it.netName?.endsWith("$") == false }
                    .map { it.netName }
            }
        }

    /** DCE/RPC over SMBJ's pipe; dcerpc's own pipe class opens with FILE_OPEN_IF, which some servers refuse. */
    private class PipeTransport(private val pipe: NamedPipe) : RPCTransport() {
        override fun transact(packetOut: ByteArray, packetIn: ByteArray) = pipe.transact(packetOut, packetIn)

        override fun write(packetOut: ByteArray) {
            pipe.write(packetOut)
        }

        override fun read(packetIn: ByteArray) = pipe.read(packetIn)
    }

    override fun openTransfer(): TransferConnection = SmbTransfer(connect(TRANSFER_TIMEOUT_MS))

    /** A connection of its own for one copy; writes go at any offset, so uploads continue where they stopped. */
    private inner class SmbTransfer(private val smb: SmbSession) : TransferConnection {
        override fun checkTarget(path: String) {
            if (splitPath(path).first.isEmpty()) throw LocalizedException(R.string.remote_error_copy_into_share_only)
        }

        override fun list(path: String) = listFolder(smb, path)

        override fun sizeOf(path: String): Long? {
            val (share, sharePath) = splitPath(path)
            val diskShare = smb.share(share)
            if (!diskShare.fileExists(sharePath)) return null
            return diskShare.getFileInformation(sharePath).standardInformation.endOfFile
        }

        override fun makeFolder(path: String) {
            val (share, sharePath) = splitPath(path)
            val diskShare = smb.share(share)
            try {
                diskShare.mkdir(sharePath)
            } catch (e: SMBApiException) {
                // an existing folder is fine, an existing file is not
                if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION || !diskShare.folderExists(sharePath)) throw e
            }
        }

        override fun read(path: String, offset: Long, block: (InputStream) -> Unit) {
            val (share, sharePath) = splitPath(path)
            smb.share(share).openFile(
                sharePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { remoteFile ->
                // SMBJ's stream skips by moving its read position, nothing is read for it
                remoteFile.inputStream.use { input ->
                    if (input.skip(offset) != offset) throw EOFException()
                    block(input)
                }
            }
        }

        override fun write(path: String, offset: Long, length: Long, modified: Long, block: (OutputStream) -> Unit) {
            val (share, sharePath) = splitPath(path)
            smb.share(share).openFile(
                sharePath,
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.FILE_READ_ATTRIBUTES),
                null,
                SMB2ShareAccess.ALL,
                if (offset > 0) SMB2CreateDisposition.FILE_OPEN else SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { remoteFile ->
                // continuing cuts the file at the offset and appends from there
                if (offset > 0) remoteFile.setLength(offset)
                remoteFile.getOutputStream(offset > 0).use(block)

                // set on the still open handle, so closing it doesn't bump the time again
                runCatching {
                    val unchanged = FileBasicInformation.DONT_SET
                    remoteFile.setFileInformation(FileBasicInformation(unchanged, unchanged, FileTime.ofEpochMillis(modified), unchanged, 0))
                }
            }
        }

        override val canContinueWrite = true

        override fun deleteFile(path: String) {
            val (share, sharePath) = splitPath(path)
            smb.share(share).rm(sharePath)
        }

        override fun close() = smb.close()
    }

    /** Deletes the given files and folders on the server, folders with all their content. */
    override fun delete(paths: List<String>) = withSession { smb ->
        paths.forEach { path ->
            val (share, sharePath) = splitPath(path)
            if (sharePath.isEmpty()) throw LocalizedException(R.string.remote_error_share_delete)
            deleteRecursively(smb.share(share), sharePath)
        }
    }

    /** Links (reparse points) are removed without touching what they point to. */
    private fun deleteRecursively(share: DiskShare, path: String) {
        share.open(
            path,
            EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT),
        ).use { entry ->
            val attributes = entry.getFileInformation(FileBasicInformation::class.java).fileAttributes
            if (attributes.has(FileAttributes.FILE_ATTRIBUTE_DIRECTORY) && !attributes.has(FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT)) {
                share.list(path)
                    .filter { it.fileName != "." && it.fileName != ".." }
                    .forEach { deleteRecursively(share, "$path\\${it.fileName}") }
            }
            entry.deleteOnClose()
        }
    }

    override fun move(path: String, newPath: String) = withSession { smb ->
        val (share, sharePath) = splitPath(path)
        if (sharePath.isEmpty()) throw LocalizedException(R.string.remote_error_share_rename)
        // a rename stays within its share, other shares are reached by copying
        val (newShare, newSharePath) = splitPath(newPath)
        if (newShare != share || newSharePath.isEmpty()) throw LocalizedException(R.string.remote_error_share_move)
        smb.share(share).open(
            sharePath,
            EnumSet.of(AccessMask.DELETE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT),
        ).use { it.rename(newSharePath) }
    }

    override fun canMove(path: String, newPath: String): Boolean {
        val (share, sharePath) = splitPath(path)
        val (newShare, newSharePath) = splitPath(newPath)
        return share == newShare && sharePath.isNotEmpty() && newSharePath.isNotEmpty()
    }

    override fun createFolder(parentPath: String, name: String): String = withSession { smb ->
        val path = joinRemotePath(parentPath, name)
        val (share, sharePath) = splitPath(path)
        if (sharePath.isEmpty()) throw LocalizedException(R.string.remote_error_share_create)
        smb.share(share).mkdir(sharePath)
        path
    }

    /** "/<share>/<a>/<b>" → the share and "a\b" inside it; both are empty for "/". */
    private fun splitPath(path: String): Pair<String, String> {
        val segments = path.split('/').filter { it.isNotEmpty() }
        return (segments.firstOrNull() ?: "") to segments.drop(1).joinToString("\\")
    }

    private fun getConfiguredShare() = server.share.split('/', '\\').firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun Long.has(attribute: FileAttributes) = EnumUtils.isSet(this, attribute)

    /** Whether [e] means the connection or session is gone, so a fresh connection may succeed. */
    private fun isConnectionLost(e: Throwable) = generateSequence(e) { it.cause }.any {
        it is TransportException || (it is SMBApiException && it.status in SESSION_GONE_STATUSES)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20000
        private const val BROWSE_TIMEOUT_MS = 20000L

        // reads and writes go in chunks of up to 1 MB, give slow links time for those
        private const val TRANSFER_TIMEOUT_MS = 60000L

        private const val STYPE_MASK = 0xF
        private const val STYPE_DISKTREE = 0

        private val LOGON_FAILURE_STATUSES = setOf(NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCESS_DENIED)

        private val SESSION_GONE_STATUSES = setOf(
            NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
            NtStatus.STATUS_USER_SESSION_DELETED,
            NtStatus.STATUS_NETWORK_NAME_DELETED,
        )
    }
}
