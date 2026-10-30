package de.needanapp.needafile.theme

import android.content.Context
import de.needanapp.design.theme.ThemeSettings
import de.needanapp.design.theme.ThemeSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The appearance settings as last stored, for code outside of Compose (e.g. the launcher icon following the accent). */
object AppearanceState {
    @Volatile
    var current: ThemeSettings? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Follows the stored settings from now on. Call once from the Application. */
    fun start(context: Context) {
        val store = ThemeSettingsStore(context.applicationContext)
        scope.launch { store.settings.collect { current = it } }
    }
}
