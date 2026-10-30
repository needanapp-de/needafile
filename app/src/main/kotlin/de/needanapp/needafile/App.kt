package de.needanapp.needafile

import android.app.Application
import de.needanapp.needafile.core.StorageVolumes
import de.needanapp.needafile.security.AppLock
import de.needanapp.needafile.system.LauncherIcon
import de.needanapp.needafile.theme.AppearanceState
import de.needanapp.needafile.vault.VaultAutoLock

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppearanceState.start(this)
        StorageVolumes.refresh(this)
        LauncherIcon.start(this)
        AppLock.start(this)
        VaultAutoLock.start(this)
    }
}
