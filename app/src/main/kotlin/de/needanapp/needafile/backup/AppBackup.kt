package de.needanapp.needafile.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.StringRes
import de.needanapp.design.theme.ThemeSettingsStore
import de.needanapp.needafile.BuildConfig
import de.needanapp.needafile.R
import de.needanapp.needafile.files.serverIdOf
import de.needanapp.needafile.remote.RemoteConnections
import de.needanapp.needafile.remote.RemoteServersStore
import de.needanapp.needafile.security.KnownCertificates
import de.needanapp.needafile.security.KnownHosts
import de.needanapp.needafile.settings.SettingKeys
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.transfer.TransferClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** The parts of a backup; each one is restored on its own and then replaces what was there. */
enum class BackupSection(val key: String, @StringRes val label: Int, @StringRes val summary: Int) {
    APPEARANCE("appearance", R.string.backup_section_appearance, R.string.backup_section_appearance_summary),
    SETTINGS("settings", R.string.backup_section_settings, R.string.backup_section_settings_summary),
    SERVERS("servers", R.string.backup_section_servers, R.string.backup_section_servers_summary),
}

/** A decrypted backup, ready to be restored section by section. */
class BackupContents internal constructor(internal val json: JSONObject) {
    val created = json.optLong("created")
    val appVersion: String = json.optString("appVersion")
    val sections = BackupSection.entries.filter { json.optJSONObject(it.key) != null }
    val serverCount = json.optJSONObject(BackupSection.SERVERS.key)?.optJSONArray(SERVER_LIST)?.length() ?: 0
}

/**
 * Saves the app's settings into a password-protected file and restores them: the appearance (from the design
 * package), the settings, and the servers with their passwords and the host keys and certificates
 * the user confirmed. The app protection is left out on purpose, a backup shouldn't be a way around it.
 */
object AppBackup {
    suspend fun export(context: Context, sections: Set<BackupSection>, password: CharArray, target: Uri) {
        val json = JSONObject()
            .put("app", APP_MARKER)
            .put("format", FORMAT)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("created", System.currentTimeMillis())
        if (BackupSection.APPEARANCE in sections) json.put(BackupSection.APPEARANCE.key, ThemeSettingsStore(context).exportBackup())
        withContext(Dispatchers.IO) {
            if (BackupSection.SETTINGS in sections) json.put(BackupSection.SETTINGS.key, exportSettings(context))
            if (BackupSection.SERVERS in sections) json.put(BackupSection.SERVERS.key, exportServers(context))
        }
        withContext(Dispatchers.Default) {
            val encrypted = BackupCrypto.encrypt(json.toString().toByteArray(), password)
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Can't write $target")
                output.use { it.write(encrypted) }
            }
        }
    }

    /** Whether [source] starts like a backup, checked before its password is asked for. */
    suspend fun looksLikeBackup(context: Context, source: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                val start = ByteArray(BackupCrypto.MAGIC_SIZE)
                var read = 0
                while (read < start.size) {
                    val count = input.read(start, read, start.size - read)
                    if (count < 0) break
                    read += count
                }
                BackupCrypto.hasBackupHeader(start.copyOf(read))
            }
        }.getOrNull() == true
    }

    /** Reads and decrypts the backup in [source]; throws a [BackupException] if it isn't one or the password is wrong. */
    suspend fun read(context: Context, source: Uri, password: CharArray): BackupContents = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(source) ?: throw IOException("Can't read $source")
        val file = input.use { it.readLimited(MAX_FILE_SIZE) } ?: throw BackupException(R.string.backup_error_not_a_backup)
        val content = withContext(Dispatchers.Default) { BackupCrypto.decrypt(file, password) }
        val json = try {
            JSONObject(content.decodeToString())
        } catch (e: JSONException) {
            throw BackupException(R.string.backup_error_damaged)
        }
        if (json.optString("app") != APP_MARKER) throw BackupException(R.string.backup_error_not_a_backup)
        if (json.optInt("format") > FORMAT) throw BackupException(R.string.backup_error_newer_version)
        BackupContents(json)
    }

    /** Replaces the chosen [sections] completely by those of the backup; to be called from the main thread. */
    suspend fun restore(context: Context, contents: BackupContents, sections: Set<BackupSection>) {
        val json = contents.json
        if (BackupSection.APPEARANCE in sections) {
            json.optJSONObject(BackupSection.APPEARANCE.key)?.let { ThemeSettingsStore(context).importBackup(it) }
        }
        withContext(Dispatchers.IO) {
            if (BackupSection.SETTINGS in sections) json.optJSONObject(BackupSection.SETTINGS.key)?.let { importSettings(context, it) }
            if (BackupSection.SERVERS in sections) json.optJSONObject(BackupSection.SERVERS.key)?.let { importServers(context, it) }
        }
        // copied server files may be on servers that are gone now
        if (BackupSection.SERVERS in sections) TransferClipboard.clear()
    }

    // region settings

    private fun exportSettings(context: Context): JSONObject {
        val all = context.config.prefs.all
        val result = JSONObject()
        SettingType.entries.forEach { type ->
            val values = JSONObject()
            all.forEach { (key, value) ->
                if (type.accepts(key) && type.isValue(value)) values.put(key, value)
            }
            result.put(type.key, values)
        }
        return result
    }

    /** Removes all settings a backup may hold, then writes those of the backup; anything else in it is ignored. */
    private fun importSettings(context: Context, json: JSONObject) {
        val prefs = context.config.prefs
        val editor = prefs.edit()
        prefs.all.keys.filter { key -> SettingType.entries.any { it.accepts(key) } }.forEach { editor.remove(it) }
        SettingType.entries.forEach { type ->
            val values = json.optJSONObject(type.key) ?: return@forEach
            values.keys().forEach { key ->
                if (type.accepts(key)) runCatching { type.put(editor, key, values.get(key)) }
            }
        }
        editor.commit()
    }

    /**
     * The settings in a backup, by the type they are stored with: the names they have in the app's preferences, and
     * prefixes for the per-folder ones. Device specific things (storage paths, root, tabs) and the protections stay out.
     */
    private enum class SettingType(val key: String, val names: Set<String>, val prefixes: List<String> = emptyList()) {
        BOOLEAN(
            "boolean",
            setOf(
                SettingKeys.SHOW_HIDDEN, SettingKeys.BACK_TWICE, SettingKeys.DELETE_WITHOUT_ASKING, SettingKeys.CLOCK_24, SettingKeys.GRID_NAMES, SettingKeys.CARDS,
                SettingKeys.AUTO_GALLERY, SettingKeys.VAULT_LOCK_SCREEN_OFF, SettingKeys.SWIPE_PAGES,
            ),
        ),
        STRING("string", setOf(SettingKeys.DATE_PATTERN, SettingKeys.HOME_FOLDER, SettingKeys.APP_ICON)),
        INT(
            "int",
            setOf(SettingKeys.VIEW, SettingKeys.SORTING, SettingKeys.GROUPING, SettingKeys.VAULT_LOCK_MINUTES),
            listOf(SettingKeys.FOLDER_SORTING, SettingKeys.FOLDER_VIEW, SettingKeys.FOLDER_ZOOM, SettingKeys.FOLDER_GROUPING, SettingKeys.ZOOM),
        ),
        FLOAT("float", setOf(SettingKeys.EDITOR_ZOOM));

        fun accepts(name: String) = name in names || prefixes.any { name.startsWith(it) && name.length <= MAX_TEXT_LENGTH }

        fun isValue(value: Any?) = when (this) {
            BOOLEAN -> value is Boolean
            STRING -> value is String
            INT -> value is Int
            FLOAT -> value is Float
        }

        fun put(editor: SharedPreferences.Editor, name: String, value: Any) {
            when (this) {
                BOOLEAN -> editor.putBoolean(name, value as Boolean)
                STRING -> editor.putString(name, (value as String).take(MAX_TEXT_LENGTH))
                INT -> editor.putInt(name, value as Int)
                FLOAT -> editor.putFloat(name, (value as Number).toFloat())
            }
        }
    }

    // endregion

    // region servers

    private fun exportServers(context: Context) = JSONObject()
        .put(SERVER_LIST, RemoteServersStore.exportPlain(context))
        .put(KNOWN_HOSTS, exportEntries(context, KnownHosts.PREFS_NAME))
        .put(KNOWN_CERTIFICATES, exportEntries(context, KnownCertificates.PREFS_NAME))

    private fun importServers(context: Context, json: JSONObject) {
        RemoteConnections.closeAll()
        RemoteServersStore.importPlain(context, json.optJSONArray(SERVER_LIST) ?: JSONArray())
        importEntries(context, KnownHosts.PREFS_NAME, json.optJSONObject(KNOWN_HOSTS))
        importEntries(context, KnownCertificates.PREFS_NAME, json.optJSONObject(KNOWN_CERTIFICATES))

        // tabs may show servers that are gone now
        val serverIds = RemoteServersStore.getServers(context).map { it.id }.toSet()
        val config = context.config
        val tabs = config.fileTabs
        val activeTab = tabs.getOrNull(config.activeFileTab)
        val keptTabs = tabs.filter { tab -> serverIdOf(tab)?.let { it in serverIds } ?: true }.ifEmpty { listOf(config.homeFolder) }
        config.fileTabs = keptTabs
        config.activeFileTab = keptTabs.indexOf(activeTab).coerceAtLeast(0)
    }

    /** The host keys or certificates the user confirmed, one string per "host:port". */
    private fun exportEntries(context: Context, prefsName: String): JSONObject {
        val result = JSONObject()
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            if (value is String) result.put(key, value)
        }
        return result
    }

    private fun importEntries(context: Context, prefsName: String, json: JSONObject?) {
        val editor = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear()
        json?.keys()?.forEach { key ->
            val value = json.opt(key)
            if (value is String && HOST_PORT.matches(key) && value.length <= MAX_ENTRY_LENGTH) editor.putString(key, value)
        }
        editor.commit()
    }

    // endregion

    private fun InputStream.readLimited(limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) return output.toByteArray()
            output.write(buffer, 0, read)
            if (output.size() > limit) return null
        }
    }

    private const val APP_MARKER = "needafile"
    private const val FORMAT = 1
    private const val KNOWN_HOSTS = "knownHosts"
    private const val KNOWN_CERTIFICATES = "knownCertificates"
    private const val MAX_FILE_SIZE = BackupCrypto.MAX_CONTENT_SIZE
    private const val MAX_TEXT_LENGTH = 4096
    private const val MAX_ENTRY_LENGTH = 64 * 1024
    private const val BUFFER_SIZE = 64 * 1024
    private val HOST_PORT = Regex("""\S+:\d{1,5}""")
}

private const val SERVER_LIST = "list"
