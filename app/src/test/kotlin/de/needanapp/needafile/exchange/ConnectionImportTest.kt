package de.needanapp.needafile.exchange

import de.needanapp.needafile.exchange.ConnectionExchangeTest.Companion.blob
import de.needanapp.needafile.exchange.ConnectionImport.HostKeyToTrust
import de.needanapp.needafile.remote.AUTH_KEY
import de.needanapp.needafile.remote.AUTH_PASSWORD
import de.needanapp.needafile.remote.RemoteServer
import de.needanapp.needafile.remote.SERVER_TYPE_SFTP
import de.needanapp.needafile.remote.SERVER_TYPE_SMB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules for taking over connections: nothing that is there changes, host keys are only added where none is known. */
class ConnectionImportTest {
    private val key = SharedHostKey("ssh-ed25519", blob("ssh-ed25519", 1))
    private val otherKey = SharedHostKey("ssh-ed25519", blob("ssh-ed25519", 2))

    private val saved = listOf(
        RemoteServer(id = "a", name = "Web", host = "Web.Example.org", port = 22, username = "alice", password = "old"),
        RemoteServer(id = "b", host = "files.example.org", port = 445, username = "bob", type = SERVER_TYPE_SMB),
    )

    private fun connection(host: String, port: Int = 22, username: String = "alice", hostKey: SharedHostKey? = null) =
        SharedConnection("Name", host, port, username, SharedConnection.Auth.PASSWORD, password = "pw", hostKey = hostKey)

    private fun plan(incoming: List<SharedConnection>, known: Map<String, SharedHostKey> = emptyMap()) =
        ConnectionImport.plan(saved, incoming) { host, port -> known["${host.lowercase()}:$port"] }

    @Test
    fun newConnectionsBecomeSftpServersWithDefaults() {
        val withKey = SharedConnection(
            "Key", "key.example.org", 2222, "root", SharedConnection.Auth.KEY, privateKey = "PRIVATE", passphrase = "satz",
        )
        val result = plan(listOf(connection("new.example.org"), withKey))

        assertEquals(2, result.added.size)
        val (password, privateKey) = result.added
        assertEquals("Name", password.name)
        assertEquals("new.example.org", password.host)
        assertEquals(22, password.port)
        assertEquals("alice", password.username)
        assertEquals(AUTH_PASSWORD, password.authType)
        assertEquals("pw", password.password)
        assertEquals("", password.privateKey)
        assertEquals(SERVER_TYPE_SFTP, password.type)
        assertEquals("/", password.initialPath)

        assertEquals(AUTH_KEY, privateKey.authType)
        assertEquals("PRIVATE", privateKey.privateKey)
        assertEquals("satz", privateKey.passphrase)
        assertEquals("", privateKey.password)
        assertEquals(2222, privateKey.port)

        val defaults = RemoteServer()
        assertEquals(defaults.domain, password.domain)
        assertEquals(defaults.share, password.share)
        assertEquals(defaults.url, password.url)
        assertEquals(defaults.ftpTls, password.ftpTls)
        assertEquals(defaults.allowPlain, password.allowPlain)

        val ids = result.added.map { it.id } + saved.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it.isEmpty() })
        assertEquals(0, result.existing)
    }

    @Test
    fun sameHostPortAndUserIsAlreadyThere() {
        val result = plan(
            listOf(
                // the host ignores case
                connection("web.example.ORG"),
                // another port, another user: new connections
                connection("web.example.org", port = 2222),
                connection("web.example.org", username = "Alice"),
            ),
        )
        assertEquals(1, result.existing)
        assertEquals(listOf(2222 to "alice", 22 to "Alice"), result.added.map { it.port to it.username })
    }

    @Test
    fun onlySftpServersCount() {
        // the saved SMB server has the same host, port and user
        val result = plan(listOf(connection("files.example.org", port = 445, username = "bob")))
        assertEquals(1, result.added.size)
        assertEquals(0, result.existing)
    }

    @Test
    fun theSameConnectionTwiceIsTakenOverOnce() {
        val result = plan(listOf(connection("new.example.org"), connection("NEW.example.org")))
        assertEquals(1, result.added.size)
        assertEquals(1, result.existing)
    }

    @Test
    fun savedServersStayAsTheyAre() {
        val before = saved.map { it.copy() }
        plan(listOf(connection("web.example.org", hostKey = otherKey), connection("new.example.org")))
        assertEquals(before, saved)
    }

    @Test
    fun hostKeyIsTrustedWhenNoneIsKnown() {
        val result = plan(listOf(connection("New.example.org", port = 2222, hostKey = key)))
        assertEquals(listOf(HostKeyToTrust("New.example.org", 2222, key)), result.hostKeys)
        assertEquals(0, result.hostKeyConflicts)
    }

    @Test
    fun aDifferentKnownHostKeyStaysAndCountsAsConflict() {
        val result = plan(
            listOf(connection("new.example.org", hostKey = key), connection("same.example.org", hostKey = key)),
            known = mapOf("new.example.org:22" to otherKey, "same.example.org:22" to key),
        )
        assertEquals(2, result.added.size)
        assertEquals(emptyList<HostKeyToTrust>(), result.hostKeys)
        assertEquals(1, result.hostKeyConflicts)
    }

    @Test
    fun theKnownKeyIsLookedUpForTheRightPort() {
        val result = plan(listOf(connection("new.example.org", port = 2222, hostKey = key)), known = mapOf("new.example.org:22" to otherKey))
        assertEquals(1, result.hostKeys.size)
        assertEquals(0, result.hostKeyConflicts)
    }

    @Test
    fun connectionsToOneHostShareItsKey() {
        val result = plan(
            listOf(
                connection("new.example.org", username = "one", hostKey = key),
                connection("NEW.example.org", username = "two", hostKey = key),
                connection("new.example.org", username = "three", hostKey = otherKey),
                connection("new.example.org", username = "four", hostKey = otherKey),
                connection("new.example.org", username = "five"),
            ),
        )
        assertEquals(5, result.added.size)
        assertEquals(listOf(HostKeyToTrust("new.example.org", 22, key)), result.hostKeys)
        // counted once per host and port
        assertEquals(1, result.hostKeyConflicts)
    }

    @Test
    fun noHostKeyIsTakenOverForAConnectionAlreadyThere() {
        val result = plan(listOf(connection("web.example.org", hostKey = key)))
        assertEquals(1, result.existing)
        assertEquals(emptyList<HostKeyToTrust>(), result.hostKeys)
        assertEquals(0, result.hostKeyConflicts)
    }

    @Test
    fun incompleteConnectionsAreSkipped() {
        val result = plan(
            listOf(
                connection("new.example.org").copy(password = ""),
                connection(" "),
                connection("new.example.org", port = 0),
                connection("new.example.org", username = ""),
                SharedConnection("", "key.example.org", 22, "root", SharedConnection.Auth.KEY, password = "pw"),
                connection("ok.example.org", hostKey = key),
            ),
        )
        assertEquals(5, result.incomplete)
        assertEquals(listOf("ok.example.org"), result.added.map { it.host })
        assertNotEquals(0, result.hostKeys.size)
    }
}
