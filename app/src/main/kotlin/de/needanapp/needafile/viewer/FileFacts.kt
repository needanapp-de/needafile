package de.needanapp.needafile.viewer

import android.content.Context
import android.net.Uri
import de.needanapp.needafile.R
import de.needanapp.needafile.core.formatDate
import de.needanapp.needafile.core.formatSize
import de.needanapp.needafile.vault.VaultSessions
import de.needanapp.needafile.vault.readableVaultPath
import java.io.File

/**
 * What a viewer's card says about the file it shows: [summary] (size and date) below its name, where it lies as people
 * read it ([shownPath]), and its [localPath] when it is the file itself rather than a copy from a vault or a server.
 */
internal class FileFacts(val summary: String?, val shownPath: String?, val localPath: String?)

/**
 * Size, date and place of the file at [path] (or only [uri] from another app); a vault copy shows the vault file's
 * date and path, a server copy none. Blocks.
 */
internal fun fileFacts(context: Context, path: String?, uri: Uri): FileFacts {
    val origin = path?.let { VaultSessions.originOf(it) }
    val isCopy = origin != null || path != null && listOfNotNull(context.cacheDir, context.externalCacheDir).any { path.startsWith(it.path + "/") }
    val size = path?.let { File(it).length() } ?: runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull()
    val modified = origin?.item?.lastModified ?: path?.takeIf { !isCopy }?.let { File(it).lastModified() }
    val facts = listOfNotNull(size?.takeIf { it >= 0 }?.formatSize(), modified?.takeIf { it > 0 }?.formatDate(context))
    return FileFacts(
        // one after the other, separated as size and date are elsewhere
        summary = facts.reduceOrNull { joined, next -> context.getString(R.string.size_and_date, joined, next) },
        shownPath = origin?.path?.readableVaultPath() ?: path?.takeIf { !isCopy },
        localPath = path?.takeIf { !isCopy },
    )
}
