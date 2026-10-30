package de.needanapp.needafile.remote

import android.content.Context
import de.needanapp.needafile.security.CryptoHelper
import de.needanapp.needafile.settings.config
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads/saves the list of [RemoteServer]s as JSON in the app config. Secret fields are
 * encrypted with [CryptoHelper] before persisting and decrypted on load.
 */
object RemoteServersStore {
    fun getServers(context: Context): ArrayList<RemoteServer> = parse(context.config.remoteServersJson, CryptoHelper::decrypt)

    fun saveServers(context: Context, servers: List<RemoteServer>) {
        context.config.remoteServersJson = toJson(servers, CryptoHelper::encrypt).toString()
    }

    /** The servers with their secrets in plain text, for a backup that is encrypted as a whole. */
    fun exportPlain(context: Context): JSONArray = toJson(getServers(context)) { it }

    /** Replaces all servers by those of a backup made by [exportPlain]. */
    fun importPlain(context: Context, servers: JSONArray) = saveServers(context, parse(servers.toString()) { it })

    private fun parse(json: String, reveal: (String) -> String): ArrayList<RemoteServer> {
        val result = ArrayList<RemoteServer>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                result.add(
                    RemoteServer(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        host = o.optString("host"),
                        port = o.optInt("port", 22),
                        username = o.optString("username"),
                        authType = o.optInt("authType", AUTH_PASSWORD),
                        password = reveal(o.optString("password")),
                        privateKey = reveal(o.optString("privateKey")),
                        passphrase = reveal(o.optString("passphrase")),
                        initialPath = o.optString("initialPath", "/"),
                        type = o.optString("type", SERVER_TYPE_SFTP),
                        domain = o.optString("domain"),
                        share = o.optString("share"),
                        url = o.optString("url"),
                        ftpTls = o.optInt("ftpTls", FTP_TLS_EXPLICIT),
                        allowPlain = o.optBoolean("allowPlain", false),
                    )
                )
            }
        } catch (e: Exception) {
            // corrupt/empty -> return whatever parsed so far
        }
        return result
    }

    private fun toJson(servers: List<RemoteServer>, protect: (String) -> String): JSONArray {
        val array = JSONArray()
        servers.forEach { s ->
            val o = JSONObject()
            o.put("id", s.id)
            o.put("name", s.name)
            o.put("host", s.host)
            o.put("port", s.port)
            o.put("username", s.username)
            o.put("authType", s.authType)
            o.put("password", protect(s.password))
            o.put("privateKey", protect(s.privateKey))
            o.put("passphrase", protect(s.passphrase))
            o.put("initialPath", s.initialPath)
            o.put("type", s.type)
            o.put("domain", s.domain)
            o.put("share", s.share)
            o.put("url", s.url)
            o.put("ftpTls", s.ftpTls)
            o.put("allowPlain", s.allowPlain)
            array.put(o)
        }
        return array
    }

    fun upsert(context: Context, server: RemoteServer) {
        val servers = getServers(context)
        val index = servers.indexOfFirst { it.id == server.id }
        if (index >= 0) {
            servers[index] = server
        } else {
            servers.add(server)
        }
        saveServers(context, servers)
    }

    /**
     * Appends [servers] to the saved ones, which stay exactly as stored: their secrets aren't decrypted and encrypted
     * again, so one the Keystore can't read any more isn't lost. A stored list that can't be read throws instead of
     * being replaced.
     */
    fun add(context: Context, servers: List<RemoteServer>) {
        if (servers.isEmpty()) return
        val stored = JSONArray(context.config.remoteServersJson)
        val added = toJson(servers, CryptoHelper::encrypt)
        for (i in 0 until added.length()) stored.put(added.get(i))
        context.config.remoteServersJson = stored.toString()
    }

    fun delete(context: Context, serverId: String) {
        val servers = getServers(context)
        servers.removeAll { it.id == serverId }
        saveServers(context, servers)
    }

    fun getServer(context: Context, serverId: String) = getServers(context).firstOrNull { it.id == serverId }
}
