package de.needanapp.needafile.files

import android.content.Context
import android.text.format.DateFormat
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.SelectionMark
import de.needanapp.needafile.R
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.formatDate
import de.needanapp.needafile.core.formatSize
import de.needanapp.needafile.core.getMimeType
import de.needanapp.needafile.core.isImageFast
import de.needanapp.needafile.core.isVideoFast
import de.needanapp.needafile.core.timePattern
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.vault.isVaultPath
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Thumbnails already decoded, so scrolling back doesn't load them again; an eighth of the app's memory, counted in KiB. */
private object ThumbnailCache : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / THUMBNAIL_MEMORY_SHARE / 1024).toInt()) {
    override fun sizeOf(key: String, value: ImageBitmap) = (value.asAndroidBitmap().allocationByteCount / 1024).coerceAtLeast(1)
}

/** Drops the thumbnails of everything below [pathPrefix], e.g. of a vault that was locked. */
internal fun forgetThumbnails(pathPrefix: String) {
    ThumbnailCache.snapshot().keys.filter { it.startsWith(pathPrefix) }.forEach { ThumbnailCache.remove(it) }
}

private fun String.extension() = substringAfterLast('.', "").lowercase(Locale.getDefault())

/** A Material symbol for the kind of file, for files without a preview. */
internal fun typeIcon(name: String): ImageVector {
    val extension = name.extension()
    return when {
        extension == "pdf" -> Icons.Outlined.PictureAsPdf
        extension == "apk" -> Icons.Outlined.Android
        extension in SPREADSHEETS -> Icons.Outlined.TableChart
        extension in PRESENTATIONS -> Icons.Outlined.Slideshow
        extension in CODE -> Icons.Outlined.Code
        extension in ARCHIVES -> Icons.Outlined.FolderZip
        else -> when (name.getMimeType().substringBefore('/')) {
            "image" -> Icons.Outlined.Image
            "video" -> Icons.Outlined.Movie
            "audio" -> Icons.Outlined.MusicNote
            "text" -> Icons.Outlined.Description
            else -> if (extension in DOCUMENTS) Icons.Outlined.Description else Icons.AutoMirrored.Outlined.InsertDriveFile
        }
    }
}

private val SPREADSHEETS = setOf("xls", "xlsx", "ods", "csv", "tsv")
private val PRESENTATIONS = setOf("ppt", "pptx", "odp", "key")
private val DOCUMENTS = setOf("doc", "docx", "odt", "rtf", "epub", "md")
private val CODE = setOf("kt", "java", "js", "ts", "py", "c", "cpp", "h", "sh", "json", "xml", "html", "css", "yml", "yaml", "gradle", "kts")
private val ARCHIVES = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "jar")

/**
 * Whether a preview of the file's content is worth loading: pictures, videos and app packages on the phone, pictures
 * and videos in vaults (decrypted for it) up to a size that is quick to decrypt.
 */
internal fun FileEntry.hasThumbnail() = when {
    path.isVaultPath() -> (path.isImageFast() && size in 0..MAX_VAULT_IMAGE_PREVIEW) || (path.isVideoFast() && size in 0..MAX_VAULT_VIDEO_PREVIEW)
    path.isVirtualPath() -> false
    else -> path.isImageFast() || path.isVideoFast() || path.endsWith(".apk", true)
}

/**
 * The icon of a list or grid entry: a folder, a preview of pictures and videos, or the file type. Folders and file
 * types are plain outlined symbols without an area behind them. A selected entry shows a check mark instead.
 */
@Composable
fun FileIcon(item: FileEntry, selected: Boolean, size: Dp, shape: Shape, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        when {
            selected -> SelectionMark(Modifier.fillMaxSize())

            item.isDirectory -> Icon(Icons.Outlined.Folder, contentDescription = null, tint = colors.primary, modifier = Modifier.fillMaxSize().padding(symbolInset(size)))

            else -> FileTypeImage(item, size)
        }
    }
}

/** In the list the symbol fills its place; in the grid it stays smaller, so the gallery's name strip doesn't cover it. */
private fun symbolInset(size: Dp) = if (size > LIST_ICON_LIMIT) size / 5 else size / 10

private val LIST_ICON_LIMIT = 80.dp

@Composable
private fun FileTypeImage(item: FileEntry, size: Dp) {
    val context = LocalContext.current
    // a few fixed sizes, so a bigger zoom level loads sharper previews without loading new ones at every step
    val sizePx = with(LocalDensity.current) { size.roundToPx() }.let { px -> THUMBNAIL_SIZES.firstOrNull { it >= px } ?: THUMBNAIL_SIZES.last() }
    val key = "${item.path}:${item.modified}:${item.size}:$sizePx"
    var thumbnail by remember(key) { mutableStateOf(ThumbnailCache.get(key)) }

    if (item.hasThumbnail()) {
        LaunchedEffect(key) {
            if (thumbnail == null) {
                thumbnail = loadThumbnail(context, item, key, sizePx)
            }
        }
    }

    val image = thumbnail
    if (image != null) {
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Icon(
            remember(item.name) { typeIcon(item.name) },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.fillMaxSize().padding(symbolInset(size)),
        )
    }
}

private suspend fun loadThumbnail(context: Context, item: FileEntry, key: String, sizePx: Int): ImageBitmap? {
    val bitmap = runInterruptible(Dispatchers.IO) {
        if (item.path.isVaultPath()) loadVaultThumbnailBitmap(context, item, sizePx) else loadThumbnailBitmap(context, item.path, sizePx)
    } ?: return null
    return bitmap.asImageBitmap().also { ThumbnailCache.put(key, it) }
}

/**
 * "12 items" for folders, "1.2 MB · 03.09.2026 14:00" for files; server folders aren't counted. A size or date that
 * isn't known (e.g. in some archives) is left out.
 */
fun describeItem(context: Context, item: FileEntry): String {
    if (item.isDirectory) {
        return if (item.path.isVirtualPath()) "" else context.resources.getQuantityString(R.plurals.items, item.children, item.children)
    }
    val size = item.size.takeIf { it >= 0 }?.formatSize()
    val date = item.modified.takeIf { it > 0 }?.formatDate(context)
    return if (size != null && date != null) context.getString(R.string.size_and_date, size, date) else size ?: date.orEmpty()
}

/**
 * The short detail of compact rows: the size of files, the number of entries of folders (not on servers).
 */
fun describeItemShort(context: Context, item: FileEntry): String = when {
    !item.isDirectory -> item.size.takeIf { it >= 0 }?.formatSize().orEmpty()
    item.path.isVirtualPath() -> ""
    else -> context.resources.getQuantityString(R.plurals.items, item.children, item.children)
}

/** The texts of the size, date and type columns of the details view. */
data class DetailColumns(val size: String, val date: String, val type: String)

fun detailColumns(context: Context, item: FileEntry, isWide: Boolean): DetailColumns {
    val date = item.modified.takeIf { it > 0 }?.let { modified ->
        val calendar = Calendar.getInstance(Locale.ENGLISH).apply { timeInMillis = modified }
        val day = columnDate(context, calendar)
        if (isWide) "$day, ${DateFormat.format(context.timePattern(), calendar)}" else day
    }.orEmpty()
    val type = when {
        item.isDirectory -> context.getString(R.string.folder)
        else -> item.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length < item.name.length }?.uppercase(Locale.getDefault()) ?: "–"
    }
    return DetailColumns(describeItemShort(context, item), date, type)
}

/** The date format of the settings, or the phone's short numeric date when that format spells out the month (too wide for a column). */
private fun columnDate(context: Context, calendar: Calendar): String {
    val format = context.config.dateFormat
    return if (format.contains("MMM")) DateFormat.getDateFormat(context).format(calendar.time) else DateFormat.format(format, calendar).toString()
}

private const val THUMBNAIL_MEMORY_SHARE = 8

/** Pictures and videos in vaults are decrypted whole for their preview; larger ones keep their type icon. */
private const val MAX_VAULT_IMAGE_PREVIEW = 40L * 1024 * 1024
private const val MAX_VAULT_VIDEO_PREVIEW = 150L * 1024 * 1024
private val THUMBNAIL_SIZES = intArrayOf(160, 320, 640, 1080)
