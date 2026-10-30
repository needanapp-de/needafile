package de.needanapp.needafile.files

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.ui.graphics.vector.ImageVector
import de.needanapp.needafile.R

/** Everything that can be done with files from an opened card or the selection card. */
enum class FileAction(@StringRes val label: Int) {
    COPY(R.string.copy),
    CUT(R.string.cut),
    RENAME(R.string.rename),
    SHARE(R.string.share),
    DELETE(R.string.delete),
    OPEN_WITH(R.string.open_with),
    OPEN_AS(R.string.open_as),
    MOVE_TO(R.string.move_to),
    COPY_TO(R.string.copy_to),
    COMPRESS(R.string.compress),
    DECOMPRESS(R.string.decompress),
    EXTRACT_TO(R.string.extract_to),
    ARCHIVE_INFO(R.string.archive_details),
    HIDE(R.string.hide),
    UNHIDE(R.string.unhide),
    CREATE_SHORTCUT(R.string.create_shortcut),
    SET_AS(R.string.set_as),
    COPY_PATH(R.string.copy_path),
    PROPERTIES(R.string.properties),
    SELECT(R.string.select_item),
    SELECT_ALL(R.string.select_all);

    /** The Material symbol of the action. */
    val vector: ImageVector
        get() = when (this) {
            COPY -> Icons.Outlined.ContentCopy
            CUT -> Icons.Outlined.ContentCut
            RENAME -> Icons.Outlined.DriveFileRenameOutline
            SHARE -> Icons.Outlined.Share
            DELETE -> Icons.Outlined.Delete
            OPEN_WITH -> Icons.AutoMirrored.Outlined.OpenInNew
            OPEN_AS -> Icons.AutoMirrored.Outlined.Article
            MOVE_TO -> Icons.AutoMirrored.Outlined.DriveFileMove
            COPY_TO -> Icons.Outlined.FileCopy
            COMPRESS -> Icons.Outlined.FolderZip
            DECOMPRESS, EXTRACT_TO -> Icons.Outlined.Unarchive
            ARCHIVE_INFO -> Icons.Outlined.Lock
            HIDE -> Icons.Outlined.VisibilityOff
            UNHIDE -> Icons.Outlined.Visibility
            CREATE_SHORTCUT -> Icons.Outlined.AddToHomeScreen
            SET_AS -> Icons.Outlined.Wallpaper
            COPY_PATH -> Icons.Outlined.Link
            PROPERTIES -> Icons.Outlined.Info
            SELECT -> Icons.Outlined.CheckCircle
            SELECT_ALL -> Icons.Outlined.SelectAll
        }

    /** The label where room is short, as in the opened card. */
    @get:StringRes
    val shortLabel: Int
        get() = if (this == COPY_PATH) R.string.copy_path_short else label

    companion object {
        /** The rest of the actions where an opened card shows more, grouped by what they are for. */
        val CARD_GROUPS = listOf(
            R.string.card_group_open to listOf(OPEN_WITH, OPEN_AS, SHARE, SET_AS),
            R.string.card_group_place to listOf(COPY_TO, MOVE_TO, COMPRESS, DECOMPRESS, EXTRACT_TO),
            R.string.card_group_manage to listOf(CREATE_SHORTCUT, HIDE, UNHIDE, COPY_PATH, ARCHIVE_INFO, PROPERTIES),
        )
    }
}
