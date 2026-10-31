package de.needanapp.needafile.pick

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Save
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.needanapp.design.components.ConfirmDialog
import de.needanapp.design.components.TextInputDialog
import de.needanapp.needafile.R
import de.needanapp.needafile.core.isAValidFilename
import de.needanapp.needafile.core.isAudioFast
import de.needanapp.needafile.core.toast
import java.io.File

/** Questions while saving a document for another app. */
sealed interface PickDialog {
    /** The app suggested no name. */
    data object FileName : PickDialog

    /** A file of that name is there already. */
    data class Replace(val path: String) : PickDialog
}

/** A tap on a file while another app waits for one. */
fun Activity.onFileTapped(request: PickRequest, path: String, show: (PickDialog) -> Unit) {
    when (request.kind) {
        PickRequest.Kind.FILE -> returnPickedFile(path)
        PickRequest.Kind.RINGTONE -> if (path.isAudioFast()) returnPickedRingtone(path) else toast(R.string.select_audio_file)
        PickRequest.Kind.CREATE_DOCUMENT -> show(PickDialog.Replace(path))
    }
}

/** "Save here": asks for a name if the app gave none, and before replacing a file. */
fun Activity.saveHere(request: PickRequest, folder: String, show: (PickDialog) -> Unit) {
    val file = File(folder, request.fileName)
    when {
        request.fileName.isEmpty() -> show(PickDialog.FileName)
        file.exists() -> show(PickDialog.Replace(file.path))
        else -> returnNewDocument(folder, request.fileName)
    }
}

/** The main action of the pill while picking, or a hint when tapping a file is the action. */
data class PickPillContent(val label: String, val icon: ImageVector?, val isAction: Boolean)

@Composable
fun pickPillContent(request: PickRequest, selectedCount: Int): PickPillContent = when {
    request.kind == PickRequest.Kind.CREATE_DOCUMENT -> PickPillContent(stringResource(R.string.save_here), Icons.Outlined.Save, isAction = true)
    request.kind == PickRequest.Kind.RINGTONE -> PickPillContent(stringResource(R.string.pick_sound), null, isAction = false)
    selectedCount > 0 -> PickPillContent(pluralStringResource(R.plurals.use_picked_files, selectedCount, selectedCount), Icons.Outlined.Check, isAction = true)
    request.allowMultiple -> PickPillContent(stringResource(R.string.pick_files), null, isAction = false)
    else -> PickPillContent(stringResource(R.string.pick_file), null, isAction = false)
}

@Composable
fun PickDialogHost(dialog: PickDialog?, folder: String, onDismiss: () -> Unit, show: (PickDialog) -> Unit) {
    val activity = LocalActivity.current ?: return
    when (dialog) {
        null -> Unit

        PickDialog.FileName -> TextInputDialog(
            title = stringResource(R.string.save_as),
            label = stringResource(R.string.filename),
            initialValue = "",
            confirmLabel = stringResource(R.string.save_here),
            icon = Icons.Outlined.Save,
            onConfirm = { name ->
                val file = File(folder, name)
                if (file.exists()) show(PickDialog.Replace(file.path)) else activity.returnNewDocument(folder, name)
            },
            onDismiss = onDismiss,
            validate = { name ->
                when {
                    name.isBlank() -> activity.getString(R.string.empty_name)
                    !name.trim().isAValidFilename() -> activity.getString(R.string.invalid_name)
                    else -> null
                }
            },
        )

        is PickDialog.Replace -> {
            val file = File(dialog.path)
            ConfirmDialog(
                title = stringResource(R.string.replace_file_title),
                message = stringResource(R.string.replace_file_question, file.name),
                confirmLabel = stringResource(R.string.replace),
                onConfirm = { activity.returnNewDocument(file.parent ?: folder, file.name) },
                onDismiss = onDismiss,
                isDestructive = true,
                icon = Icons.Outlined.Autorenew,
            )
        }
    }
}
