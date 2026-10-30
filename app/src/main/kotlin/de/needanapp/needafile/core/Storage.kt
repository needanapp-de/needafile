package de.needanapp.needafile.core

import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import de.needanapp.needafile.BuildConfig
import de.needanapp.needafile.R
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.vault.VaultSessions
import java.io.File
import java.util.Locale

/**
 * The phone's storage volumes. With access to all files (which needafile asks for) internal storage, SD cards and USB
 * drives are all reached with plain file paths; only Android/data and Android/obb stay closed to apps.
 */
object StorageVolumes {
    @Volatile
    private var removable: String? = null

    /** The internal shared storage, usually /storage/emulated/0. */
    val internalPath: String = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')

    /** The first SD card or USB drive that is mounted, empty if there is none. Looked up again by [refresh]. */
    fun removablePath(context: Context): String = removable ?: refresh(context)

    /** Looks for SD cards and USB drives again, e.g. when the app comes back to the front. */
    fun refresh(context: Context): String {
        val manager = context.getSystemService(StorageManager::class.java)
        val path = manager?.storageVolumes.orEmpty()
            .firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            ?.directory?.absolutePath?.trimEnd('/').orEmpty()
        removable = path
        return path
    }

    /** Volume names as MediaStore knows them, the primary one first. */
    fun mediaVolumeNames(context: Context): List<String> {
        val manager = context.getSystemService(StorageManager::class.java)
        val others = manager?.storageVolumes.orEmpty()
            .filter { !it.isPrimary && it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { it.uuid?.lowercase(Locale.US) }
        return listOf(MediaStore.VOLUME_EXTERNAL_PRIMARY) + others
    }
}

val Context.internalStorage: String get() = StorageVolumes.internalPath

/** The SD card or USB drive, empty when none is mounted. */
val Context.removableStorage: String get() = StorageVolumes.removablePath(this)

fun Context.isOnRemovableStorage(path: String) = removableStorage.let { it.isNotEmpty() && path.isInside(it) }

/** Outside the shared storage volumes, reachable with root only. */
fun Context.needsRoot(path: String) = !path.isInside(internalStorage) && !isOnRemovableStorage(path)

/** The root of the volume [this] lies on: internal storage, the SD card, or "/" for anything else. */
fun String.volumeRoot(context: Context) = when {
    isInside(context.internalStorage) -> context.internalStorage
    context.isOnRemovableStorage(this) -> context.removableStorage
    else -> "/"
}

/** What a volume is called, e.g. "Interner Speicher" for the internal storage. */
fun Context.volumeName(root: String): String = getString(
    when (root) {
        "/" -> R.string.root
        internalStorage -> R.string.internal
        else -> R.string.sd_card
    },
)

/** A path as people read it: the volume's name in place of /storage/…, e.g. "Interner Speicher/Download". */
fun Context.displayPath(path: String): String {
    val root = path.volumeRoot(this)
    val rest = path.trimEnd('/').removePrefix(root.trimEnd('/'))
    return volumeName(root) + rest
}

/** Android/data and Android/obb of a volume: Android keeps apps out of them, only the system's file app can show them. */
fun Context.isRestrictedFolder(path: String) = restrictedFolderOf(path) != null

/** The document uri of the restricted folder holding [path], for the system's file app. */
fun Context.restrictedFolderUri(path: String): Uri {
    val root = path.volumeRoot(this).trimEnd('/')
    val folder = restrictedFolderOf(path) ?: RESTRICTED_FOLDERS.first()
    val storageId = if (root == internalStorage) PRIMARY_STORAGE_ID else root.substringAfterLast('/')
    val treeUri = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "$storageId:")
    return DocumentsContract.buildDocumentUriUsingTree(treeUri, "$storageId:$folder")
}

/** "Android/data" or "Android/obb" when [path] lies in one of them on a shared volume. */
private fun Context.restrictedFolderOf(path: String): String? {
    val root = path.volumeRoot(this).takeIf { it != "/" } ?: return null
    return RESTRICTED_FOLDERS.firstOrNull { path.isInside("$root/$it") }
}

/** Whether this path is [folder] or lies somewhere below it. */
fun String.isInside(folder: String): Boolean {
    val base = folder.trimEnd('/')
    return this == base || startsWith("$base/") || base.isEmpty()
}

/**
 * Where picking a folder to copy or move to starts: the one used last time, as long as it still exists and isn't
 * hidden while hidden files are off; otherwise [fallback].
 */
fun Context.copyTargetStart(showHidden: Boolean, fallback: String): String {
    val last = config.lastCopyPath.takeIf { it.isNotEmpty() } ?: return fallback
    val usable = File(last).isDirectory && (showHidden || !last.isPathInHiddenFolder())
    return if (usable) last else fallback
}

/**
 * Change times of the files directly in [folder] as MediaStore keeps them, by path. One query instead of asking each
 * file; empty when MediaStore doesn't know the folder.
 */
fun Context.mediaStoreModifiedTimes(folder: String): Map<String, Long> {
    val prefix = "${folder.trimEnd('/')}/"
    val times = HashMap<String, Long>()
    val columns = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_MODIFIED)
    try {
        contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            columns,
            "${MediaStore.MediaColumns.DATA} LIKE ?",
            arrayOf("$prefix%"),
            null,
        )?.use { cursor ->
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val timeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val path = cursor.getString(pathColumn) ?: continue
                val seconds = cursor.getLong(timeColumn)
                // deeper files belong to subfolders, which are read on their own
                if (seconds > 0 && path.indexOf('/', prefix.length) < 0) times[path] = seconds * MILLIS_PER_SECOND
            }
        }
    } catch (e: Exception) {
        // MediaStore unavailable or the folder not indexed: the files are asked one by one then
    }
    return times
}

/** Lets MediaStore (and so galleries and music apps) know about new, changed or removed files. */
fun Context.rescanPaths(paths: List<String>, onDone: (() -> Unit)? = null) {
    if (paths.isEmpty()) {
        onDone?.invoke()
        return
    }
    var pending = paths.size
    MediaScannerConnection.scanFile(applicationContext, paths.toTypedArray(), null) { _, _ ->
        if (--pending == 0) onDone?.invoke()
    }
}

/**
 * The uri other apps get for a file: MediaStore's for media it knows (so galleries can work with it), otherwise one of
 * needafile's own file provider. Files decrypted from a vault for viewing are read from the vault instead, so locking it
 * ends the other apps' access.
 */
fun Context.getFilePublicUri(file: File): Uri = VaultSessions.shareUriFor(this, file)
    ?: mediaStoreUri(file.absolutePath)
    ?: FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)

private fun Context.mediaStoreUri(path: String): Uri? {
    val collection = when {
        path.isImageFast() -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        path.isVideoFast() -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }
    val id = firstValue(collection, MediaStore.MediaColumns._ID, "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path)) { getLong(it) }
    return id?.let { Uri.withAppendedPath(collection, it.toString()) }
}

/** A public uri for [path]: content uris stay as they are, file paths get one via [getFilePublicUri]. */
fun Context.ensurePublicUri(path: String): Uri {
    val uri = Uri.parse(path)
    return if (uri.scheme == "content") uri else getFilePublicUri(File(if (path.startsWith("/")) path else uri.path.orEmpty()))
}

/** The file path behind a uri where there is one, e.g. for files picked in the system's file app. */
fun Context.filePathOf(uri: Uri): String? {
    if (uri.scheme == "file") return uri.path
    if (uri.authority == EXTERNAL_STORAGE_AUTHORITY && DocumentsContract.isDocumentUri(this, uri)) {
        val documentId = DocumentsContract.getDocumentId(uri)
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "")
        return if (volume.equals(PRIMARY_STORAGE_ID, true)) "$internalStorage/$relative" else "/storage/$volume/$relative"
    }
    return firstValue(uri, MediaStore.MediaColumns.DATA) { getString(it) }
}

/** The name a content uri shows for its file, e.g. the original name of a shared file. */
fun Context.displayNameOf(uri: Uri): String? = firstValue(uri, OpenableColumns.DISPLAY_NAME) { getString(it) }

/** The mime type by the uri's file name, otherwise what its provider says; empty when neither knows. */
fun Context.mimeTypeOf(uri: Uri): String = uri.path?.getMimeType()?.takeIf { it.isNotEmpty() }
    ?: runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()

/** One [column] of the first row a query finds, null when there is none or the provider fails. */
private fun <T> Context.firstValue(
    uri: Uri,
    column: String,
    selection: String? = null,
    args: Array<String>? = null,
    read: Cursor.(Int) -> T?,
): T? = try {
    contentResolver.query(uri, arrayOf(column), selection, args, null)?.use { cursor ->
        val index = cursor.getColumnIndex(column)
        if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.read(index) else null
    }
} catch (e: Exception) {
    null
}

const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.provider"

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_STORAGE_ID = "primary"
private const val MILLIS_PER_SECOND = 1000L
private val RESTRICTED_FOLDERS = listOf("Android/data", "Android/obb")
