package de.needanapp.needafile.vault

import android.content.Context
import de.needanapp.needafile.remote.getRemoteServerId
import de.needanapp.needafile.remote.isRemotePath
import org.json.JSONArray
import org.json.JSONObject

/**
 * A vault that was unlocked once, so it can be found again in the location list. Only where it lies is kept, never
 * its password; [fingerprintSecret] is the password encrypted with a key that only a fingerprint unlocks (see
 * [VaultBiometrics]), when the user chose that.
 */
data class SavedVault(val location: String, val fingerprintSecret: String? = null, val fingerprintIv: String? = null) {
    val name get() = location.trimEnd('/').substringAfterLast('/')
    val hasFingerprint get() = fingerprintSecret != null && fingerprintIv != null
}

/** The remembered vaults, in the order they were first unlocked. */
object VaultStore {
    private const val PREFS_NAME = "vaults"
    private const val KEY_LIST = "list"
    private const val LOCATION = "location"
    private const val SECRET = "fingerprintSecret"
    private const val IV = "fingerprintIv"

    @Synchronized
    fun all(context: Context): List<SavedVault> {
        val json = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                SavedVault(entry.getString(LOCATION), entry.optString(SECRET).ifEmpty { null }, entry.optString(IV).ifEmpty { null })
            }
        }.getOrDefault(emptyList())
    }

    fun get(context: Context, location: String) = all(context).firstOrNull { it.location == location }

    /** Adds the vault at [location] to the list, if it isn't there yet. */
    @Synchronized
    fun remember(context: Context, location: String) {
        val vaults = all(context)
        if (vaults.none { it.location == location }) save(context, vaults + SavedVault(location))
    }

    @Synchronized
    fun forget(context: Context, location: String) {
        save(context, all(context).filterNot { it.location == location })
    }

    /** Forgets the vaults on the server [serverId], e.g. when the server was deleted. */
    @Synchronized
    fun forgetServer(context: Context, serverId: String): List<String> {
        val (gone, kept) = all(context).partition { it.location.isRemotePath() && it.location.getRemoteServerId() == serverId }
        if (gone.isNotEmpty()) save(context, kept)
        return gone.map { it.location }
    }

    /** Stores the password behind the fingerprint ([secret] null forgets it); the vault joins the list if needed. */
    @Synchronized
    fun setFingerprint(context: Context, location: String, secret: String?, iv: String?) {
        val vaults = all(context)
        if (vaults.none { it.location == location }) {
            if (secret != null) save(context, vaults + SavedVault(location, secret, iv))
            return
        }
        save(context, vaults.map { if (it.location == location) it.copy(fingerprintSecret = secret, fingerprintIv = iv) else it })
    }

    private fun save(context: Context, vaults: List<SavedVault>) {
        val array = JSONArray()
        vaults.forEach { vault ->
            array.put(JSONObject().put(LOCATION, vault.location).apply {
                vault.fingerprintSecret?.let { put(SECRET, it) }
                vault.fingerprintIv?.let { put(IV, it) }
            })
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
