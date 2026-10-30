package de.needanapp.needafile.exchange

import de.needanapp.needafile.exchange.ConnectionExchange.Decoded
import de.needanapp.needafile.remote.AUTH_KEY
import de.needanapp.needafile.remote.AUTH_PASSWORD
import de.needanapp.needafile.remote.RemoteServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64

/** The JSON both apps exchange (format 1): what needafile writes, and how leniently it reads needaload's answer. */
class ConnectionExchangeTest {
    private val hostKey = SharedHostKey("ssh-ed25519", blob("ssh-ed25519", 1))

    private val withPassword = SharedConnection(
        name = "Web",
        host = "web.example.org",
        port = 22,
        username = "alice",
        auth = SharedConnection.Auth.PASSWORD,
        password = "geheim \"mit\" Zeichen ü",
        hostKey = hostKey,
    )

    private val withKey = SharedConnection(
        name = "",
        host = "10.0.0.5",
        port = 2222,
        username = "root",
        auth = SharedConnection.Auth.KEY,
        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----",
    )

    // region writing

    @Test
    fun writesFormatAppAndOnlyTheFieldsOfTheAuthentication() {
        val root = JSONObject(ConnectionExchange.encode(listOf(withPassword, withKey.copy(passphrase = "satz"))))
        assertEquals(1, root.getInt("format"))
        assertEquals("needafile", root.getString("app"))
        val connections = root.getJSONArray("connections")
        assertEquals(2, connections.length())

        val password = connections.getJSONObject(0)
        assertEquals("Web", password.getString("name"))
        assertEquals("web.example.org", password.getString("host"))
        assertEquals(22, password.getInt("port"))
        assertEquals("alice", password.getString("username"))
        assertEquals("password", password.getString("auth"))
        assertEquals(withPassword.password, password.getString("password"))
        assertFalse(password.has("privateKey"))
        assertFalse(password.has("passphrase"))
        val key = password.getJSONObject("hostKey")
        assertEquals(hostKey.type, key.getString("type"))
        assertEquals(hostKey.blob, key.getString("blob"))

        val withPrivateKey = connections.getJSONObject(1)
        assertEquals("key", withPrivateKey.getString("auth"))
        assertEquals(withKey.privateKey, withPrivateKey.getString("privateKey"))
        assertEquals("satz", withPrivateKey.getString("passphrase"))
        assertFalse(withPrivateKey.has("password"))
    }

    @Test
    fun leavesOutEmptyFieldsAndAMissingHostKey() {
        val json = JSONObject(ConnectionExchange.encode(listOf(withKey))).getJSONArray("connections").getJSONObject(0)
        assertFalse(json.has("name"))
        assertFalse(json.has("passphrase"))
        assertFalse(json.has("hostKey"))
    }

    @Test
    fun writesOnlyCompleteConnections() {
        val noPassword = withPassword.copy(password = "")
        val noKey = withKey.copy(privateKey = " ")
        val json = JSONObject(ConnectionExchange.encode(listOf(noPassword, withKey, noKey)))
        val connections = json.getJSONArray("connections")
        assertEquals(1, connections.length())
        assertEquals("10.0.0.5", connections.getJSONObject(0).getString("host"))
    }

    @Test
    fun readsWhatItWrites() {
        val connections = listOf(withPassword, withKey.copy(passphrase = "satz"))
        assertEquals(Decoded.Connections(connections, 0), ConnectionExchange.decode(ConnectionExchange.encode(connections)))
    }

    @Test
    fun serverBecomesSharedConnectionWithTheSecretsOfItsAuthentication() {
        val passwordServer = RemoteServer(
            name = " Web ", host = " web.example.org ", port = 22, username = "alice ", authType = AUTH_PASSWORD,
            password = "pw", privateKey = "left over", passphrase = "left over",
        )
        val shared = passwordServer.toShared(hostKey)
        assertEquals(withPassword.copy(password = "pw"), shared)

        val keyServer = RemoteServer(host = "10.0.0.5", port = 2222, username = "root", authType = AUTH_KEY, password = "left over",
            privateKey = withKey.privateKey + "\n", passphrase = "satz")
        assertEquals(withKey.copy(passphrase = "satz"), keyServer.toShared(null))
    }

    @Test
    fun secretsStayOutOfToString() {
        val text = withPassword.copy(privateKey = "PRIVATE", passphrase = "PASSPHRASE").toString()
        assertFalse(text.contains(withPassword.password))
        assertFalse(text.contains("PRIVATE"))
        assertFalse(text.contains("PASSPHRASE"))
    }

    // endregion

    // region reading

    @Test
    fun readsNeedaloadsAnswerAndIgnoresUnknownFields() {
        val answer = answer(
            connection("Load", "load.example.org", 22, "bob", "password", "password" to "pw")
                .put("watchedUnits", JSONArray().put("nginx.service"))
                .put("hostKey", JSONObject().put("type", hostKey.type).put("blob", hostKey.blob).put("comment", "x")),
        ).put("app", "needaload").put("exportedAt", 1_700_000_000)

        val decoded = ConnectionExchange.decode(answer.toString()) as Decoded.Connections
        assertEquals(0, decoded.skipped)
        val expected = SharedConnection("Load", "load.example.org", 22, "bob", SharedConnection.Auth.PASSWORD, password = "pw", hostKey = hostKey)
        assertEquals(listOf(expected), decoded.connections)
    }

    @Test
    fun rejectsOtherFormats() {
        assertEquals(Decoded.UnsupportedFormat(2), ConnectionExchange.decode(answer(validConnection()).put("format", 2).toString()))
        assertEquals(Decoded.UnsupportedFormat(0), ConnectionExchange.decode(answer(validConnection()).put("format", 0).toString()))
        assertEquals(Decoded.UnsupportedFormat(null), ConnectionExchange.decode(answer(validConnection()).put("format", "1").toString()))
        assertEquals(Decoded.UnsupportedFormat(null), ConnectionExchange.decode(answer(validConnection()).apply { remove("format") }.toString()))
    }

    @Test
    fun rejectsWhatIsNoAnswer() {
        assertEquals(Decoded.Unreadable, ConnectionExchange.decode(null))
        assertEquals(Decoded.Unreadable, ConnectionExchange.decode(""))
        assertEquals(Decoded.Unreadable, ConnectionExchange.decode("not json"))
        assertEquals(Decoded.Unreadable, ConnectionExchange.decode("[]"))
        assertEquals(Decoded.Unreadable, ConnectionExchange.decode("""{"format":1}"""))
        assertEquals(Decoded.Unreadable, ConnectionExchange.decode("""{"format":1,"connections":{}}"""))
    }

    @Test
    fun skipsIncompleteEntries() {
        val invalid = listOf(
            validConnection().apply { remove("host") },
            validConnection().put("host", "  "),
            validConnection().apply { remove("username") },
            validConnection().put("username", JSONObject.NULL),
            validConnection().apply { remove("port") },
            validConnection().put("port", 0),
            validConnection().put("port", 65536),
            validConnection().put("port", 22.5),
            validConnection().put("port", "22"),
            validConnection().put("auth", "agent"),
            validConnection().apply { remove("auth") },
            validConnection().apply { remove("password") },
            validConnection().put("password", ""),
            validConnection().put("password", JSONObject.NULL),
            // the key belongs to the other way of signing in
            connection("", "h", 22, "u", "password", "privateKey" to "KEY"),
            connection("", "h", 22, "u", "key", "password" to "pw"),
            connection("", "h", 22, "u", "key", "privateKey" to "  "),
        )
        val list = JSONArray()
        invalid.forEach { list.put(it) }
        list.put("no connection")
        list.put(JSONObject.NULL)
        list.put(validConnection().put("port", 65535))

        val decoded = ConnectionExchange.decode(answer().put("connections", list).toString()) as Decoded.Connections
        assertEquals(invalid.size + 2, decoded.skipped)
        assertEquals(1, decoded.connections.size)
        assertEquals(65535, decoded.connections.single().port)
    }

    @Test
    fun takesOnlyTheSecretsOfTheAuthentication() {
        val password = connection("", "h", 22, "u", "password", "password" to "pw", "privateKey" to "KEY", "passphrase" to "satz")
        val key = connection("", "h", 22, "u", "key", "password" to "pw", "privateKey" to "KEY", "passphrase" to "satz")
        val decoded = (ConnectionExchange.decode(answer(password, key).toString()) as Decoded.Connections).connections

        assertEquals("pw", decoded[0].password)
        assertEquals("", decoded[0].privateKey)
        assertEquals("", decoded[0].passphrase)
        assertEquals("", decoded[1].password)
        assertEquals("KEY", decoded[1].privateKey)
        assertEquals("satz", decoded[1].passphrase)
    }

    @Test
    fun trimsHostAndUserButNotPasswords() {
        val json = connection(" Name ", " Host.example ", 22, " u ", "password", "password" to " pw ")
        val decoded = (ConnectionExchange.decode(answer(json).toString()) as Decoded.Connections).connections.single()
        assertEquals("Name", decoded.name)
        assertEquals("Host.example", decoded.host)
        assertEquals("u", decoded.username)
        assertEquals(" pw ", decoded.password)
    }

    @Test
    fun dropsABrokenHostKeyButKeepsTheConnection() {
        val broken = listOf(
            JSONObject().put("type", "ssh-ed25519").put("blob", "not base64!"),
            JSONObject().put("type", "ssh-ed25519").put("blob", ""),
            JSONObject().put("type", "ssh-rsa").put("blob", hostKey.blob),
            JSONObject().put("type", "ssh ed25519").put("blob", blob("ssh ed25519", 1)),
            JSONObject().put("blob", hostKey.blob),
            JSONObject().put("type", "ssh-ed25519"),
            JSONObject().put("type", "ssh-ed25519").put("blob", Base64.getEncoder().encodeToString(byteArrayOf(0, 0, 0, 99, 1))),
        )
        broken.forEach { key ->
            val decoded = ConnectionExchange.decode(answer(validConnection().put("hostKey", key)).toString()) as Decoded.Connections
            assertEquals(key.toString(), 0, decoded.skipped)
            assertNull(key.toString(), decoded.connections.single().hostKey)
        }
        val notAnObject = ConnectionExchange.decode(answer(validConnection().put("hostKey", "ssh-ed25519 AAAA")).toString()) as Decoded.Connections
        assertNull(notAnObject.connections.single().hostKey)
    }

    @Test
    fun writesTheHostKeyBlobTheWayKnownHostsDoes() {
        // an ECDSA key's blob ends in Base64 padding, which a lenient writer might have left out
        val ecdsa = SharedHostKey("ecdsa-sha2-nistp256", blob("ecdsa-sha2-nistp256", 1, size = 65))
        val unpadded = ecdsa.blob.trimEnd('=')
        if (unpadded == ecdsa.blob) fail("the test key needs padding")
        val json = validConnection().put("hostKey", JSONObject().put("type", ecdsa.type).put("blob", unpadded))
        val decoded = ConnectionExchange.decode(answer(json).toString()) as Decoded.Connections
        assertEquals(ecdsa, decoded.connections.single().hostKey)
    }

    // endregion

    @Test
    fun partnerIsTheNeedaloadBuildOfTheSameType() {
        assertEquals("de.needanapp.needaload", ConnectionExchange.partnerPackage("de.needanapp.needafile"))
        assertEquals("de.needanapp.needaload.debug", ConnectionExchange.partnerPackage("de.needanapp.needafile.debug"))
        assertTrue(ConnectionExchange.isPartner("de.needanapp.needaload"))
        assertTrue(ConnectionExchange.isPartner("de.needanapp.needaload.debug"))
        assertFalse(ConnectionExchange.isPartner("de.needanapp.needafile"))
        assertFalse(ConnectionExchange.isPartner("de.needanapp.needaload.evil"))
        assertFalse(ConnectionExchange.isPartner(null))
    }

    private fun validConnection() = connection("Server", "example.org", 22, "alice", "password", "password" to "pw")

    private fun connection(name: String, host: String, port: Int, username: String, auth: String, vararg secrets: Pair<String, String>): JSONObject {
        val json = JSONObject().put("name", name).put("host", host).put("port", port).put("username", username).put("auth", auth)
        secrets.forEach { (key, value) -> json.put(key, value) }
        return json
    }

    private fun answer(vararg connections: JSONObject): JSONObject {
        val list = JSONArray()
        connections.forEach { list.put(it) }
        return JSONObject().put("format", 1).put("app", "needaload").put("connections", list)
    }

    companion object {
        /** A public key blob in SSH wire format, Base64: the type, then [size] key bytes made from [seed]. */
        fun blob(type: String, seed: Int, size: Int = ED25519_KEY_SIZE): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(type.length)
                out.write(type.toByteArray(Charsets.US_ASCII))
                out.writeInt(size)
                out.write(ByteArray(size) { (it * seed).toByte() })
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray())
        }

        private const val ED25519_KEY_SIZE = 32
    }
}
