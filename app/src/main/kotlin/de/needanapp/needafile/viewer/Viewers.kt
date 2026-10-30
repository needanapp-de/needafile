package de.needanapp.needafile.viewer

import android.app.Activity
import android.content.Intent
import de.needanapp.needafile.core.EXTRA_FILE_PATH
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.openFile
import de.needanapp.needafile.editor.TextEditorActivity
import de.needanapp.needafile.transfer.TransferManager.toBrowserFile
import de.needanapp.needafile.vault.getVaultLocation
import de.needanapp.needafile.vault.isVaultPath
import de.needanapp.needafile.viewer.document.DocumentViewerActivity
import de.needanapp.needafile.viewer.document.Documents
import de.needanapp.needafile.viewer.document.ViewerDocumentKind
import de.needanapp.needafile.viewer.image.ImageViewerActivity
import de.needanapp.needafile.viewer.image.VaultGallery
import java.io.File

/** What needafile shows itself instead of handing a file to another app. */
enum class ViewerKind { IMAGE, DOCUMENT, TEXT }

/** needafile's own viewers: which files they show, and opening a file in the right one. */
object Viewers {
    /** Extra for [open]: show only this file, e.g. one downloaded from a server, without its neighbors. */
    const val EXTRA_SINGLE = "de.needanapp.needafile.viewer.SINGLE"

    /** Extra for the image viewer: the token of a [VaultGallery] to swipe through. */
    const val EXTRA_VAULT_GALLERY = "de.needanapp.needafile.viewer.VAULT_GALLERY"

    // what Android 12 decodes itself, plus SVG and JPEG XL through Coil
    private val imageExtensions = setOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "apng", "webp", "gif", "bmp", "heic", "heif", "avif", "jxl", "svg", "ico", "dng",
    )
    private val textExtensions = setOf(
        "txt", "text", "log", "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg", "properties", "env", "srt", "sub",
        "sh", "bat", "py", "kt", "kts", "java", "c", "h", "cpp", "js", "ts", "css", "gradle", "sql", "nfo",
    )

    fun kindOf(name: String): ViewerKind? = when (name.substringAfterLast('.', "").lowercase()) {
        in imageExtensions -> ViewerKind.IMAGE
        in textExtensions -> ViewerKind.TEXT
        // plain text goes to the editor above, everything else to other apps
        else -> when (Documents.kindOf(name)) {
            null, ViewerDocumentKind.TEXT -> null
            else -> ViewerKind.DOCUMENT
        }
    }

    fun isImage(name: String) = kindOf(name) == ViewerKind.IMAGE

    /** Opens [path] in needafile's own viewer; false when there is none for it. [single]: no swiping to neighbors. */
    fun open(activity: Activity, path: String, single: Boolean = false): Boolean {
        val target = when (kindOf(File(path).name)) {
            ViewerKind.IMAGE -> ImageViewerActivity::class.java
            ViewerKind.DOCUMENT -> DocumentViewerActivity::class.java
            ViewerKind.TEXT -> TextEditorActivity::class.java
            null -> return false
        }
        activity.startActivity(Intent(activity, target).putExtra(EXTRA_FILE_PATH, path).putExtra(EXTRA_SINGLE, single))
        return true
    }

    /**
     * Opens the vault picture [start] in the image viewer, which swipes through the other pictures among [shown] (the
     * folder's entries in the order of the file list) and decrypts each one as it comes on screen.
     */
    fun openVaultPictures(activity: Activity, shown: List<FileEntry>, start: FileEntry) {
        val location = start.path.getVaultLocation()
        val pictures = shown.filter { !it.isDirectory && isImage(it.name) && it.path.isVaultPath() && it.path.getVaultLocation() == location }
            .ifEmpty { listOf(start) }
        val entries = pictures.map { VaultGallery.Entry(it.path, it.toBrowserFile()) }
        val token = VaultGallery.put(VaultGallery.Gallery(location, entries, pictures.indexOfFirst { it.path == start.path }.coerceAtLeast(0)))
        activity.startActivity(Intent(activity, ImageViewerActivity::class.java).putExtra(EXTRA_VAULT_GALLERY, token))
    }

    /** [open], or the app the system picks for [path] when needafile has no viewer for it. */
    fun openOrHandOver(activity: Activity, path: String, single: Boolean = false) {
        if (!open(activity, path, single)) activity.openFile(path, false)
    }
}
