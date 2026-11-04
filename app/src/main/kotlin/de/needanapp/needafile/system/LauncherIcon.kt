package de.needanapp.needafile.system

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import de.needanapp.design.theme.AccentPreset
import de.needanapp.design.theme.AccentSource
import de.needanapp.design.theme.ThemeSettings
import de.needanapp.design.theme.ThemeSettingsStore
import de.needanapp.needafile.R
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.theme.AppearanceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** An icon the launcher can show for needafile; each has its own launcher entry (activity-alias) in the manifest. */
sealed interface AppIcon {
    /** A color that stands for the icon, e.g. behind the symbol of a shortcut. */
    val argb: Int

    @get:StringRes
    val label: Int

    /** The activity-alias in the manifest that shows this icon in the launcher. */
    val aliasName: String
}

/**
 * The drawn motifs of the needafile icon: a dark plate with light technical lines and an orange accent, written by
 * tools/icons/motif_icons.py. [LAYERS] is the standard.
 */
enum class AppIconMotif(
    override val argb: Int,
    @StringRes override val label: Int,
    @DrawableRes val background: Int,
    @DrawableRes val foreground: Int,
) : AppIcon {
    LAYERS(0xFFFF5A1F.toInt(), R.string.app_icon_layers, R.drawable.needafile_icon_layers_background, R.drawable.needafile_icon_layers_foreground),
    BLUEPRINT(0xFF12365C.toInt(), R.string.app_icon_blueprint, R.drawable.needafile_icon_blueprint_background, R.drawable.needafile_icon_blueprint_foreground),
    NETWORK(0xFFFF5A1F.toInt(), R.string.app_icon_network, R.drawable.needafile_icon_network_background, R.drawable.needafile_icon_network_foreground),
    TRANSFER(0xFFFF5A1F.toInt(), R.string.app_icon_transfer, R.drawable.needafile_icon_transfer_background, R.drawable.needafile_icon_transfer_foreground);

    override val aliasName get() = "${AppIconColor.ALIAS_PREFIX}${name.lowercase().replaceFirstChar { it.uppercase() }}"

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.name == key }
    }
}

/** The colors the classic needafile icon (a glyph on a transparent background) comes in. */
enum class AppIconColor(override val argb: Int, @StringRes override val label: Int, @DrawableRes val foreground: Int) : AppIcon {
    ORANGE(0xFFFF5A1F.toInt(), R.string.app_icon_orange, R.drawable.needafile_icon_foreground_orange),
    BLUE(0xFF3D63D8.toInt(), R.string.app_icon_blue, R.drawable.needafile_icon_foreground_blue),
    SAPPHIRE(0xFF1E5AA8.toInt(), de.needanapp.design.R.string.nd_preset_sapphire, R.drawable.needafile_icon_foreground_sapphire),
    EMERALD(0xFF1E8A5A.toInt(), de.needanapp.design.R.string.nd_preset_emerald, R.drawable.needafile_icon_foreground_emerald),
    AMBER(0xFFC98A12.toInt(), de.needanapp.design.R.string.nd_preset_amber, R.drawable.needafile_icon_foreground_amber),
    VIOLET(0xFF6E4BC4.toInt(), de.needanapp.design.R.string.nd_preset_violet, R.drawable.needafile_icon_foreground_violet),
    CORAL(0xFFE0624A.toInt(), de.needanapp.design.R.string.nd_preset_coral, R.drawable.needafile_icon_foreground_coral),
    PETROL(0xFF0E7C86.toInt(), de.needanapp.design.R.string.nd_preset_petrol, R.drawable.needafile_icon_foreground_petrol),
    LIME(0xFF6F9E1C.toInt(), de.needanapp.design.R.string.nd_preset_lime, R.drawable.needafile_icon_foreground_lime),
    ROSE(0xFFC94A7A.toInt(), de.needanapp.design.R.string.nd_preset_rose, R.drawable.needafile_icon_foreground_rose),
    SLATE(0xFF56606E.toInt(), de.needanapp.design.R.string.nd_preset_slate, R.drawable.needafile_icon_foreground_slate),
    BLACK(0xFF1B1B1F.toInt(), R.string.app_icon_black, R.drawable.needafile_icon_foreground_black),
    WHITE(0xFFFFFFFF.toInt(), R.string.app_icon_white, R.drawable.needafile_icon_foreground_white);

    override val aliasName get() = "$ALIAS_PREFIX${name.lowercase().replaceFirstChar { it.uppercase() }}"

    companion object {
        const val ALIAS_PREFIX = "de.needanapp.needafile.launcher."

        fun fromKey(key: String?) = entries.firstOrNull { it.name == key }
    }
}

/**
 * The app's entry in the launcher: one of the motifs, or the classic icon in the chosen color or in the one closest to
 * the accent color; orange, needafile's own color, as long as the accent is the standard one.
 *
 * Launcher icons can only be switched by enabling one activity-alias and disabling the others. Disabling the entry
 * the app was opened with closes it at once, so changes wait until the app is left.
 */
object LauncherIcon {
    /** Value of [appIconChoice]: the icon follows the accent color of the appearance settings. */
    const val FOLLOW_ACCENT = "accent"

    /** Value of [appIconMotif]: the classic icon in a color. */
    const val CLASSIC = "classic"

    /** The icon the launcher shows, or shows once the app is left. */
    fun resolve(context: Context, settings: ThemeSettings? = AppearanceState.current): AppIcon =
        AppIconMotif.fromKey(context.config.appIconMotif) ?: resolveColor(context, settings)

    /**
     * The color the classic icon has. Following the accent color before the appearance settings are read (right after
     * the app started), it is the color the launcher shows now.
     */
    fun resolveColor(context: Context, settings: ThemeSettings? = AppearanceState.current): AppIconColor {
        val choice = context.config.appIconChoice
        if (choice != FOLLOW_ACCENT) {
            return AppIconColor.fromKey(choice) ?: AppIconColor.ORANGE
        }
        if (settings == null) return enabledColors(context).firstOrNull() ?: AppIconColor.ORANGE
        return when (settings.accentSource) {
            // the icon's standard is always orange, whatever the style's standard accent
            AccentSource.DEFAULT -> AppIconColor.ORANGE
            AccentSource.PRESET -> presetColor(settings.preset)
            AccentSource.CUSTOM -> closestTo(settings.customAccent.toArgb())
            AccentSource.DYNAMIC -> closestTo(context.getColor(android.R.color.system_accent1_500))
        }
    }

    // presets without an icon of their own (e.g. the Myzel ones) get the closest icon color
    private fun presetColor(preset: AccentPreset) = AppIconColor.fromKey(preset.name) ?: closestTo(preset.color.toArgb())

    // compares hue and colorfulness (a and b in CIELAB); black and white are only used when picked explicitly
    private fun closestTo(argb: Int): AppIconColor {
        val target = DoubleArray(3).also { ColorUtils.colorToLAB(argb, it) }
        return AppIconColor.entries.filter { it != AppIconColor.BLACK && it != AppIconColor.WHITE }.minBy { color ->
            val lab = DoubleArray(3).also { ColorUtils.colorToLAB(color.argb, it) }
            val da = lab[1] - target[1]
            val db = lab[2] - target[2]
            da * da + db * db
        }
    }

    /** Shows exactly the launcher entry of [resolve]'s icon and hides the others; only touches entries whose state is wrong. */
    fun sync(context: Context, settings: ThemeSettings? = AppearanceState.current) {
        val wanted = resolve(context, settings).aliasName
        // the wanted entry first, so the app is never without one
        launcherEntries(context).sortedByDescending { it.info.name == wanted }.forEach { entry ->
            val shouldBeEnabled = entry.info.name == wanted
            if (entry.isEnabled != shouldBeEnabled) setEnabled(context, entry.info.name, shouldBeEnabled)
        }
    }

    /**
     * Shows the launcher entry of [resolve]'s icon when none is shown, e.g. after an update renamed them. Never hides
     * one: the app may just be opening through it.
     */
    private fun restoreMissing(context: Context) {
        if (launcherEntries(context).none { it.isEnabled }) setEnabled(context, resolve(context).aliasName, true)
    }

    /** The colors whose launcher entries are shown now; normally exactly one. */
    private fun enabledColors(context: Context) =
        launcherEntries(context).filter { it.isEnabled }.mapNotNull { entry -> AppIconColor.entries.firstOrNull { it.aliasName == entry.info.name } }

    private class LauncherEntry(val info: ActivityInfo, val isEnabled: Boolean)

    private fun launcherEntries(context: Context): List<LauncherEntry> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        // the Int variant: the one taking ResolveInfoFlags only exists from Android 13 on
        @Suppress("DEPRECATION")
        val resolvedEntries = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_DISABLED_COMPONENTS)
        return resolvedEntries.map { resolved ->
            val info = resolved.activityInfo
            val isEnabled = when (packageManager.getComponentEnabledSetting(ComponentName(context.packageName, info.name))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> info.enabled
                else -> false
            }
            LauncherEntry(info, isEnabled)
        }
    }

    private fun setEnabled(context: Context, name: String, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(ComponentName(context.packageName, name), state, PackageManager.DONT_KILL_APP)
    }

    /**
     * Makes sure at start that the app has a launcher entry, and syncs whenever the last screen of the app is left.
     * Call once from the Application.
     */
    fun start(application: Application) {
        restoreMissing(application)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started == 0 && !activity.isChangingConfigurations) {
                    sync(application)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

/**
 * Starts the app process right after an update, so [LauncherIcon.start] (run from App.onCreate) can restore a missing
 * launcher entry even though the app can't be opened from the launcher anymore. Then it syncs the entries with the
 * stored appearance: an update may enable another entry by default (e.g. orange as the new standard), and no screen
 * of the app is open yet that hiding one could close.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = ThemeSettingsStore(appContext).settings.first()
                LauncherIcon.sync(appContext, settings)
            } finally {
                pending.finish()
            }
        }
    }
}
