package de.needanapp.needafile.remote

import de.needanapp.needafile.security.KnownHosts
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.Security
import java.util.EnumSet

/**
 * Thin sshj-based SFTP client for a single [RemoteServer] (see [RemoteFileSystem]). Browsing reuses one kept-open connection,
 * transfers run on their own connection so browsing stays responsive meanwhile. Every call blocks,
 * so callers must run them off the main thread and call [close] when done.
 *
 * Host keys are checked against [KnownHosts]: an unconfirmed or changed key aborts the connection with a
 * [HostKeyException], which the UI turns into a confirmation dialog (trust on first use).
 */
class SftpManager(private val server: RemoteServer, private val knownHosts: KnownHosts) : ServerFileSystem {
    private var sessionClient: SSHClient? = null
    private var sessionSftp: SFTPClient? = null

    private fun buildClient(): SSHClient {
        ensureBouncyCastle()
        val ssh = SSHClient(DefaultConfig())
        val verifier = knownHosts.Verifier()
        ssh.addHostKeyVerifier(verifier)
        ssh.connectTimeout = CONNECT_TIMEOUT_MS
        ssh.timeout = CONNECT_TIMEOUT_MS
        try {
            ssh.connect(server.host, server.port)
            authenticate(ssh)
        } catch (e: Exception) {
            disconnectQuietly(ssh)
            throw verifier.rejection?.let { HostKeyException(it, e) } ?: e
        }
        return ssh
    }

    private fun authenticate(ssh: SSHClient) {
        if (server.authType == AUTH_KEY && server.privateKey.isNotBlank()) {
            val finder = if (server.passphrase.isNotEmpty()) {
                object : PasswordFinder {
                    override fun reqPassword(resource: Resource<*>?) = server.passphrase.toCharArray()
                    override fun shouldRetry(resource: Resource<*>?) = false
                }
            } else {
                null
            }
            val keys = ssh.loadKeys(server.privateKey, null, finder)
            ssh.authPublickey(server.username, keys)
        } else {
            ssh.authPassword(server.username, server.password)
        }
    }

    private fun disconnectQuietly(ssh: SSHClient) {
        try {
            ssh.disconnect()
        } catch (ignored: Exception) {
        }
    }

    /** Runs [block] on the kept-open browsing connection, reconnecting once if it went stale. */
    @Synchronized
    private fun <T> withSession(block: (SFTPClient) -> T): T {
        val existing = sessionSftp?.takeIf { sessionClient?.isConnected == true }
        if (existing != null) {
            try {
                return block(existing)
            } catch (e: SFTPException) {
                throw e
            } catch (e: IOException) {
                // the connection may have dropped (network change, server idle timeout), retry on a fresh one
            }
        }

        closeSession()
        val client = buildClient()
        sessionClient = client
        val sftp = client.newSFTPClient()
        sessionSftp = sftp
        return block(sftp)
    }

    /** Closes the kept-open browsing connection. */
    @Synchronized
    override fun close() = closeSession()

    private fun closeSession() {
        try {
            sessionSftp?.close()
        } catch (ignored: Exception) {
        }
        try {
            sessionClient?.disconnect()
        } catch (ignored: Exception) {
        }
        sessionSftp = null
        sessionClient = null
    }

    /** Verifies host/credentials by opening a session; throws on failure. */
    override fun testConnection() {
        val ssh = buildClient()
        try {
            ssh.isAuthenticated
        } finally {
            try {
                ssh.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }

    override fun listFiles(path: String): List<BrowserFile> = withSession { sftp ->
        sftp.ls(path)
            .filter { it.name != "." && it.name != ".." }
            .map { toBrowserFile(sftp, it) }
            .sortedWith(BrowserFile.DEFAULT_ORDER)
    }

    /** Symlinks are resolved, so linked folders can be opened and copied like normal ones. */
    private fun toBrowserFile(sftp: SFTPClient, entry: RemoteResourceInfo): BrowserFile {
        var attrs = entry.attributes
        if (attrs.type == FileMode.Type.SYMLINK) {
            attrs = try {
                sftp.stat(entry.path)
            } catch (e: IOException) {
                attrs
            }
        }

        return BrowserFile(
            name = entry.name,
            path = entry.path,
            isDirectory = attrs.type == FileMode.Type.DIRECTORY,
            size = attrs.size,
            lastModified = attrs.mtime * 1000L,
        )
    }

    override fun openTransfer(): TransferConnection {
        val ssh = buildClient()
        try {
            return SftpTransfer(ssh, ssh.newSFTPClient())
        } catch (e: Exception) {
            disconnectQuietly(ssh)
            throw e
        }
    }

    /** A connection of its own for one copy; writes go at any offset, so uploads continue where they stopped. */
    private inner class SftpTransfer(private val ssh: SSHClient, private val sftp: SFTPClient) : TransferConnection {
        override fun list(path: String) = sftp.ls(path)
            .filter { it.name != "." && it.name != ".." }
            .map { toBrowserFile(sftp, it) }

        override fun folderKey(path: String): String = sftp.canonicalize(path)

        override fun sizeOf(path: String) = sftp.statExistence(path)?.takeIf { it.type != FileMode.Type.DIRECTORY }?.size

        override fun makeFolder(path: String) {
            if (sftp.statExistence(path) == null) {
                sftp.mkdir(path)
            }
        }

        override fun read(path: String, offset: Long, block: (InputStream) -> Unit) {
            sftp.open(path, EnumSet.of(OpenMode.READ)).use { remoteFile ->
                remoteFile.ReadAheadRemoteFileInputStream(MAX_UNCONFIRMED_REQUESTS, offset).use(block)
            }
        }

        override fun write(path: String, offset: Long, length: Long, modified: Long, block: (OutputStream) -> Unit) {
            val modes = if (offset > 0) EnumSet.of(OpenMode.WRITE) else EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
            sftp.open(path, modes).use { remoteFile ->
                remoteFile.RemoteFileOutputStream(offset, MAX_UNCONFIRMED_REQUESTS).use(block)
            }
            val mtime = modified / 1000
            runCatching { sftp.setattr(path, FileAttributes.Builder().withAtimeMtime(mtime, mtime).build()) }
        }

        override val canContinueWrite = true

        // several writes are on their way at once, the server may have kept a later one but not an earlier
        override val rewriteMargin = MAX_UNCONFIRMED_REQUESTS * WRITE_CHUNK_SIZE

        override fun deleteFile(path: String) = sftp.rm(path)

        override fun close() {
            runCatching { sftp.close() }
            disconnectQuietly(ssh)
        }
    }

    /** Deletes the given files and folders on the server, folders with all their content. */
    override fun delete(paths: List<String>) = withSession { sftp ->
        fun deleteRecursively(path: String) {
            // lstat, so a symlinked folder only loses the link and not the linked content
            if (sftp.lstat(path).type == FileMode.Type.DIRECTORY) {
                sftp.ls(path)
                    .filter { it.name != "." && it.name != ".." }
                    .forEach { deleteRecursively(it.path) }
                sftp.rmdir(path)
            } else {
                sftp.rm(path)
            }
        }
        paths.forEach { deleteRecursively(it) }
    }

    override fun move(path: String, newPath: String) = withSession { sftp ->
        sftp.rename(path, newPath)
    }

    override fun createFolder(parentPath: String, name: String): String = withSession { sftp ->
        val path = joinRemotePath(parentPath, name)
        sftp.mkdir(path)
        path
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20000
        private const val MAX_UNCONFIRMED_REQUESTS = 16
        private const val WRITE_CHUNK_SIZE = 32 * 1024L

        @Volatile
        private var bouncyCastleReady = false

        /**
         * Android ships a stripped-down BouncyCastle; replace it with sshj's full provider so
         * modern key exchange/cipher algorithms are available.
         */
        private fun ensureBouncyCastle() {
            if (bouncyCastleReady) return
            synchronized(this) {
                if (bouncyCastleReady) return
                Security.removeProvider("BC")
                Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
                bouncyCastleReady = true
            }
        }
    }
}
