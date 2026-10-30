package de.needanapp.needafile.files

import android.media.ExifInterface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ListRow
import de.needanapp.design.components.OptionsSheet
import de.needanapp.design.components.SegmentedRow
import de.needanapp.design.components.SettingsGroup
import de.needanapp.design.components.TextFieldRow
import de.needanapp.design.components.verticalScrollWithBar
import de.needanapp.needafile.R
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.isAValidFilename
import de.needanapp.needafile.settings.config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How several items get their new names. */
private enum class RenameMode(val label: Int) { ADD(R.string.rename_mode_add), REPLACE(R.string.rename_mode_replace), TEMPLATE(R.string.rename_mode_template) }

/**
 * Renames several files and folders at once: text added at the start or end, text replaced, or a template with the
 * date (taken or modified) and a running number. Extensions stay as they are; taken names get " (2)", " (3)" …
 * A preview shows the first new names.
 */
@Composable
fun RenameManySheet(items: List<FileEntry>, operations: FileOperations, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(RenameMode.ADD) }
    var text by rememberSaveable { mutableStateOf("") }
    var atStart by rememberSaveable { mutableStateOf(false) }
    var find by rememberSaveable { mutableStateOf("") }
    var replaceWith by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable { mutableStateOf(context.config.lastRenamePatternUsed.ifEmpty { DEFAULT_TEMPLATE }) }
    var dates by remember { mutableStateOf<List<Calendar>?>(null) }

    LaunchedEffect(mode) {
        if (mode == RenameMode.TEMPLATE && dates == null) dates = withContext(Dispatchers.IO) { items.map { dateOf(it.path) } }
    }

    val newNames = remember(items, mode, text, atStart, find, replaceWith, template, dates) {
        items.mapIndexed { index, item ->
            val (base, extension) = item.nameParts()
            val newBase = when (mode) {
                RenameMode.ADD -> if (atStart) text + base else base + text
                RenameMode.REPLACE -> if (find.isEmpty()) base else base.replace(find, replaceWith)
                RenameMode.TEMPLATE -> dates?.let { fillTemplate(template, it[index], index + 1, items.size) } ?: base
            }
            newBase + extension
        }
    }
    val planned = remember(newNames) { withFreeNames(items, newNames) }
    val changes = planned.count { (item, name) -> name != item.name }
    val isValid = newNames.all { it.isNotBlank() && it.isAValidFilename() }

    OptionsSheet(onDismiss = onDismiss, title = stringResource(R.string.rename), skipPartiallyExpanded = true) {
        Column(Modifier.verticalScrollWithBar()) {
            SettingsGroup(pluralStringResource(R.plurals.selected_items, items.size, items.size)) {
                row { SegmentedRow(it, null, RenameMode.entries, mode, label = { stringResource(it.label) }, onSelect = { mode = it }) }
                when (mode) {
                    RenameMode.ADD -> {
                        row { TextFieldRow(it, text, { text = it }, stringResource(R.string.rename_text)) }
                        row {
                            SegmentedRow(it, null, listOf(true, false), atStart, label = { start ->
                                stringResource(if (start) R.string.rename_at_start else R.string.rename_at_end)
                            }, onSelect = { atStart = it })
                        }
                    }

                    RenameMode.REPLACE -> {
                        row { TextFieldRow(it, find, { find = it }, stringResource(R.string.rename_find)) }
                        row { TextFieldRow(it, replaceWith, { replaceWith = it }, stringResource(R.string.rename_replace_with)) }
                    }

                    RenameMode.TEMPLATE -> row {
                        TextFieldRow(it, template, { template = it }, stringResource(R.string.rename_mode_template), supportingText = stringResource(R.string.rename_template_help))
                    }
                }
            }
            SettingsGroup(stringResource(R.string.rename_preview)) {
                planned.take(PREVIEW_COUNT).forEach { (item, name) ->
                    row {
                        ListRow(
                            it,
                            name,
                            summary = item.name.takeIf { old -> old != name },
                            icon = if (item.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.ArrowForward,
                        )
                    }
                }
            }
            if (planned.size > PREVIEW_COUNT) {
                Text(
                    pluralStringResource(R.plurals.rename_more, planned.size - PREVIEW_COUNT, planned.size - PREVIEW_COUNT),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(enabled = isValid && changes > 0, onClick = {
                    onDismiss()
                    if (mode == RenameMode.TEMPLATE) context.config.lastRenamePatternUsed = template
                    operations.renameAll(planned.filter { (item, name) -> name != item.name })
                }) { Text(stringResource(R.string.rename)) }
            }
        }
    }
}

/** Name and extension (with its dot); folders and dot files have none. */
private fun FileEntry.nameParts(): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    return if (isDirectory || dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
}

/** Names that are already taken in the folder, or twice in the plan, get " (2)", " (3)" … */
private fun withFreeNames(items: List<FileEntry>, names: List<String>): List<Pair<FileEntry, String>> {
    val claimed = HashSet<String>()
    return items.zip(names).map { (item, name) ->
        val parent = File(item.path).parentFile
        var candidate = name
        var number = 2
        fun isTaken(candidate: String) = candidate != item.name && (File(parent, candidate).exists() || "${parent?.path}/$candidate" in claimed)
        while (isTaken(candidate)) {
            val dot = name.lastIndexOf('.').takeIf { !item.isDirectory && it > 0 } ?: name.length
            candidate = "${name.substring(0, dot)} ($number)${name.substring(dot)}"
            number++
        }
        claimed.add("${parent?.path}/$candidate")
        item to candidate
    }
}

/** When a picture was taken, otherwise when the file was changed last. */
private fun dateOf(path: String): Calendar {
    val calendar = Calendar.getInstance()
    val taken = runCatching {
        val exif = ExifInterface(path)
        (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))
            ?.let { SimpleDateFormat(EXIF_DATE, Locale.ENGLISH).parse(it) }
    }.getOrNull()
    calendar.timeInMillis = taken?.time ?: File(path).lastModified()
    return calendar
}

private fun fillTemplate(template: String, date: Calendar, number: Int, count: Int): String {
    fun two(value: Int) = "%02d".format(value)
    return template
        .replace("%Y", date.get(Calendar.YEAR).toString())
        .replace("%M", two(date.get(Calendar.MONTH) + 1))
        .replace("%D", two(date.get(Calendar.DAY_OF_MONTH)))
        .replace("%h", two(date.get(Calendar.HOUR_OF_DAY)))
        .replace("%m", two(date.get(Calendar.MINUTE)))
        .replace("%s", two(date.get(Calendar.SECOND)))
        .replace("%i", "%0${count.toString().length}d".format(number))
}

private const val DEFAULT_TEMPLATE = "%Y%M%D_%h%m%s"
private const val EXIF_DATE = "yyyy:MM:dd HH:mm:ss"
private const val PREVIEW_COUNT = 5
