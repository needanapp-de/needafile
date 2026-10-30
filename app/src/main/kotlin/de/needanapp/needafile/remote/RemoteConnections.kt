package de.needanapp.needafile.remote

import android.content.Context
import de.needanapp.needafile.core.inBackground
import de.needanapp.needafile.security.KnownCertificates
import de.needanapp.needafile.security.KnownHosts

/** One [RemoteFileSystem] (with its kept-open browsing connection) per server, shared by the whole app. */
object RemoteConnections {
    private val managers = HashMap<String, RemoteFileSystem>()

    @Synchronized
    fun get(context: Context, serverId: String): RemoteFileSystem? {
        managers[serverId]?.let { return it }
        val server = RemoteServersStore.getServer(context, serverId) ?: return null
        return create(context, server).also { managers[serverId] = it }
    }

    /** A new, unshared connection, e.g. to test the settings of a server that isn't saved yet. */
    fun create(context: Context, server: RemoteServer): RemoteFileSystem = when (server.type) {
        SERVER_TYPE_SMB -> SmbManager(server)
        SERVER_TYPE_WEBDAV -> WebDavManager(server, KnownCertificates(context))
        SERVER_TYPE_FTP -> FtpManager(server, KnownCertificates(context))
        else -> SftpManager(server, KnownHosts(context))
    }

    /** Drops the cached connection, e.g. after the server settings changed or the server got deleted. */
    @Synchronized
    fun invalidate(serverId: String) {
        val manager = managers.remove(serverId) ?: return
        inBackground { manager.close() }
    }

    @Synchronized
    fun closeAll() {
        val closing = managers.values.toList()
        managers.clear()
        inBackground { closing.forEach { it.close() } }
    }
}
