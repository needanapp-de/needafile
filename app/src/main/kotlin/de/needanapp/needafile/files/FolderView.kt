package de.needanapp.needafile.files

import androidx.annotation.StringRes
import de.needanapp.needafile.R
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.ViewType
import de.needanapp.needafile.core.isImageFast
import de.needanapp.needafile.core.isVideoFast
import de.needanapp.needafile.settings.Config
import kotlin.math.roundToInt

/** The views the browser offers, in the order the options show them. */
val VIEW_TYPES = listOf(ViewType.LIST, ViewType.DETAILS, ViewType.GRID, ViewType.GALLERY)

@StringRes
fun viewTypeLabel(type: Int) = when (type) {
    ViewType.DETAILS -> R.string.view_details
    ViewType.GRID -> R.string.view_grid
    ViewType.GALLERY -> R.string.view_gallery
    else -> R.string.view_list
}

/** How entries are grouped under headings; [id] is what the settings store. Folders always come first, on their own. */
enum class GroupBy(val id: Int, @StringRes val label: Int) {
    NONE(0, R.string.group_none),
    DATE(1, R.string.group_date),
    TYPE(2, R.string.group_type),
    SIZE(3, R.string.group_size),
    NAME(4, R.string.group_name),
    ;

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * How a folder is shown: view type, how big the entries are ([zoom], 0 = smallest, see [zoomLevels]) and the grouping.
 * [isCustom] when the folder has its own, otherwise the general ones apply.
 */
data class FolderView(val type: Int, val zoom: Int, val groupBy: GroupBy, val isCustom: Boolean)

/** List and details: compact, normal, large. Grid and gallery: one level per column count. */
fun zoomLevels(type: Int) = when (type) {
    ViewType.GRID -> GRID_COLUMNS_NARROW.size
    ViewType.GALLERY -> GALLERY_COLUMNS_NARROW.size
    else -> ZOOM_LEVELS_ROWS
}

fun defaultZoom(type: Int) = when (type) {
    ViewType.GRID -> 2
    ViewType.GALLERY -> 1
    else -> ZOOM_NORMAL
}

const val ZOOM_COMPACT = 0
const val ZOOM_NORMAL = 1
const val ZOOM_LARGE = 2
private const val ZOOM_LEVELS_ROWS = 3

// phones held upright get fixed column counts; wider screens fit as many cells of the level's size as there is room for
private val GRID_COLUMNS_NARROW = intArrayOf(6, 5, 4, 3, 2)
private val GRID_CELL_DP = floatArrayOf(70f, 85f, 105f, 140f, 200f)
private val GALLERY_COLUMNS_NARROW = intArrayOf(4, 3, 2, 1)
private val GALLERY_CELL_DP = floatArrayOf(100f, 140f, 200f, 300f)
private const val NARROW_WIDTH_DP = 600f

/** Columns of the grid or gallery at [zoom] for a screen [widthDp] wide; bigger zoom levels never get more columns. */
fun gridColumns(type: Int, zoom: Int, widthDp: Float): Int {
    val narrow = if (type == ViewType.GALLERY) GALLERY_COLUMNS_NARROW else GRID_COLUMNS_NARROW
    val cells = if (type == ViewType.GALLERY) GALLERY_CELL_DP else GRID_CELL_DP
    val level = zoom.coerceIn(0, narrow.lastIndex)
    if (widthDp < NARROW_WIDTH_DP) return narrow[level]
    var columns = Int.MAX_VALUE
    for (i in 0..level) {
        columns = (widthDp / cells[i]).roundToInt().coerceAtMost(columns - 1).coerceAtLeast(1)
    }
    return columns
}

/** The view [path] is shown in, from the settings. */
fun Config.folderView(path: String): FolderView {
    val isCustom = hasCustomViewType(path)
    val type = getFolderViewType(path).takeIf { it in VIEW_TYPES } ?: ViewType.DETAILS
    val zoom = if (isCustom) getFolderZoom(path, type, defaultZoom(type)) else getZoom(type, defaultZoom(type))
    val groupBy = GroupBy.of(if (isCustom) getFolderGroupBy(path) else groupBy)
    return FolderView(type, zoom.coerceIn(0, zoomLevels(type) - 1), groupBy, isCustom)
}

/** Saves [view] for [path] when it is the folder's own ([FolderView.isCustom]), otherwise as the general view. */
fun Config.saveFolderView(path: String, view: FolderView) {
    if (view.isCustom) {
        saveFolderViewType(path, view.type)
        saveFolderZoom(path, view.zoom)
        saveFolderGroupBy(path, view.groupBy.id)
    } else {
        removeFolderViewType(path)
        viewType = view.type
        setZoom(view.type, view.zoom)
        groupBy = view.groupBy.id
    }
}

/** The general zoom level of [type]. */
fun Config.generalZoom(type: Int) = getZoom(type, defaultZoom(type)).coerceIn(0, zoomLevels(type) - 1)

/** Whether more than 70 % of the files (at least a few) are pictures or videos, so the gallery suits the folder. */
fun isMediaFolder(items: List<FileEntry>): Boolean {
    val files = items.filter { !it.isDirectory }
    if (files.size < AUTO_GALLERY_MIN_FILES) return false
    val media = files.count { it.path.isImageFast() || it.path.isVideoFast() }
    return media * 10 > files.size * 7
}

private const val AUTO_GALLERY_MIN_FILES = 4
