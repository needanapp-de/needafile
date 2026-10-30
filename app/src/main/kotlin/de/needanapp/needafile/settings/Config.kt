package de.needanapp.needafile.settings

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateFormat
import de.needanapp.needafile.archive.ArchiveKind
import de.needanapp.needafile.archive.CompressOptions
import de.needanapp.needafile.archive.CompressionStrength
import de.needanapp.needafile.archive.ZIP_PART_SIZES
import de.needanapp.needafile.core.Sorting
import de.needanapp.needafile.core.ViewType
import de.needanapp.needafile.core.defaultDatePattern
import de.needanapp.needafile.core.internalStorage
import de.needanapp.needafile.files.RecentFolder
import de.needanapp.needafile.remote.migrateLegacyRemotePath
import de.needanapp.needafile.system.LauncherIcon
import de.needanapp.needafile.vault.getVaultLocation
import de.needanapp.needafile.vault.isVaultPath
import de.needanapp.needafile.vault.vaultRootPath
import java.io.File
import java.util.Locale
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import net.lingala.zip4j.model.enums.AesKeyStrength
import org.json.JSONArray
import org.json.JSONObject

val Context.config: Config get() = Config(applicationContext)

/**
 * needafile's settings, kept in one preferences file. Settings that can differ per folder (sorting, view, zoom,
 * grouping) are stored under the folder's [key][folderKey], falling back to the general setting.
 */
class Config(private val context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)

    init {
        moveServersFromEarlierFile()
    }

    // region showing files

    var showHidden by flag(SettingKeys.SHOW_HIDDEN, false)

    var sorting by number(SettingKeys.SORTING, Sorting.DEFAULT)

    fun getFolderSorting(path: String) = prefs.getInt(folderSetting(SettingKeys.FOLDER_SORTING, path), sorting)

    /** Sorting for [path] only; an empty path changes the general sorting. */
    fun saveCustomSorting(path: String, value: Int) {
        if (path.isEmpty()) sorting = value else prefs.edit().putInt(folderSetting(SettingKeys.FOLDER_SORTING, path), value).apply()
    }

    fun removeCustomSorting(path: String) = prefs.edit().remove(folderSetting(SettingKeys.FOLDER_SORTING, path)).apply()

    fun hasCustomSorting(path: String) = prefs.contains(folderSetting(SettingKeys.FOLDER_SORTING, path))

    var viewType by number(SettingKeys.VIEW, ViewType.DETAILS)

    fun getFolderViewType(path: String) = prefs.getInt(folderSetting(SettingKeys.FOLDER_VIEW, path), viewType)

    /** View type for [path] only; an empty path changes the general one. */
    fun saveFolderViewType(path: String, value: Int) {
        if (path.isEmpty()) viewType = value else prefs.edit().putInt(folderSetting(SettingKeys.FOLDER_VIEW, path), value).apply()
    }

    /** Back to the general view for [path]: its own view type, zoom level and grouping go. */
    fun removeFolderViewType(path: String) {
        val editor = prefs.edit()
        listOf(SettingKeys.FOLDER_VIEW, SettingKeys.FOLDER_ZOOM, SettingKeys.FOLDER_GROUPING).forEach { editor.remove(folderSetting(it, path)) }
        editor.apply()
    }

    fun hasCustomViewType(path: String) = prefs.contains(folderSetting(SettingKeys.FOLDER_VIEW, path))

    /** How big the entries of [viewType] are, 0 = smallest; see FolderView. */
    fun getZoom(viewType: Int, default: Int) = prefs.getInt(SettingKeys.ZOOM + viewType, default)

    fun setZoom(viewType: Int, value: Int) = prefs.edit().putInt(SettingKeys.ZOOM + viewType, value).apply()

    /** The zoom level of a folder with its own view, the general one of its view type otherwise. */
    fun getFolderZoom(path: String, viewType: Int, default: Int) =
        prefs.getInt(folderSetting(SettingKeys.FOLDER_ZOOM, path), getZoom(viewType, default))

    fun saveFolderZoom(path: String, value: Int) = prefs.edit().putInt(folderSetting(SettingKeys.FOLDER_ZOOM, path), value).apply()

    /** How entries are grouped under headings, a GroupBy id; 0 = not at all. */
    var groupBy by number(SettingKeys.GROUPING, 0)

    fun getFolderGroupBy(path: String) = prefs.getInt(folderSetting(SettingKeys.FOLDER_GROUPING, path), groupBy)

    fun saveFolderGroupBy(path: String, value: Int) = prefs.edit().putInt(folderSetting(SettingKeys.FOLDER_GROUPING, path), value).apply()

    /** Folders holding mostly pictures and videos open in the gallery unless they have a view of their own. */
    var autoGallery by flag(SettingKeys.AUTO_GALLERY, true)

    /** The grid shows names below the pictures. */
    var displayFilenames by flag(SettingKeys.GRID_NAMES, true)

    /** Every view shows each entry as a card of its own. */
    var listAsCards by flag(SettingKeys.CARDS, true)

    /** A date pattern, one of DATE_PATTERNS; by default the one closest to the phone's. */
    var dateFormat: String
        get() = prefs.getString(SettingKeys.DATE_PATTERN, null) ?: context.defaultDatePattern()
        set(value) = prefs.edit().putString(SettingKeys.DATE_PATTERN, value).apply()

    var use24HourFormat: Boolean
        get() = prefs.getBoolean(SettingKeys.CLOCK_24, DateFormat.is24HourFormat(context))
        set(value) = prefs.edit().putBoolean(SettingKeys.CLOCK_24, value).apply()

    // endregion

    // region places

    /** Where the files tab starts; internal storage when the folder is gone. */
    var homeFolder: String
        get() = prefs.getString(SettingKeys.HOME_FOLDER, null)?.takeIf { File(it).isDirectory } ?: context.internalStorage
        set(value) = prefs.edit().putString(SettingKeys.HOME_FOLDER, value).apply()


    /** The folder copied into last time; for a folder inside a vault the vault itself, never a decrypted path. */
    var lastCopyPath: String
        get() = prefs.getString(SettingKeys.LAST_COPY_TARGET, "").orEmpty()
        set(value) = prefs.edit().putString(SettingKeys.LAST_COPY_TARGET, if (value.isVaultPath()) value.getVaultLocation() else value).apply()

    /** Folders open in the pill tabs of the files tab, local paths or remote ones (see RemotePaths). */
    var fileTabs: List<String>
        get() {
            val array = JSONArray(prefs.getString(SettingKeys.TABS, "[]"))
            return List(array.length()) { array.getString(it).migrateLegacyRemotePath() }
        }
        set(tabs) = prefs.edit().putString(SettingKeys.TABS, JSONArray(tabs).toString()).apply()

    var activeFileTab by number(SettingKeys.ACTIVE_TAB, 0)

    /** Folders opened lately, newest first; the file browser never records one inside a vault. */
    var recentFolders: List<RecentFolder>
        get() = runCatching {
            val array = JSONArray(prefs.getString(SettingKeys.RECENT_FOLDERS, "[]"))
            List(array.length()) { array.getJSONObject(it).let { entry -> RecentFolder(entry.getString("path"), entry.getLong("time")) } }
        }.getOrDefault(emptyList())
        set(folders) {
            val array = JSONArray(folders.map { JSONObject().put("path", it.path).put("time", it.time) })
            prefs.edit().putString(SettingKeys.RECENT_FOLDERS, array.toString()).apply()
        }

    // endregion

    // region behavior

    var pressBackTwice by flag(SettingKeys.BACK_TWICE, true)

    /** The introduction after installing was gone through or skipped. */
    var onboardingDone by flag(SettingKeys.ONBOARDING_DONE, false)

    /** A swipe to the side changes the page of the pill, as a tap on it does. */
    var swipePages by flag(SettingKeys.SWIPE_PAGES, true)

    var skipDeleteConfirmation by flag(SettingKeys.DELETE_WITHOUT_ASKING, false)

    /** Whether the phone is rooted, found out at every start. */
    var isRootAvailable by flag(SettingKeys.ROOTED, false)

    var enableRootAccess by flag(SettingKeys.ROOT_ACCESS, false)

    var editorTextZoom: Float
        get() = prefs.getFloat(SettingKeys.EDITOR_ZOOM, DEFAULT_EDITOR_TEXT_ZOOM)
        set(value) = prefs.edit().putFloat(SettingKeys.EDITOR_ZOOM, value).apply()

    var lastRenamePatternUsed: String
        get() = prefs.getString(SettingKeys.RENAME_PATTERN, "").orEmpty()
        set(value) = prefs.edit().putString(SettingKeys.RENAME_PATTERN, value).apply()

    /** The options of the last archive created (never its password), offered again for the next one. */
    var compressOptions: CompressOptions
        get() {
            val defaults = CompressOptions()
            return CompressOptions(
                format = enumOrNull<ArchiveKind>(prefs.getString(SettingKeys.COMPRESS_FORMAT, null))?.takeIf { it in COMPRESS_FORMATS } ?: defaults.format,
                isProtected = prefs.getBoolean(SettingKeys.COMPRESS_PROTECTED, defaults.isProtected),
                encryptFileNames = prefs.getBoolean(SettingKeys.COMPRESS_HIDE_NAMES, defaults.encryptFileNames),
                strength = enumOrNull<CompressionStrength>(prefs.getString(SettingKeys.COMPRESS_STRENGTH, null)) ?: defaults.strength,
                aesKeyStrength = enumOrNull<AesKeyStrength>(prefs.getString(SettingKeys.COMPRESS_KEY_SIZE, null))
                    ?.takeIf { it != AesKeyStrength.KEY_STRENGTH_192 } ?: defaults.aesKeyStrength,
                isSplit = prefs.getBoolean(SettingKeys.COMPRESS_SPLIT, defaults.isSplit),
                partSize = prefs.getLong(SettingKeys.COMPRESS_PART_SIZE, defaults.partSize).takeIf { it in ZIP_PART_SIZES } ?: defaults.partSize,
                isSolid = prefs.getBoolean(SettingKeys.COMPRESS_SOLID, defaults.isSolid),
            )
        }
        set(value) = prefs.edit()
            .putString(SettingKeys.COMPRESS_FORMAT, value.format.name)
            .putBoolean(SettingKeys.COMPRESS_PROTECTED, value.isProtected)
            .putBoolean(SettingKeys.COMPRESS_HIDE_NAMES, value.encryptFileNames)
            .putString(SettingKeys.COMPRESS_STRENGTH, value.strength.name)
            .putString(SettingKeys.COMPRESS_KEY_SIZE, value.aesKeyStrength.name)
            .putBoolean(SettingKeys.COMPRESS_SPLIT, value.isSplit)
            .putLong(SettingKeys.COMPRESS_PART_SIZE, value.partSize)
            .putBoolean(SettingKeys.COMPRESS_SOLID, value.isSolid)
            .apply()

    /** Raw JSON list of saved servers; secrets inside are encrypted (see RemoteServersStore). */
    var remoteServersJson: String
        get() = prefs.getString(SettingKeys.SERVERS, "[]").orEmpty()
        set(value) = prefs.edit().putString(SettingKeys.SERVERS, value).apply()

    /** The launcher icon's color: an [de.needanapp.needafile.system.AppIconColor] name or "accent" to follow the accent color. */
    var appIconChoice: String
        get() = prefs.getString(SettingKeys.APP_ICON, LauncherIcon.FOLLOW_ACCENT).orEmpty()
        set(value) = prefs.edit().putString(SettingKeys.APP_ICON, value).apply()

    // endregion

    // region vaults

    /** Minutes in the background after which unlocked vaults lock; 0 locks right away, a negative value never. */
    var vaultLockMinutes by number(SettingKeys.VAULT_LOCK_MINUTES, DEFAULT_VAULT_LOCK_MINUTES)

    var vaultLockOnScreenOff by flag(SettingKeys.VAULT_LOCK_SCREEN_OFF, true)

    // endregion

    /**
     * Where a folder's own setting [kind] is stored. Inside a vault all folders share the vault's entry, so no
     * decrypted folder names end up in the settings.
     */
    private fun folderSetting(kind: String, path: String): String {
        val folder = if (path.isVaultPath()) vaultRootPath(path.getVaultLocation()) else path
        return kind + folder.lowercase(Locale.ROOT)
    }

    /** Saved servers of versions before 0.75.0 lived in another file; they move over once. */
    private fun moveServersFromEarlierFile() {
        if (earlierFileChecked) return
        earlierFileChecked = true
        val earlier = context.getSharedPreferences(EARLIER_FILE, Context.MODE_PRIVATE)
        val servers = earlier.getString(EARLIER_SERVERS, null)
        if (servers != null && !prefs.contains(SettingKeys.SERVERS)) prefs.edit().putString(SettingKeys.SERVERS, servers).apply()
        if (earlier.all.isNotEmpty()) earlier.edit().clear().apply()
    }

    private fun flag(key: String, default: Boolean) = object : ReadWriteProperty<Config, Boolean> {
        override fun getValue(thisRef: Config, property: KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Config, property: KProperty<*>, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    }

    private fun number(key: String, default: Int) = object : ReadWriteProperty<Config, Int> {
        override fun getValue(thisRef: Config, property: KProperty<*>) = prefs.getInt(key, default)
        override fun setValue(thisRef: Config, property: KProperty<*>, value: Int) = prefs.edit().putInt(key, value).apply()
    }

    private companion object {
        /** Once per process is enough for [moveServersFromEarlierFile]. */
        @Volatile
        var earlierFileChecked = false
    }
}

/** The names settings are stored under; the FOLDER_ ones and [ZOOM] are followed by a folder key or a view type. */
object SettingKeys {
    const val SHOW_HIDDEN = "hidden.show"
    const val SORTING = "list.sorting"
    const val VIEW = "list.view"
    const val GROUPING = "list.grouping"
    const val ZOOM = "list.zoom."
    const val FOLDER_SORTING = "folder.sorting:"
    const val FOLDER_VIEW = "folder.view:"
    const val FOLDER_ZOOM = "folder.zoom:"
    const val FOLDER_GROUPING = "folder.grouping:"
    const val AUTO_GALLERY = "list.auto_gallery"
    const val GRID_NAMES = "list.grid_names"
    const val CARDS = "list.cards"
    const val DATE_PATTERN = "time.date_pattern"
    const val CLOCK_24 = "time.clock_24"
    const val HOME_FOLDER = "places.home"
    const val LAST_COPY_TARGET = "places.last_copy_target"
    const val TABS = "places.tabs"
    const val ACTIVE_TAB = "places.active_tab"
    const val RECENT_FOLDERS = "places.recent"
    const val BACK_TWICE = "behavior.back_twice"
    const val ONBOARDING_DONE = "onboarding.done"
    const val SWIPE_PAGES = "behavior.swipe_pages"
    const val DELETE_WITHOUT_ASKING = "behavior.delete_without_asking"
    const val ROOTED = "root.available"
    const val ROOT_ACCESS = "root.access"
    const val EDITOR_ZOOM = "editor.zoom"
    const val RENAME_PATTERN = "rename.last_pattern"
    const val COMPRESS_FORMAT = "compress.format"
    const val COMPRESS_PROTECTED = "compress.protected"
    const val COMPRESS_HIDE_NAMES = "compress.hide_names"
    const val COMPRESS_STRENGTH = "compress.strength"
    const val COMPRESS_KEY_SIZE = "compress.key_size"
    const val COMPRESS_SPLIT = "compress.split"
    const val COMPRESS_PART_SIZE = "compress.part_size"
    const val COMPRESS_SOLID = "compress.solid"
    const val SERVERS = "servers"
    const val APP_ICON = "app_icon"
    const val VAULT_LOCK_MINUTES = "vault.lock_minutes"
    const val VAULT_LOCK_SCREEN_OFF = "vault.lock_screen_off"
}

private const val SETTINGS_FILE = "settings"
private const val EARLIER_FILE = "Prefs"
private const val EARLIER_SERVERS = "remote_servers"
private val COMPRESS_FORMATS = setOf(ArchiveKind.ZIP, ArchiveKind.SEVEN_ZIP)
private const val DEFAULT_EDITOR_TEXT_ZOOM = 1.2f
private const val DEFAULT_VAULT_LOCK_MINUTES = 5

private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? = enumValues<T>().firstOrNull { it.name == name }
