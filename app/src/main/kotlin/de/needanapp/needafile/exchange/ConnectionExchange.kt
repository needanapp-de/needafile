package de.needanapp.needafile.exchange

import de.needanapp.needafile.remote.AUTH_KEY
import de.needanapp.needafile.remote.RemoteServer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Exchanging SFTP connections with needaload, in both directions and only with the consent of the giving app: the taking
 * app starts [ACTION_PICK_CONNECTIONS] in the other one for a result, the giving one shows a choice and answers with
 * [EXTRA_CONNECTIONS], JSON in format [FORMAT]. Guarded by a permission with protection level signature, whose name
 * differs per build type (`sharePermission` in build.gradle.kts), and a signature check in code. needaload implements
 * the same interface; a change here needs the same change there.
 *
 * Plain Kotlin besides org.json, so the unit tests run on the computer.
 */
object ConnectionExchange {
    const val ACTION_PICK_CONNECTIONS = "de.needanapp.action.PICK_CONNECTIONS"
    const val EXTRA_CONNECTIONS = "de.needanapp.extra.CONNECTIONS"
    const val FORMAT = 1
    const val APP = "needafile"

    private const val PARTNER = "de.needanapp.needaload"
    private const val DEBUG_SUFFIX = ".debug"
    private const val AUTH_PASSWORD_NAME = "password"
    private const val AUTH_KEY_NAME = "key"
    private const val LENGTH_FIELD_SIZE = 4

    // an SSH key type such as ssh-ed25519, ecdsa-sha2-nistp256 or ssh-rsa
    private val HOST_KEY_TYPE = Regex("[A-Za-z0-9@._-]{1,64}")

    /** Release builds exchange with release builds, debug with debug ones: the two are signed with different keys. */
    fun partnerPackage(ownPackage: String) = PARTNER + if (ownPackage.endsWith(DEBUG_SUFFIX)) DEBUG_SUFFIX else ""

    /** Whether [packageName] is needaload in one of its builds; its signature has to be checked as well. */
    fun isPartner(packageName: String?) = packageName == PARTNER || packageName == PARTNER + DEBUG_SUFFIX

    /** The answer for needaload; only complete connections are written, empty fields are left out. */
    fun encode(connections: List<SharedConnection>): String {
        val list = JSONArray()
        connections.filter { it.isComplete }.forEach { connection ->
            val json = JSONObject()
                .putIfNotEmpty("name", connection.name)
                .put("host", connection.host)
                .put("port", connection.port)
                .put("username", connection.username)
            when (connection.auth) {
                SharedConnection.Auth.PASSWORD -> json.put("auth", AUTH_PASSWORD_NAME).putIfNotEmpty("password", connection.password)
                SharedConnection.Auth.KEY -> json.put("auth", AUTH_KEY_NAME)
                    .putIfNotEmpty("privateKey", connection.privateKey)
                    .putIfNotEmpty("passphrase", connection.passphrase)
            }
            connection.hostKey?.let { json.put("hostKey", JSONObject().put("type", it.type).put("blob", it.blob)) }
            list.put(json)
        }
        return JSONObject().put("format", FORMAT).put("app", APP).put("connections", list).toString()
    }

    /** What needaload answered. */
    sealed interface Decoded {
        /** The usable connections; [skipped] counts the entries that lacked something or weren't a connection at all. */
        data class Connections(val connections: List<SharedConnection>, val skipped: Int) : Decoded

        /** Another format than [FORMAT] (null: none given), e.g. from a newer version of needaload. */
        data class UnsupportedFormat(val format: Int?) : Decoded

        /** No answer of this interface at all. */
        data object Unreadable : Decoded
    }

    /** Reads an answer leniently: unknown fields are ignored, incomplete entries skipped, a broken host key dropped. */
    fun decode(text: String?): Decoded {
        val root = try {
            JSONObject(text ?: return Decoded.Unreadable)
        } catch (e: JSONException) {
            return Decoded.Unreadable
        }
        val format = root.wholeNumber("format")
        if (format != FORMAT) return Decoded.UnsupportedFormat(format)
        val list = root.opt("connections") as? JSONArray ?: return Decoded.Unreadable
        var skipped = 0
        val connections = (0 until list.length()).mapNotNull { index ->
            connection(list.opt(index) as? JSONObject).also { if (it == null) skipped++ }
        }
        return Decoded.Connections(connections, skipped)
    }

    private fun connection(json: JSONObject?): SharedConnection? {
        if (json == null) return null
        val auth = when (json.text("auth")) {
            AUTH_PASSWORD_NAME -> SharedConnection.Auth.PASSWORD
            AUTH_KEY_NAME -> SharedConnection.Auth.KEY
            else -> return null
        }
        val isKey = auth == SharedConnection.Auth.KEY
        return SharedConnection(
            name = json.text("name").trim(),
            host = json.text("host").trim(),
            port = json.wholeNumber("port") ?: return null,
            username = json.text("username").trim(),
            auth = auth,
            password = if (isKey) "" else json.text("password"),
            privateKey = if (isKey) json.text("privateKey").trim() else "",
            passphrase = if (isKey) json.text("passphrase") else "",
            hostKey = (json.opt("hostKey") as? JSONObject)?.let(::hostKey),
        ).takeIf { it.isComplete }
    }

    /** A broken host key is left out; then the user confirms the key on the first connection, as usual. */
    private fun hostKey(json: JSONObject): SharedHostKey? {
        val type = json.text("type").takeIf { HOST_KEY_TYPE.matches(it) } ?: return null
        val blob = try {
            Base64.getDecoder().decode(json.text("blob"))
        } catch (e: IllegalArgumentException) {
            return null
        }
        // the blob starts with its own key type; a key that doesn't match would only show up later as a changed one
        if (blobType(blob) != type) return null
        // written the way KnownHosts stores keys, so equal keys compare equal
        return SharedHostKey(type, Base64.getEncoder().encodeToString(blob))
    }

    /** The key type an SSH public key blob starts with: a 32-bit length, then the name. */
    private fun blobType(blob: ByteArray): String? {
        if (blob.size < LENGTH_FIELD_SIZE) return null
        val length = ByteBuffer.wrap(blob, 0, LENGTH_FIELD_SIZE).int
        if (length <= 0 || length > blob.size - LENGTH_FIELD_SIZE) return null
        return String(blob, LENGTH_FIELD_SIZE, length, Charsets.US_ASCII)
    }

    // a string value only; JSON null, numbers or objects count as missing (optString would turn null into "null")
    private fun JSONObject.text(key: String) = opt(key) as? String ?: ""

    private fun JSONObject.wholeNumber(key: String): Int? {
        val value = (opt(key) as? Number)?.toDouble() ?: return null
        return value.takeIf { it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
    }

    private fun JSONObject.putIfNotEmpty(key: String, value: String) = if (value.isEmpty()) this else put(key, value)
}

/**
 * One SFTP connection as it is exchanged. [password] belongs to [Auth.PASSWORD], [privateKey] (PEM or OpenSSH text) and
 * [passphrase] to [Auth.KEY]; [hostKey] is the key the user confirmed for host and port, if any.
 */
data class SharedConnection(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val auth: Auth,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val hostKey: SharedHostKey? = null,
) {
    enum class Auth { PASSWORD, KEY }

    /** Host, user, a valid port and the secret its [auth] needs; anything less is neither passed on nor taken over. */
    val isComplete: Boolean
        get() = host.isNotBlank() && username.isNotBlank() && port in VALID_PORTS && when (auth) {
            Auth.PASSWORD -> password.isNotEmpty()
            Auth.KEY -> privateKey.isNotBlank()
        }

    // keeps passwords and keys out of logs and crash reports
    override fun toString() = "SharedConnection(name=$name, host=$host, port=$port, username=$username, auth=$auth, hostKey=${hostKey?.type})"

    private companion object {
        val VALID_PORTS = 1..65535
    }
}

/** A confirmed host key: its type and the Base64 key blob in SSH wire format, as KnownHosts stores it. */
data class SharedHostKey(val type: String, val blob: String)

/** This SFTP server as it is passed on, with the host key confirmed for it ([hostKey]) if there is one. */
fun RemoteServer.toShared(hostKey: SharedHostKey?): SharedConnection {
    val usesKey = authType == AUTH_KEY
    return SharedConnection(
        name = name.trim(),
        host = host.trim(),
        port = port,
        username = username.trim(),
        auth = if (usesKey) SharedConnection.Auth.KEY else SharedConnection.Auth.PASSWORD,
        password = if (usesKey) "" else password,
        privateKey = if (usesKey) privateKey.trim() else "",
        passphrase = if (usesKey) passphrase else "",
        hostKey = hostKey,
    )
}
