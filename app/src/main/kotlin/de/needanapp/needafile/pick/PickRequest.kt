package de.needanapp.needafile.pick

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.media.RingtoneManager
import de.needanapp.needafile.core.getCommonMimeType
import de.needanapp.needafile.core.getFilePublicUri
import de.needanapp.needafile.core.getMimeType
import java.io.File

/** What another app asked for when it opened needafile: pick files, pick a ringtone or choose where to save one. */
data class PickRequest(
    val kind: Kind,
    val allowMultiple: Boolean,
    /** Mime types the app accepts, `""` = everything. */
    val mimeTypes: List<String>,
    /** The name the app suggested for a new document, may be empty. */
    val fileName: String,
) {
    enum class Kind { FILE, RINGTONE, CREATE_DOCUMENT }

    companion object {
        fun from(intent: Intent?): PickRequest? {
            val kind = when (intent?.action) {
                Intent.ACTION_GET_CONTENT, Intent.ACTION_PICK -> Kind.FILE
                RingtoneManager.ACTION_RINGTONE_PICKER -> Kind.RINGTONE
                Intent.ACTION_CREATE_DOCUMENT -> Kind.CREATE_DOCUMENT
                else -> return null
            }
            val mimeTypes = if (kind == Kind.FILE) {
                intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList() ?: listOf(intent.type ?: "")
            } else {
                listOf("")
            }
            return PickRequest(
                kind = kind,
                allowMultiple = kind == Kind.FILE && intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
                mimeTypes = mimeTypes,
                fileName = intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty(),
            )
        }
    }
}

/** Hands the picked file to the asking app and closes. */
fun Activity.returnPickedFile(path: String) {
    val intent = Intent().apply {
        setDataAndType(getFilePublicUri(File(path)), path.getMimeType())
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    setResult(Activity.RESULT_OK, intent)
    finish()
}

/** Hands several picked files to the asking app and closes. */
fun Activity.returnPickedFiles(paths: List<String>) {
    val uris = paths.map { getFilePublicUri(File(it)) }
    val clipData = ClipData(CLIP_LABEL, arrayOf(paths.getCommonMimeType()), ClipData.Item(uris.first()))
    uris.drop(1).forEach { clipData.addItem(ClipData.Item(it)) }
    val intent = Intent().apply {
        this.clipData = clipData
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    setResult(Activity.RESULT_OK, intent)
    finish()
}

/** Hands the picked sound to the asking app and closes. */
fun Activity.returnPickedRingtone(path: String) {
    val uri = getFilePublicUri(File(path))
    val intent = Intent().apply {
        setDataAndType(uri, path.getMimeType())
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, uri)
    }
    setResult(Activity.RESULT_OK, intent)
    finish()
}

/** Lets the asking app write a new document named [fileName] into [folder], then closes. */
fun Activity.returnNewDocument(folder: String, fileName: String) {
    val file = File(folder, fileName)
    val intent = Intent().apply {
        setDataAndType(getFilePublicUri(file), file.path.getMimeType())
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    }
    setResult(Activity.RESULT_OK, intent)
    finish()
}

private const val CLIP_LABEL = "Attachment"
