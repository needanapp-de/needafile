package de.needanapp.needafile.vault

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Closes this screen as soon as the vault its file comes from locks, so nothing of it stays readable on screen. [path]
 * is a file decrypted for viewing, [uri] one handed over by [VaultShares]; for any other file nothing happens.
 */
fun ComponentActivity.closeWhenVaultLocks(path: String?, uri: Uri? = null) {
    val location = path?.let { VaultSessions.originOf(it)?.location }
        ?: uri?.takeIf { VaultShares.isVaultUri(this, it) }?.let { VaultShares.get(it)?.location ?: run { finish(); return } }
        ?: return
    if (!VaultSessions.isUnlocked(location)) {
        finish()
        return
    }
    hideFromRecents(true)
    val listener = { _: String -> if (!VaultSessions.isUnlocked(location)) finish() }
    VaultSessions.addListener(listener)
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = VaultSessions.removeListener(listener)
    })
}

/**
 * Keeps this screen out of the picture Android takes of it for the recent apps, e.g. while it shows a vault's files:
 * the picture would stay there after the vault locks and the screen closes, until the app comes to the front again.
 * Below Android 13 only a secure window does that, which also blocks screenshots.
 */
fun Activity.hideFromRecents(hidden: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        setRecentsScreenshotEnabled(!hidden)
    } else if (hidden) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
