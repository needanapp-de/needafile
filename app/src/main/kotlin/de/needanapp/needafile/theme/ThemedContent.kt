package de.needanapp.needafile.theme

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.needanapp.design.theme.NeedanappTheme
import de.needanapp.design.theme.ThemeSettings
import de.needanapp.design.theme.ThemeSettingsStore
import de.needanapp.needafile.core.KeepScreenOnWhileBusy
import de.needanapp.needafile.permissions.StorageAccessHost
import de.needanapp.needafile.security.AppLock
import de.needanapp.needafile.security.AppLockScreen
import de.needanapp.needafile.security.SecurityRequestHost

/**
 * Compose content in needafile's theme; starts with the settings this process already read (the App follows them from
 * onCreate) and otherwise waits for them instead of flashing the defaults. The app lock screen stands in for the
 * content while the app is locked; protection prompts and the request for access to all files appear on top. While a
 * copy or the like runs, the display stays on.
 */
fun ComponentActivity.setThemedContent(content: @Composable (ThemeSettings) -> Unit) {
    val store = ThemeSettingsStore(this)
    setContent {
        val settings by store.settings.collectAsStateWithLifecycle(initialValue = store.cachedSettings)
        settings?.let {
            NeedanappTheme(it) {
                KeepScreenOnWhileBusy()
                if (AppLock.isLocked) {
                    AppLockScreen()
                } else {
                    content(it)
                    SecurityRequestHost()
                    StorageAccessHost()
                }
            }
        }
    }
}
