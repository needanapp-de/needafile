package de.needanapp.needafile.core

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import de.needanapp.needafile.R
import kotlin.concurrent.thread

fun Context.toast(textRes: Int, length: Int = Toast.LENGTH_SHORT) = toast(getString(textRes), length)

/** Shows [text] briefly, from any thread; nothing happens for a closing activity. */
fun Context.toast(text: String, length: Int = Toast.LENGTH_SHORT) = runOnMainThread {
    val activity = this as? Activity
    if (activity == null || !activity.isFinishing && !activity.isDestroyed) {
        Toast.makeText(this, text, length).show()
    }
}

/** "Error: …" with [text], shown a little longer. */
fun Context.showError(text: String) = toast(getString(R.string.error, text), Toast.LENGTH_LONG)

fun Context.showError(error: Throwable) = showError(error.localizedMessage ?: error.toString())

/** Puts [text] on the clipboard and says so. */
fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.brand_name), text))
    toast(getString(R.string.value_copied_to_clipboard_show, text))
}

/** Closes the keyboard and takes the focus off the field that had it. */
fun Activity.hideKeyboard() = runOnMainThread {
    val focused = currentFocus
    getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow((focused ?: window.decorView).windowToken, 0)
    focused?.clearFocus()
}

fun isMainThread() = Looper.getMainLooper().isCurrentThread

/** Runs [work] off the main thread: on a new thread when called from the main thread, otherwise right here. */
fun inBackground(work: () -> Unit) {
    if (isMainThread()) thread(isDaemon = true, block = work) else work()
}

fun runOnMainThread(work: () -> Unit) {
    if (isMainThread()) work() else mainHandler.post(work)
}

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
