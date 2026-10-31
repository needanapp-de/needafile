package de.needanapp.needafile.backup

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ContentDialog
import de.needanapp.design.components.SecretTextField
import de.needanapp.design.components.SignalLinearProgressIndicator
import de.needanapp.design.components.signalCheckboxColors
import de.needanapp.needafile.R
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.transfer.TransferManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Making a backup: first the sections and the password, then the file to save it in (the system's file picker).
 * [onDismiss] closes it, also once the backup is saved.
 */
@Composable
fun ExportBackupFlow(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val sections = remember { mutableStateListOf(*BackupSection.entries.toTypedArray()) }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // the password isn't kept when the screen is rebuilt meanwhile (e.g. turned), then it is asked for again
        if (password.length < MIN_PASSWORD_LENGTH) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            return@rememberLauncherForActivityResult
        }
        isWorking = true
        scope.launch {
            try {
                AppBackup.export(context, sections.toSet(), password.toCharArray(), uri)
                context.toast(R.string.backup_exported)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isWorking = false
                context.toast(context.describeError(e))
            }
        }
    }

    val tooShort = password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
    val mismatch = repeated.isNotEmpty() && repeated != password
    val canSave = sections.isNotEmpty() && password.length >= MIN_PASSWORD_LENGTH && repeated == password && !isWorking

    ContentDialog(
        title = stringResource(R.string.backup_export),
        confirmLabel = stringResource(R.string.backup_next),
        onConfirm = {
            val date = SimpleDateFormat(FILE_DATE_PATTERN, Locale.ROOT).format(Date())
            createFile.launch(resources.getString(R.string.backup_file_name, resources.getString(R.string.brand_name), date))
        },
        onDismiss = { if (!isWorking) onDismiss() },
        confirmEnabled = canSave,
        icon = Icons.Outlined.SettingsBackupRestore,
    ) {
        SectionChoice(BackupSection.entries, sections, enabled = !isWorking)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.backup_export_password_hint), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        SecretTextField(
            password,
            { password = it },
            stringResource(R.string.backup_password),
            error = if (tooShort) stringResource(R.string.backup_password_too_short, MIN_PASSWORD_LENGTH) else null,
        )
        SecretTextField(
            repeated,
            { repeated = it },
            stringResource(R.string.backup_password_repeat),
            error = if (mismatch) stringResource(R.string.backup_password_mismatch) else null,
        )
        if (isWorking) Working(R.string.backup_exporting)
    }
}

/**
 * Restoring a backup: the file (system file picker), its password, then the sections to replace. [onRestored] runs
 * after something was restored, so the app can show the new settings.
 */
@Composable
fun ImportBackupFlow(onDismiss: () -> Unit, onRestored: () -> Unit) {
    var hasAsked by rememberSaveable { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf<Uri?>(null) }
    // the decrypted backup holds passwords, so it isn't saved with the screen's state
    var contents by remember { mutableStateOf<BackupContents?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            if (AppBackup.looksLikeBackup(context, uri)) {
                source = uri
            } else {
                context.toast(R.string.backup_error_not_a_backup)
                onDismiss()
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!hasAsked) {
            hasAsked = true
            openFile.launch(arrayOf("*/*"))
        }
    }

    val uri = source ?: return
    val opened = contents
    if (opened == null) {
        BackupPasswordDialog(uri, onOpened = { contents = it }, onDismiss = onDismiss)
    } else {
        RestoreDialog(opened, onDismiss = onDismiss, onRestored = onRestored)
    }
}

@Composable
private fun BackupPasswordDialog(uri: Uri, onOpened: (BackupContents) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var isWrong by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun open() {
        if (password.isEmpty() || isWorking) return
        isWorking = true
        scope.launch {
            try {
                onOpened(AppBackup.read(context, uri, password.toCharArray()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                isWorking = false
                if (e.messageRes == R.string.backup_error_wrong_password) {
                    isWrong = true
                } else {
                    context.toast(context.describeError(e))
                    onDismiss()
                }
            } catch (e: Exception) {
                context.toast(context.describeError(e))
                onDismiss()
            }
        }
    }

    ContentDialog(
        title = stringResource(R.string.backup_open_title),
        confirmLabel = stringResource(R.string.backup_open),
        onConfirm = ::open,
        onDismiss = { if (!isWorking) onDismiss() },
        confirmEnabled = password.isNotEmpty() && !isWorking,
        icon = Icons.Outlined.Lock,
    ) {
        Text(stringResource(R.string.backup_open_message))
        Spacer(Modifier.height(12.dp))
        SecretTextField(
            password,
            {
                password = it
                isWrong = false
            },
            stringResource(R.string.backup_password),
            modifier = Modifier.focusRequester(focusRequester),
            error = if (isWrong) stringResource(R.string.backup_error_wrong_password) else null,
            onDone = ::open,
        )
        if (isWorking) Working(R.string.backup_reading)
    }
}

@Composable
private fun RestoreDialog(contents: BackupContents, onDismiss: () -> Unit, onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sections = remember { mutableStateListOf(*contents.sections.toTypedArray()) }
    var isWorking by remember { mutableStateOf(false) }

    fun restore() {
        // a running transfer would lose its server in the middle
        if (BackupSection.SERVERS in sections && TransferManager.isRunning) {
            context.toast(R.string.backup_transfer_running)
            return
        }
        isWorking = true
        scope.launch {
            try {
                AppBackup.restore(context, contents, sections.toSet())
                context.toast(R.string.backup_restored)
                onDismiss()
                onRestored()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isWorking = false
                context.toast(context.describeError(e))
            }
        }
    }

    ContentDialog(
        title = stringResource(R.string.backup_restore_title),
        confirmLabel = stringResource(R.string.backup_restore),
        onConfirm = ::restore,
        onDismiss = { if (!isWorking) onDismiss() },
        confirmEnabled = sections.isNotEmpty() && !isWorking,
        isDestructive = true,
        icon = Icons.Outlined.SettingsBackupRestore,
    ) {
        if (contents.created > 0) {
            val created = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(contents.created))
            Text(
                stringResource(R.string.backup_restore_created, created, contents.appVersion),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        SectionChoice(contents.sections, sections, enabled = !isWorking) { section ->
            if (section == BackupSection.SERVERS) pluralStringResource(R.plurals.backup_server_count, contents.serverCount, contents.serverCount) else null
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.backup_restore_warning), color = MaterialTheme.colorScheme.error)
        if (isWorking) Working(R.string.backup_restoring)
    }
}

/** Check boxes for [available] sections, the chosen ones in [chosen]; [detail] can replace a section's summary. */
@Composable
private fun SectionChoice(
    available: List<BackupSection>,
    chosen: MutableList<BackupSection>,
    enabled: Boolean,
    detail: @Composable (BackupSection) -> String? = { null },
) {
    available.forEach { section ->
        val isChosen = section in chosen
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .toggleable(isChosen, enabled = enabled, role = Role.Checkbox) { checked ->
                    if (checked) chosen.add(section) else chosen.remove(section)
                }
                .padding(vertical = 4.dp),
        ) {
            Checkbox(isChosen, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp), colors = signalCheckboxColors())
            Column {
                Text(stringResource(section.label), style = MaterialTheme.typography.bodyLarge)
                Text(
                    detail(section) ?: stringResource(section.summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Working(text: Int) {
    Spacer(Modifier.height(16.dp))
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    SignalLinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
}

private const val BACKUP_MIME_TYPE = "application/octet-stream"
private const val FILE_DATE_PATTERN = "yyyy-MM-dd"
private const val MIN_PASSWORD_LENGTH = 8
