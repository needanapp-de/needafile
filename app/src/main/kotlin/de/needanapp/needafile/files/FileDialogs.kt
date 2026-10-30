package de.needanapp.needafile.files

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.EnhancedEncryption
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ActionTile
import de.needanapp.design.components.CardSpacing
import de.needanapp.design.components.ConfirmDialog
import de.needanapp.design.components.MarqueeText
import de.needanapp.design.components.OptionsSheet
import de.needanapp.design.components.TextInputDialog
import de.needanapp.design.components.TileCard
import de.needanapp.design.components.verticalScrollWithBar
import de.needanapp.needafile.R
import de.needanapp.needafile.archive.ArchivePasswordPrompt
import de.needanapp.needafile.archive.CompressRequest
import de.needanapp.needafile.archive.CompressSheet
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.ANY_MIME_TYPE
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.Sorting
import de.needanapp.needafile.core.openFile
import de.needanapp.needafile.remote.isRemotePath
import de.needanapp.needafile.transfer.TransferConflictPrompt
import de.needanapp.needafile.vault.ChangeVaultPasswordDialog
import de.needanapp.needafile.vault.CreateVaultDialog
import de.needanapp.needafile.vault.ResetVaultPasswordDialog
import de.needanapp.needafile.vault.ShowRecoveryKeyDialog
import de.needanapp.needafile.vault.vaultRootPath

/** The sheets and dialogs of the file browser; the screen shows the one that is requested. */
sealed interface FileDialog {
    /** Sorting, view, hidden files and more for the open folder. */
    data object Options : FileDialog

    /** The "+" button: new folder, file, tab or server. */
    data object Create : FileDialog
    data class NewItem(val isFolder: Boolean) : FileDialog
    data class Rename(val item: FileEntry) : FileDialog
    data class RenameMany(val items: List<FileEntry>) : FileDialog
    data class Delete(val items: List<FileEntry>) : FileDialog
    data class OpenAs(val path: String) : FileDialog

    /** What is known about [items], as a card floating in place of the pill (shown by the home screen, not here). */
    data class Info(val items: List<FileEntry>) : FileDialog
    data class Compress(val items: List<FileEntry>, val onCompress: (CompressRequest) -> Unit) : FileDialog
    data class ArchivePassword(val path: String, val isWrong: Boolean, val onPassword: (String) -> Unit, val onCancel: () -> Unit) : FileDialog

    /** A file being decrypted, downloaded or extracted before it opens. */
    data class Fetching(val fetch: FetchProgress) : FileDialog

    /** Items to paste already exist in the target folder: overwrite or skip them. */
    data class TransferConflict(val names: List<String>, val onDecision: (overwrite: Boolean) -> Unit) : FileDialog
    data class PickFolder(val pick: FolderPick) : FileDialog

    /** A new Cryptomator vault in the open folder. */
    data class CreateVault(val parentPath: String) : FileDialog
    data class ChangeVaultPassword(val location: String) : FileDialog
    data class ShowRecoveryKey(val location: String) : FileDialog
    data class ResetVaultPassword(val location: String) : FileDialog
}

/** Renders [dialog]; [onDismiss] closes it, [show] switches to another one. */
@Composable
fun FileDialogHost(
    dialog: FileDialog?,
    model: FileBrowserModel,
    operations: FileOperations,
    onDismiss: () -> Unit,
    show: (FileDialog) -> Unit,
    onAddServer: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        FileDialog.Options -> FolderOptionsSheet(model, onDismiss, show)
        FileDialog.Create -> CreateSheet(model, onDismiss, show, onAddServer)
        is FileDialog.NewItem -> NewItemDialog(dialog.isFolder, model, operations, onDismiss)
        is FileDialog.Rename -> RenameDialog(dialog.item, operations, onDismiss)
        is FileDialog.RenameMany -> RenameManySheet(dialog.items, operations, onDismiss)
        is FileDialog.Delete -> DeleteDialog(dialog.items, operations, onDismiss)
        is FileDialog.OpenAs -> OpenAsSheet(dialog.path, onDismiss)
        is FileDialog.Info -> Unit
        is FileDialog.Compress -> CompressSheet(dialog.items, onDismiss, dialog.onCompress)
        is FileDialog.ArchivePassword -> ArchivePasswordPrompt(
            dialog.path,
            dialog.isWrong,
            onPassword = {
                onDismiss()
                dialog.onPassword(it)
            },
            onCancel = {
                onDismiss()
                dialog.onCancel()
            },
        )

        is FileDialog.Fetching -> FetchDialog(dialog.fetch, onDismiss)
        is FileDialog.TransferConflict -> TransferConflictPrompt(dialog.names, dialog.onDecision, onDismiss)
        is FileDialog.PickFolder -> FolderPickerSheet(dialog.pick, onDismiss)
        is FileDialog.CreateVault -> {
            val parent = dialog.parentPath
            CreateVaultDialog(
                parentPath = parent,
                validateName = { operations.validateNewName(parent, it) },
                onCreated = { location ->
                    onDismiss()
                    model.showInFiles(vaultRootPath(location))
                },
                onDismiss = onDismiss,
            )
        }

        is FileDialog.ChangeVaultPassword -> ChangeVaultPasswordDialog(dialog.location, onChanged = {
            onDismiss()
            // a locked vault shows its files, among them the new key file and its backup
            model.refresh()
        }, onDismiss = onDismiss)
        is FileDialog.ShowRecoveryKey -> ShowRecoveryKeyDialog(dialog.location, onDismiss)
        is FileDialog.ResetVaultPassword -> ResetVaultPasswordDialog(dialog.location, onReset = {
            onDismiss()
            model.showVault(dialog.location)
        }, onDismiss = onDismiss)
    }
}

// region creating, renaming, deleting

@Composable
private fun CreateSheet(model: FileBrowserModel, onDismiss: () -> Unit, show: (FileDialog) -> Unit, onAddServer: () -> Unit) {
    val path = model.currentPath
    val isRemote = path.isVirtualPath()
    // vaults go on the phone or a server, not into archives or other vaults
    val canHoldVault = !model.isPicking && (!isRemote || path.isRemotePath()) && model.lockedVault == null
    val folder = model.breadcrumbs(path).lastOrNull()?.first.orEmpty()
    OptionsSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
        // the sheet names the folder everything in the first card lands in
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics(mergeDescendants = true) { heading() }) {
            Text(stringResource(R.string.create_new_title), style = MaterialTheme.typography.titleLarge)
            if (folder.isNotEmpty()) {
                MarqueeText(
                    stringResource(R.string.create_in_folder, folder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.verticalScrollWithBar()) {
            TileCard(stringResource(R.string.create_here), CardSpacing) {
                line(
                    listOfNotNull(
                        { m, shape ->
                            ActionTile(stringResource(R.string.folder), { show(FileDialog.NewItem(isFolder = true)) }, shape, m, Icons.Outlined.CreateNewFolder, centered = true)
                        },
                        if (!isRemote) {
                            { m, shape ->
                                ActionTile(
                                    stringResource(R.string.file),
                                    { show(FileDialog.NewItem(isFolder = false)) },
                                    shape,
                                    m,
                                    Icons.Outlined.NoteAdd,
                                    summary = stringResource(R.string.create_file_summary),
                                    centered = true,
                                )
                            }
                        } else {
                            null
                        },
                        if (canHoldVault) {
                            { m, shape ->
                                ActionTile(
                                    stringResource(R.string.vault),
                                    { show(FileDialog.CreateVault(path)) },
                                    shape,
                                    m,
                                    Icons.Outlined.EnhancedEncryption,
                                    summary = stringResource(R.string.create_vault_summary),
                                    centered = true,
                                )
                            }
                        } else {
                            null
                        },
                    ),
                )
            }
            // a tab or a server doesn't depend on the open folder; new tabs open from the places
            if (!model.isPicking) {
                TileCard(stringResource(R.string.create_open), CardSpacing) {
                    line({ m, shape ->
                        ActionTile(stringResource(R.string.new_tab), {
                            onDismiss()
                            model.showPlaces()
                        }, shape, m, Icons.Outlined.Tab, inline = true, iconInCircle = false)
                    })
                    line({ m, shape ->
                        ActionTile(stringResource(R.string.connect_server), {
                            onDismiss()
                            onAddServer()
                        }, shape, m, Icons.Outlined.Dns, inline = true, iconInCircle = false)
                    })
                }
            }
        }
    }
}

@Composable
private fun NewItemDialog(isFolder: Boolean, model: FileBrowserModel, operations: FileOperations, onDismiss: () -> Unit) {
    val parent = model.currentPath
    TextInputDialog(
        title = stringResource(if (isFolder) R.string.create_new_folder else R.string.create_new_file_title),
        label = stringResource(if (isFolder) R.string.folder_name else R.string.filename),
        initialValue = "",
        confirmLabel = stringResource(R.string.create),
        onConfirm = { name -> operations.create(parent, name, isFolder) },
        onDismiss = onDismiss,
        validate = { operations.validateNewName(parent, it) },
    )
}

@Composable
private fun RenameDialog(item: FileEntry, operations: FileOperations, onDismiss: () -> Unit) {
    val parent = item.path.substringBeforeLast('/')
    val extensionStart = item.name.lastIndexOf('.').takeIf { !item.isDirectory && it > 0 } ?: item.name.length
    TextInputDialog(
        title = stringResource(if (item.isDirectory) R.string.rename_folder else R.string.rename_file),
        label = stringResource(R.string.filename),
        initialValue = item.name,
        confirmLabel = stringResource(R.string.rename),
        onConfirm = { name -> if (name != item.name) operations.renameTo(item, name) },
        onDismiss = onDismiss,
        validate = { operations.validateNewName(parent, it, currentName = item.name) },
        selectUntil = extensionStart,
    )
}

@Composable
private fun DeleteDialog(items: List<FileEntry>, operations: FileOperations, onDismiss: () -> Unit) {
    val what = if (items.size == 1) {
        stringResource(R.string.quoted_name, items.first().name)
    } else {
        pluralStringResource(R.plurals.delete_items, items.size, items.size)
    }
    ConfirmDialog(
        title = stringResource(R.string.delete),
        message = stringResource(R.string.deletion_confirmation, what),
        confirmLabel = stringResource(R.string.delete),
        onConfirm = { operations.delete(items) },
        onDismiss = onDismiss,
        isDestructive = true,
    )
}

@Composable
private fun OpenAsSheet(path: String, onDismiss: () -> Unit) {
    val activity = LocalActivity.current as? BaseActivity ?: return
    fun open(mimeType: String) {
        onDismiss()
        activity.openFile(path, false, mimeType)
    }
    OptionsSheet(onDismiss = onDismiss, title = stringResource(R.string.open_as), skipPartiallyExpanded = true) {
        TileCard(null, CardSpacing) {
            line(
                { m, shape -> ActionTile(stringResource(R.string.text_file), { open("text/*") }, shape, m, Icons.AutoMirrored.Outlined.Article, centered = true) },
                { m, shape -> ActionTile(stringResource(R.string.image_file), { open("image/*") }, shape, m, Icons.Outlined.Image, centered = true) },
            )
            line(
                { m, shape -> ActionTile(stringResource(R.string.audio_file), { open("audio/*") }, shape, m, Icons.Outlined.MusicNote, centered = true) },
                { m, shape -> ActionTile(stringResource(R.string.video_file), { open("video/*") }, shape, m, Icons.Outlined.Movie, centered = true) },
            )
            line({ m, shape -> ActionTile(stringResource(R.string.other_file), { open(ANY_MIME_TYPE) }, shape, m, Icons.Outlined.InsertDriveFile, inline = true) })
        }
    }
}

// endregion



