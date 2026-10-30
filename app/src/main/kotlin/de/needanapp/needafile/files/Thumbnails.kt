package de.needanapp.needafile.files

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.Size
import androidx.core.graphics.drawable.toBitmap
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.isVideoFast
import de.needanapp.needafile.transfer.TransferManager.toBrowserFile
import de.needanapp.needafile.vault.VaultSessions
import java.io.File

/**
 * A small preview of a picture, video or app package on the phone, at most [sizePx] wide and high; null when there
 * is none. Pictures use the preview stored inside them where there is one, which is fast. Call it off the main thread.
 */
internal fun loadThumbnailBitmap(context: Context, path: String, sizePx: Int): Bitmap? = try {
    val size = Size(sizePx, sizePx)
    when {
        path.endsWith(".apk", true) -> apkIcon(context, path)?.toBitmap(sizePx, sizePx)
        path.isVideoFast() -> ThumbnailUtils.createVideoThumbnail(File(path), size, null)
        else -> ThumbnailUtils.createImageThumbnail(File(path), size, null)
    }
} catch (e: Exception) {
    // unknown or broken format, the type icon is shown instead
    null
}

/**
 * The preview of a picture or video in an unlocked vault: decrypted into the cache for a moment and deleted right after,
 * only the small bitmap stays (in memory). Null when the vault got locked meanwhile or the file can't be read.
 */
internal fun loadVaultThumbnailBitmap(context: Context, item: FileEntry, sizePx: Int): Bitmap? {
    val file = try {
        VaultSessions.decryptForPreview(context, item.path, item.toBrowserFile())
    } catch (e: Exception) {
        return null
    }
    return try {
        loadThumbnailBitmap(context, file.path, sizePx)
    } finally {
        file.parentFile?.deleteRecursively()
    }
}

/** The icon of the app inside an APK file. */
private fun apkIcon(context: Context, path: String) = context.packageManager.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES)?.applicationInfo?.let {
    it.sourceDir = path
    it.publicSourceDir = path
    it.loadIcon(context.packageManager)
}
