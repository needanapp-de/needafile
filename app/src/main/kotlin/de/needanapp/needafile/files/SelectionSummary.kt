package de.needanapp.needafile.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.needanapp.needafile.R
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.formatSize

/** What the floating selection card says about [items]: how many are selected and how big the files among them are. */
@Composable
fun selectionSummary(items: List<FileEntry>): String {
    val count = pluralStringResource(R.plurals.items, items.size, items.size)
    val files = items.filterNot { it.isDirectory }
    // folders would need counting through first, so only files sum up
    return if (files.isNotEmpty() && files.size == items.size && files.all { it.size >= 0 }) {
        stringResource(R.string.count_and_size, count, files.sumOf { it.size }.formatSize())
    } else {
        count
    }
}

/** The names of [items] running through below the summary, the first [MAX_NAMES] of them. */
fun selectionNames(items: List<FileEntry>) = items.take(MAX_NAMES).joinToString(", ") { it.name }

private const val MAX_NAMES = 20
