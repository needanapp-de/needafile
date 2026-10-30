package de.needanapp.needafile.files

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.text.format.DateUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.needanapp.design.components.CardMenu
import de.needanapp.design.components.CardStatus
import de.needanapp.design.components.CardTileGroups
import de.needanapp.design.components.Detail
import de.needanapp.design.components.FloatingItemCard
import de.needanapp.design.components.StatusTile
import de.needanapp.design.components.StatusTone
import de.needanapp.design.components.detailLines
import de.needanapp.needafile.R
import de.needanapp.needafile.archive.ArchiveDetails
import de.needanapp.needafile.archive.ArchiveInspector
import de.needanapp.needafile.archive.archiveDetailLines
import de.needanapp.needafile.archive.archiveDetails
import de.needanapp.needafile.archive.isArchiveFile
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.formatDate
import de.needanapp.needafile.core.formatSize
import de.needanapp.needafile.core.getMimeType
import de.needanapp.needafile.core.getParentPath
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.vault.readableVaultPath
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Size and content of the chosen items, counted in the background. */
private data class Measurement(val size: Long, val files: Int, val folders: Int)

/** What the info card unfolds below its actions: what is known about the items, or the further actions. */
enum class InfoFold { DETAILS, MORE }

/**
 * [items] as a card floating at the bottom in place of the pill, the way the image viewer shows a picture: the name and
 * its main facts, the [menu]'s actions, and below them what is known about them – size, content, date and place, for
 * media resolution and length, for an archive its format and encryption, for a file its checksums – or, with [fold]
 * [InfoFold.MORE], the further actions. Back runs [onBack]. It slides away while [visible] is false.
 */
@Composable
fun FileInfoCard(items: List<FileEntry>, menu: CardMenu, fold: InfoFold, onBack: () -> Unit, visible: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val showHidden = context.config.showHidden
    val single = items.singleOrNull()
    val measurement by produceState<Measurement?>(null, items) { value = measure(items.map { it.path }, showHidden) }
    val media by produceState(emptyList<Detail>(), single) {
        value = if (single != null && !single.isDirectory) withContext(Dispatchers.IO) { mediaDetails(context, single.path) } else emptyList()
    }
    // an archive's format and encryption, or why they could not be read
    val archive by produceState<Result<ArchiveDetails>?>(null, single) {
        value = if (single != null && !single.isDirectory && single.path.isArchiveFile()) {
            withContext(Dispatchers.IO) { runCatching { archiveDetails(context, ArchiveInspector.inspect(single.path)) } }
        } else {
            null
        }
    }

    val loading = stringResource(R.string.loading)
    val size = measurement?.size?.formatSize() ?: loading
    val content = measurement?.let {
        stringResource(
            R.string.files_and_folders,
            pluralStringResource(R.plurals.count_files, it.files, it.files),
            pluralStringResource(R.plurals.count_folders, it.folders, it.folders),
        )
    } ?: loading
    val modified = remember(single) { single?.let { File(it.path).lastModified().takeIf { time -> time > 0 } ?: it.modified } ?: 0L }
    val general = buildList {
        if (single != null) {
            if (single.isDirectory) {
                add(Detail(stringResource(R.string.size), size))
                add(Detail(stringResource(R.string.archive_info_content), content))
            } else {
                add(Detail(stringResource(R.string.file_type), single.path.getMimeType().ifEmpty { "–" }))
                add(Detail(stringResource(R.string.size), size))
            }
            if (modified > 0) add(Detail(stringResource(R.string.last_modified), modified.formatDate(context)))
            add(Detail(stringResource(R.string.path), single.path.readableVaultPath()))
        } else {
            add(Detail(stringResource(R.string.size), size))
            add(Detail(stringResource(R.string.archive_info_content), content))
            items.map { it.path.getParentPath() }.distinct().singleOrNull()?.let { add(Detail(stringResource(R.string.path), it.readableVaultPath())) }
        }
    }
    val title = single?.name ?: pluralStringResource(R.plurals.selected_items, items.size, items.size)
    val subtitle = when {
        single != null -> describeItem(context, single)
        measurement != null -> stringResource(R.string.size_and_date, size, content)
        else -> selectionSummary(items)
    }

    FloatingItemCard(
        title = title,
        subtitle = subtitle,
        menu = menu,
        expanded = fold == InfoFold.MORE,
        onFold = onBack,
        visible = visible,
        modifier = modifier,
        details = if (fold == InfoFold.DETAILS) {
            {
                CardTileGroups {
                    label(stringResource(R.string.card_general))
                    detailLines(general)
                    if (media.isNotEmpty()) {
                        label(stringResource(R.string.card_media))
                        detailLines(media)
                    }
                    archive?.onSuccess { archiveDetailLines(it) }?.onFailure { error ->
                        label(stringResource(R.string.card_archive))
                        val status = CardStatus(context.describeError(error), Icons.Outlined.ErrorOutline, StatusTone.ERROR)
                        line({ m, shape -> StatusTile(status, shape, m) })
                    }
                    if (single != null && !single.isDirectory) {
                        label(stringResource(R.string.checksums))
                        checksumLines(single.path)
                    }
                }
            }
        } else {
            null
        },
    )
}

/** Adds up sizes and counts files and folders below [paths]; hidden ones only when hidden files are shown. */
private suspend fun measure(paths: List<String>, showHidden: Boolean): Measurement = withContext(Dispatchers.IO) {
    var size = 0L
    var files = 0
    var folders = 0
    paths.forEach { path ->
        File(path).walkTopDown()
            .onEnter { showHidden || it.path == path || !it.isHidden }
            .forEach { file ->
                ensureActive()
                when {
                    file.path == path && file.isDirectory -> Unit
                    !showHidden && file.isHidden && file.path != path -> Unit
                    file.isDirectory -> folders++
                    else -> {
                        files++
                        size += file.length()
                    }
                }
            }
    }
    Measurement(size, files, folders)
}

/** Resolution of pictures and videos, length of audio and video, artist and album of music. */
private fun mediaDetails(context: Context, path: String): List<Detail> {
    val type = path.getMimeType().substringBefore('/')
    val details = ArrayList<Detail>()
    fun add(label: Int, value: String?) {
        if (!value.isNullOrBlank()) details.add(Detail(context.getString(label), value))
    }

    when (type) {
        "image" -> {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                add(R.string.resolution, context.getString(R.string.resolution_value, options.outWidth, options.outHeight))
            }
        }

        "video", "audio" -> runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(path)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                if (width != null && height != null) add(R.string.resolution, context.getString(R.string.resolution_value, width, height))
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                    add(R.string.duration, DateUtils.formatElapsedTime(it / MILLIS_PER_SECOND))
                }
                add(R.string.artist, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                add(R.string.album, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            }
        }
    }
    return details
}

private const val MILLIS_PER_SECOND = 1000L
