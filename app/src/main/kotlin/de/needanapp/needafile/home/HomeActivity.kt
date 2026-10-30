package de.needanapp.needafile.home

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.StorageVolumes
import de.needanapp.needafile.core.filePathOf
import de.needanapp.needafile.core.inBackground
import de.needanapp.needafile.core.openFile
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.files.FileBrowserModel
import de.needanapp.needafile.files.isVirtualPath
import de.needanapp.needafile.onboarding.Onboarding
import de.needanapp.needafile.onboarding.OnboardingFlow
import de.needanapp.needafile.permissions.StorageAccess
import de.needanapp.needafile.permissions.StorageAccessScreen
import de.needanapp.needafile.pick.PickRequest
import de.needanapp.needafile.remote.RemoteConnections
import de.needanapp.needafile.root.RootShell
import de.needanapp.needafile.security.AppLock
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.theme.setThemedContent
import de.needanapp.needafile.transfer.TransferManager
import de.needanapp.needafile.viewer.image.VaultGallery
import java.io.File

/**
 * The main screen of needafile, built with Compose and the needanapp design package: files, servers and settings
 * behind the floating navigation pill. Also the screen other apps get when they want a file picked or saved.
 */
class HomeActivity : BaseActivity() {
    private val model: FileBrowserModel by viewModels()
    /** When back last asked to be pressed again to close the app, 0 if it didn't. */
    private var backWarnedAt = 0L
    private var needsStorageAccess by mutableStateOf(false)
    private var showsOnboarding by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // started from the launcher in a task of its own, not brought back by the system after it ended the app in the
        // background: the app was closed (back, swiped away, restart), so it doesn't take up the last tabs again
        val freshStart = savedInstanceState == null && isTaskRoot && intent?.action == Intent.ACTION_MAIN
        model.attach(this, PickRequest.from(intent), freshStart)
        // only when opened from the launcher, not while another app has a file picked
        showsOnboarding = intent?.action == Intent.ACTION_MAIN && !model.isPicking && Onboarding.shouldShow(this)

        setThemedContent {
            when {
                showsOnboarding -> OnboardingFlow(this, it, onDone = ::finishOnboarding)
                needsStorageAccess -> StorageAccessScreen(onAllow = { StorageAccess.openSettings(this) })
                else -> HomeScreen(this, model, it)
            }
        }

        if (savedInstanceState == null) inBackground(::startupChecks)
        if (model.currentPath.isEmpty()) {
            showStartFolder()
        }
    }

    override fun onResume() {
        super.onResume()
        TransferManager.isUiVisible = true
        // an SD card or USB drive may have come or gone meanwhile
        StorageVolumes.refresh(this)
        // back from the system settings with access to all files
        if (needsStorageAccess && StorageAccess.isGranted()) showStartFolder()
        model.refreshServerNames()
        // settings like hidden files may have changed meanwhile; server folders would cost a request each time, vault
        // folders reload only when pictures were deleted in the viewer
        if (model.currentPath.isNotEmpty() && (!model.currentPath.isVirtualPath() || VaultGallery.takeChanged(model.currentPath))) {
            model.refresh()
        }
    }

    override fun onPause() {
        super.onPause()
        TransferManager.isUiVisible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        model.detach(this)
        if (!isChangingConfigurations) {
            RemoteConnections.closeAll()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openIntentPath(intent)
    }

    /** The last step of "back": everything in the screen itself (selection, folders up …) is handled there. */
    override fun onLastBack(): Boolean {
        // back out of picking: the asking app gets no file
        if (model.isPicking) {
            finish()
            return true
        }
        // with "press back twice" the first press only warns; a second one soon after closes
        val now = SystemClock.elapsedRealtime()
        if (config.pressBackTwice && now - backWarnedAt > BACK_AGAIN_WINDOW_MS) {
            backWarnedAt = now
            toast(R.string.press_back_again)
            return true
        }
        AppLock.lock(this)
        model.forgetTabs()
        finish()
        return true
    }

    /** Shows the introduction again, e.g. from the settings. */
    fun showOnboarding() {
        showsOnboarding = true
    }

    private fun finishOnboarding() {
        Onboarding.finish(this)
        showsOnboarding = false
        // the files open once they may be read; shown again from the settings, the tabs stay as they were
        if (needsStorageAccess || model.currentPath.isEmpty()) showStartFolder()
    }

    /** The folder the app starts in, once it may read the storage: what the intent asks for, otherwise the home folder. */
    private fun showStartFolder() {
        needsStorageAccess = !StorageAccess.isGranted()
        if (needsStorageAccess) return
        if (!openIntentPath(intent)) model.openActiveTab(config.homeFolder)
    }

    /** A folder shortcut or "open with": shows that folder, or opens the file and closes again. */
    private fun openIntentPath(intent: Intent?): Boolean {
        // the app's settings link in the system settings: the settings page above the usual folder
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) {
            intent.removeExtra(EXTRA_OPEN_SETTINGS)
            model.showSettings()
            return false
        }

        // a folder from another screen of the app, e.g. "show in folder" of a viewer
        intent?.getStringExtra(EXTRA_OPEN_FOLDER)?.let { folder ->
            intent.removeExtra(EXTRA_OPEN_FOLDER)
            model.showInFiles(folder)
            return true
        }

        val data = intent?.data
        if (intent?.action != Intent.ACTION_VIEW || data == null) {
            return false
        }

        val path = if (data.scheme == "file") data.path else filePathOf(data)
        if (path == null) {
            model.openActiveTab(config.homeFolder)
            return true
        }

        val file = File(path)
        if (file.isDirectory) {
            model.showInFiles(path)
        } else {
            model.showInFiles(file.parent ?: config.homeFolder)
            openFile(path, false, finishActivity = true)
        }
        return true
    }

    /**
     * Once per start, in the background: whether the phone is rooted. Root access is offered only then, and asked for
     * again if it was switched on.
     */
    private fun startupChecks() {
        val rooted = RootShell.isAvailable()
        config.isRootAvailable = rooted
        if (rooted && config.enableRootAccess) config.enableRootAccess = RootShell.requestAccess()
    }

    companion object {
        /** A local folder to show, for screens of the app that lead back to the files. */
        const val EXTRA_OPEN_FOLDER = "de.needanapp.needafile.OPEN_FOLDER"
        const val EXTRA_OPEN_SETTINGS = "de.needanapp.needafile.OPEN_SETTINGS"
        private const val BACK_AGAIN_WINDOW_MS = 5000L
    }
}
