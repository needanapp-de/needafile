package de.needanapp.needafile.viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import de.needanapp.needafile.R
import de.needanapp.needafile.core.openFile
import de.needanapp.needafile.core.sharePaths
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.home.HomeActivity
import java.io.File

/** Actions the viewers share: a file when there is one, otherwise what another app handed over at [uri]. */
internal object ViewerActions {
    fun share(activity: Activity, path: String?, uri: Uri, fallbackType: String) {
        if (path != null) {
            activity.sharePaths(arrayListOf(path))
            return
        }
        val intent = Intent(Intent.ACTION_SEND).setType(activity.contentResolver.getType(uri) ?: fallbackType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        chooser(activity, intent, R.string.share)
    }

    fun openWith(activity: Activity, path: String?, uri: Uri, fallbackType: String) {
        if (path != null) {
            activity.openFile(path, true)
            return
        }
        send(activity, Intent.ACTION_VIEW, uri, fallbackType, R.string.open_with)
    }

    fun send(activity: Activity, action: String, uri: Uri, fallbackType: String, title: Int) {
        val intent = Intent(action).setDataAndType(uri, activity.contentResolver.getType(uri) ?: fallbackType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        chooser(activity, intent, title)
    }

    private fun chooser(activity: Activity, intent: Intent, title: Int) {
        runCatching { activity.startActivity(Intent.createChooser(intent, activity.getString(title))) }
            .onFailure { activity.toast(R.string.no_app_found) }
    }

    /** Hides status and navigation bar while [fullScreen] (they come back with a swipe from the edge). */
    fun setFullScreen(activity: Activity, fullScreen: Boolean) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullScreen) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    /** Back to the main screen, showing the folder [path] is in. */
    fun showInFolder(activity: Activity, path: String) {
        val folder = File(path).parentFile ?: return
        val intent = Intent(activity, HomeActivity::class.java)
            .putExtra(HomeActivity.EXTRA_OPEN_FOLDER, folder.path)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activity.startActivity(intent)
        activity.finish()
    }
}
