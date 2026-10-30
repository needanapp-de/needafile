package de.needanapp.needafile.core

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.TransactionTooLargeException
import de.needanapp.needafile.R
import de.needanapp.needafile.vault.VaultSessions
import java.util.Locale

/**
 * Opens a file with another app: APKs go straight to the installer, anything else to the app for its type. With
 * [withChooser] Android always asks which app; [mimeType] replaces the file's own type. [finishActivity] closes the
 * calling screen afterwards. Finding the file in MediaStore can take a moment, so this runs in the background.
 */
fun Activity.openFile(path: String, withChooser: Boolean, mimeType: String = "", finishActivity: Boolean = false) {
    if (!withChooser && path.endsWith(".apk", true)) {
        inBackground {
            val uri = publicUriOrNull(path) ?: return@inBackground
            startSafely(Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME_TYPE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    } else {
        viewWithApp(path, withChooser, mimeType)
    }
    if (finishActivity) finish()
}

private fun Activity.viewWithApp(path: String, withChooser: Boolean, mimeType: String) = inBackground {
    val uri = publicUriOrNull(path) ?: return@inBackground
    val type = mimeType.ifEmpty { path.getMimeType().ifEmpty { mimeTypeOf(uri) } }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, type)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // vault files are read through the uri only, never from the copy needafile's viewer decrypted
        if (path.startsWith("/") && VaultSessions.originOf(path) == null) putExtra(EXTRA_FILE_PATH, path)
    }
    try {
        startActivity(if (withChooser) Intent.createChooser(intent, getString(R.string.open_with)) else intent)
    } catch (e: ActivityNotFoundException) {
        // no app for the exact type, maybe one for its kind ("image/ *") or for anything
        val generic = type.substringBefore('/', "").takeIf { it.isNotEmpty() }?.let { "$it/*" } ?: ANY_MIME_TYPE
        intent.setDataAndType(uri, generic)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app_found)
        }
    }
}

/** Shares files with another app, finding their uris in the background. */
fun Activity.sharePaths(paths: List<String>) = inBackground {
    if (paths.isEmpty()) return@inBackground
    val uris = paths.map { publicUriOrNull(it) ?: return@inBackground }
    val type = paths.getCommonMimeType().takeIf { it.isNotEmpty() && it != "application/octet-stream" } ?: ANY_MIME_TYPE
    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        this.type = type
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        // the share sheet gets to read them too, for its preview
        clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        startActivity(Intent.createChooser(intent, getString(R.string.share_via)))
    } catch (e: ActivityNotFoundException) {
        toast(R.string.no_app_found)
    } catch (e: RuntimeException) {
        if (e.cause is TransactionTooLargeException) toast(R.string.maximum_share_reached) else showError(e)
    }
}

/** Offers a picture to other apps, e.g. as wallpaper or contact photo. */
fun Activity.setAs(path: String) = inBackground {
    val uri = publicUriOrNull(path) ?: return@inBackground
    val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
        setDataAndType(uri, path.getMimeType().ifEmpty { mimeTypeOf(uri) })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        startActivity(Intent.createChooser(intent, getString(R.string.set_as)))
    } catch (e: ActivityNotFoundException) {
        toast(R.string.no_app_found)
    }
}

/** Opens a web page. */
fun Context.openWebPage(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        toast(R.string.no_browser_found)
    }
}

/**
 * Shows the spot at [latitude], [longitude] on a map with a pin on it: in Google Maps, without Google Maps in another
 * map app, without any in the browser.
 */
fun Activity.showOnMap(latitude: Double, longitude: Double) {
    val point = "%.6f,%.6f".format(Locale.ROOT, latitude, longitude)
    // a search pins the exact spot; other Google Maps links pin the nearest business instead
    val search = "https://www.google.com/maps/search/?api=1&query=$point"
    if (!startIfInstalled(Intent(Intent.ACTION_VIEW, Uri.parse(search)).setPackage(GOOGLE_MAPS)) &&
        !startIfInstalled(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=$point")))
    ) {
        openWebPage(search)
    }
}

/** Starts [intent]; false when no app for it is installed. */
private fun Activity.startIfInstalled(intent: Intent) = try {
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
}

private const val GOOGLE_MAPS = "com.google.android.apps.maps"

/** Opens the mail app with a new message to [recipient]. */
fun Context.writeEmail(recipient: String) = startSafely(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", recipient, null)))

/** Starts [intent], or says that no app can handle it. */
fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        toast(R.string.no_app_found)
    }
}

/** Shows [uri] in the system's own file app, which may look into folders like Android/data that apps can't. */
fun Context.launchSystemFileManager(uri: Uri) {
    val candidates = listOf(
        ComponentName("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity"),
        ComponentName("com.android.documentsui", "com.android.documentsui.files.FilesActivity"),
        null,
    )
    val opened = candidates.any { component ->
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                component?.let { this.component = it }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
    if (!opened) toast(R.string.confirm_storage_access_restricted_text_not_found)
}

private fun Context.publicUriOrNull(path: String): Uri? = try {
    ensurePublicUri(path)
} catch (e: IllegalArgumentException) {
    // a path the file provider doesn't cover
    showError(e)
    null
}

/** The file path behind a uri handed to the viewers, so they can show where it lies and work on the file itself. */
const val EXTRA_FILE_PATH = "de.needanapp.needafile.FILE_PATH"

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
