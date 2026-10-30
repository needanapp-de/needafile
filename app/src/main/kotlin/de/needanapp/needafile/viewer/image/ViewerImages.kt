package de.needanapp.needafile.viewer.image

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import com.awxkee.jxlcoder.coil.JxlDecoder
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.entryOrder
import de.needanapp.needafile.core.filePathOf
import de.needanapp.needafile.permissions.StorageAccess
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.viewer.Viewers
import java.io.File

/**
 * A picture in the viewer: a file when there is one, otherwise only what another app handed over. A picture in a
 * vault ([vault]) has neither until it is decrypted.
 */
internal data class ViewerImage(val path: String?, val uri: Uri, val name: String, val vault: VaultGallery.Entry? = null) {
    /** What Coil loads: the file itself, so large pictures can be shown in full detail when zoomed in. */
    val model: Any get() = path?.let(::File) ?: uri
    val key: String get() = vault?.vaultPath ?: path ?: uri.toString()
}

internal object ViewerImages {
    @Volatile
    private var loader: ImageLoader? = null

    /** Coil with animated GIF/WebP/HEIF, SVG and JPEG XL on top of what Android decodes itself. */
    fun loader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(SvgDecoder.Factory())
                add(JxlDecoder.Factory())
            }
            .build()
            .also { loader = it }
    }

    fun fromPath(path: String) = ViewerImage(path, Uri.fromFile(File(path)), File(path).name)

    /** The picture behind [uri]; a file when it can be found and read, so its neighbors can be shown too. */
    fun resolve(context: Context, uri: Uri): ViewerImage {
        if (uri.scheme == "file") uri.path?.let { return fromPath(it) }
        val path = if (StorageAccess.isGranted()) runCatching { context.filePathOf(uri) }.getOrNull() else null
        if (path != null && File(path).canRead()) return fromPath(path)
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        return ViewerImage(null, uri, name)
    }

    /** The pictures in the folder of [path], hidden ones only when hidden files are shown; not yet sorted. */
    fun siblings(context: Context, path: String): List<FileEntry> {
        val folder = File(path).parentFile ?: return emptyList()
        val showHidden = context.config.showHidden
        return folder.listFiles().orEmpty()
            .filter { it.isFile && Viewers.isImage(it.name) && (showHidden || !it.name.startsWith(".") || it.path == path) }
            .map { FileEntry(it.path, it.name, false, 0, it.length(), it.lastModified()) }
    }

    /** [items] in the order the folder has in the file list. */
    fun sortLikeFolder(context: Context, folder: String, items: List<FileEntry>): List<FileEntry> {
        return items.sortedWith(entryOrder(context.config.getFolderSorting(folder)))
    }
}
