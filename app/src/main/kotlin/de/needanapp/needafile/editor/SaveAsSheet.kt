package de.needanapp.needafile.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ConfirmDialog
import de.needanapp.design.components.ListRow
import de.needanapp.design.components.OptionsSheet
import de.needanapp.design.components.SettingsGroup
import de.needanapp.design.components.SignalButton
import de.needanapp.design.components.TextFieldRow
import de.needanapp.needafile.R
import de.needanapp.needafile.core.displayPath
import de.needanapp.needafile.core.isAValidFilename
import de.needanapp.needafile.files.FolderPick
import de.needanapp.needafile.files.FolderPickerSheet
import java.io.File

/** Name and folder for saving as a new file (text, edited pictures); asks before replacing a file that is already there. */
@Composable
fun SaveAsSheet(name: String, folder: String, onDismiss: () -> Unit, onSave: (path: String) -> Unit) {
    val context = LocalContext.current
    var fileName by rememberSaveable { mutableStateOf(name) }
    var targetFolder by rememberSaveable { mutableStateOf(folder) }
    var picking by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<String?>(null) }
    val isValid = fileName.isNotBlank() && fileName.isAValidFilename()

    OptionsSheet(onDismiss = onDismiss, title = stringResource(R.string.save_as), skipPartiallyExpanded = true) {
        SettingsGroup(null) {
            row {
                TextFieldRow(
                    it,
                    fileName,
                    { fileName = it },
                    stringResource(R.string.filename),
                    error = stringResource(R.string.invalid_name).takeIf { fileName.isNotEmpty() && !isValid },
                )
            }
        }
        SettingsGroup(stringResource(R.string.save_to)) {
            row { ListRow(it, context.displayPath(targetFolder), icon = Icons.Outlined.Folder, onClick = { picking = true }) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            SignalButton(enabled = isValid, onClick = {
                val path = File(targetFolder, fileName.trim()).path
                if (File(path).exists()) replacing = path else onSave(path)
            }) { Text(stringResource(R.string.save)) }
        }
    }

    if (picking) {
        FolderPickerSheet(
            FolderPick(R.string.select_folder, R.string.use_this_folder, targetFolder) { targetFolder = it },
            onDismiss = { picking = false },
        )
    }
    replacing?.let { path ->
        ConfirmDialog(
            title = stringResource(R.string.replace_file_title),
            message = stringResource(R.string.replace_file_question, File(path).name),
            confirmLabel = stringResource(R.string.replace),
            onConfirm = { onSave(path) },
            onDismiss = { replacing = null },
            isDestructive = true,
            icon = Icons.Outlined.Autorenew,
        )
    }
}
