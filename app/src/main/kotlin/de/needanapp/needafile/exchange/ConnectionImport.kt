package de.needanapp.needafile.exchange

import de.needanapp.needafile.remote.AUTH_KEY
import de.needanapp.needafile.remote.AUTH_PASSWORD
import de.needanapp.needafile.remote.RemoteServer
import de.needanapp.needafile.remote.SERVER_TYPE_SFTP

/**
 * Taking over connections from needaload without changing anything that is there: a connection to the same host
 * (ignoring case), port and user as a saved SFTP server counts as already there, a new one gets an id of its own, starts
 * in "/" and keeps the defaults otherwise. Host keys are taken over for new connections only, and only for a host and
 * port without a confirmed key; a different confirmed key stays and counts as a conflict.
 */
object ConnectionImport {
    /**
     * What taking over changes: the servers to add and the host keys to trust. [existing] counts connections already
     * there, [hostKeyConflicts] the hosts (with port) whose known key differs, [incomplete] entries that lacked something.
     */
    data class Plan(
        val added: List<RemoteServer>,
        val hostKeys: List<HostKeyToTrust>,
        val existing: Int,
        val hostKeyConflicts: Int,
        val incomplete: Int,
    )

    /** A host key to store for [host] and [port], which have none yet. */
    data class HostKeyToTrust(val host: String, val port: Int, val key: SharedHostKey)

    /** [knownHostKey] answers the confirmed key of a host and port, null for none. */
    fun plan(saved: List<RemoteServer>, incoming: List<SharedConnection>, knownHostKey: (host: String, port: Int) -> SharedHostKey?): Plan {
        val taken = saved.filter { it.type == SERVER_TYPE_SFTP }.mapTo(HashSet()) { identity(it.host, it.port, it.username) }
        val added = ArrayList<RemoteServer>()
        val newKeys = LinkedHashMap<Pair<String, Int>, HostKeyToTrust>()
        val conflicts = HashSet<Pair<String, Int>>()
        var existing = 0
        var incomplete = 0

        incoming.forEach { connection ->
            when {
                !connection.isComplete -> incomplete++
                // also catches the same connection twice in one answer
                !taken.add(identity(connection.host, connection.port, connection.username)) -> existing++
                else -> {
                    added += connection.toRemoteServer()
                    val key = connection.hostKey ?: return@forEach
                    val address = connection.host.lowercase() to connection.port
                    when (newKeys[address]?.key ?: knownHostKey(connection.host, connection.port)) {
                        null -> newKeys[address] = HostKeyToTrust(connection.host, connection.port, key)
                        key -> Unit
                        else -> conflicts += address
                    }
                }
            }
        }
        return Plan(added, newKeys.values.toList(), existing, conflicts.size, incomplete)
    }

    private fun identity(host: String, port: Int, username: String) = Triple(host.trim().lowercase(), port, username.trim())

    private fun SharedConnection.toRemoteServer() = RemoteServer(
        name = name,
        host = host,
        port = port,
        username = username,
        authType = if (auth == SharedConnection.Auth.KEY) AUTH_KEY else AUTH_PASSWORD,
        password = password,
        privateKey = privateKey,
        passphrase = passphrase,
        initialPath = "/",
        type = SERVER_TYPE_SFTP,
    )
}
