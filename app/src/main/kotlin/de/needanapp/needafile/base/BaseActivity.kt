package de.needanapp.needafile.base

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import de.needanapp.design.theme.NeedanappViewTheme

/**
 * The base of needafile's screens: the appearance settings for the window, edge to edge, a last step for "back"
 * ([onLastBack]) and asking for the permission to show notifications. AppCompat keeps the app's own language
 * working on Android 12.
 */
open class BaseActivity : AppCompatActivity() {
    private var onNotificationPermission: ((Boolean) -> Unit)? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onNotificationPermission?.invoke(granted)
        onNotificationPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // night mode and the window behind the Compose content follow the appearance settings, so nothing flashes
        NeedanappViewTheme.apply(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // registered before any screen content, so Compose's back handlers come first
        onBackPressedDispatcher.addCallback(this) {
            if (!onLastBack()) {
                // step aside once, so the dispatcher does what it would do without this screen
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    /** What "back" does when nothing on the screen took it; true when handled, false for the usual behavior. */
    open fun onLastBack() = false

    /** Asks for the permission to post notifications if needed; [onResult] gets whether it is granted. */
    fun withNotificationPermission(onResult: (Boolean) -> Unit) {
        // Android 12 shows notifications without asking
        val isGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (isGranted) {
            onResult(true)
            return
        }
        onNotificationPermission = onResult
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
