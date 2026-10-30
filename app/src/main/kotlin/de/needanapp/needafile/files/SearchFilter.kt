package de.needanapp.needafile.files

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ChipRow
import de.needanapp.design.theme.emphasizedSpec
import java.util.Locale

/** A type to narrow a category to, e.g. "jpg" for .jpg and .jpeg files. */
data class FileTypeChoice(val label: String, val extensions: Set<String> = setOf(label))

/** Files of [category] only, and only of [types] when any are picked; folders never match. */
data class SearchFilter(val category: FileCategory, val types: Set<FileTypeChoice> = emptySet()) {
    fun matches(name: String, isDirectory: Boolean): Boolean = when {
        isDirectory -> false
        types.isEmpty() -> fileCategory(name) == category
        else -> name.substringAfterLast('.', "").lowercase(Locale.ROOT).let { extension -> types.any { extension in it.extensions } }
    }
}

private fun choices(vararg labels: String) = labels.map { FileTypeChoice(it) }

/** The categories the search offers to pick, each with its common types; a category alone finds all of its kind. */
private val SEARCH_CATEGORIES = linkedMapOf(
    FileCategory.IMAGES to listOf(FileTypeChoice("jpg", setOf("jpg", "jpeg"))) + choices("png", "gif", "webp") +
        FileTypeChoice("heic", setOf("heic", "heif")) + choices("svg", "dng"),
    FileCategory.VIDEOS to choices("mp4", "mkv", "webm", "mov", "avi", "3gp"),
    FileCategory.AUDIO to choices("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "amr"),
    FileCategory.DOCUMENTS to choices("pdf", "txt", "md", "doc", "docx", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "epub", "csv"),
    FileCategory.ARCHIVES to choices("zip", "7z", "rar", "tar") + FileTypeChoice("gz", setOf("gz", "tgz")) + choices("xz", "bz2"),
    FileCategory.APPS to choices("apk", "apks", "xapk"),
)

private val FileCategory.icon: ImageVector
    get() = when (this) {
        FileCategory.IMAGES -> Icons.Outlined.Image
        FileCategory.VIDEOS -> Icons.Outlined.Movie
        FileCategory.AUDIO -> Icons.Outlined.MusicNote
        FileCategory.DOCUMENTS -> Icons.Outlined.Description
        FileCategory.ARCHIVES -> Icons.Outlined.FolderZip
        FileCategory.APPS -> Icons.Outlined.Android
        FileCategory.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }

/** Below the search field: the categories to pick one of, then, if wanted, the exact types within it. */
@Composable
fun SearchFilterRows(filter: SearchFilter?, onChange: (SearchFilter?) -> Unit) {
    val fade = emphasizedSpec<Float>()
    val resize = emphasizedSpec<IntSize>()
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        ChipRow(SEARCH_CATEGORIES.keys.toList()) { category ->
            val isSelected = filter?.category == category
            FilterChip(
                selected = isSelected,
                onClick = { onChange(if (isSelected) null else SearchFilter(category)) },
                label = { Text(stringResource(category.label)) },
                leadingIcon = { Icon(category.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
        AnimatedContent(
            targetState = filter?.category,
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) using SizeTransform(clip = true) { _, _ -> resize } },
            label = "search_types",
        ) { category ->
            if (category != null) {
                // while fading out after the category was dropped, its types show unpicked
                val picked = filter?.takeIf { it.category == category }?.types.orEmpty()
                ChipRow(SEARCH_CATEGORIES.getValue(category)) { type ->
                    val isSelected = type in picked
                    FilterChip(
                        selected = isSelected,
                        onClick = { onChange(SearchFilter(category, if (isSelected) picked - type else picked + type)) },
                        label = { Text(type.label) },
                    )
                }
            }
        }
    }
}
