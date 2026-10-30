package de.needanapp.needafile.vault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EnhancedEncryption
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ContentDialog
import de.needanapp.design.components.SecretTextField
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.errors.LocalizedException
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.remote.buildRemotePath
import de.needanapp.needafile.remote.getRemoteServerId
import de.needanapp.needafile.remote.getRemoteServerPath
import de.needanapp.needafile.remote.isRemotePath
import de.needanapp.needafile.transfer.TransferManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// region unlocking

/**
 * Asks for the password of the vault at [location] and unlocks it. With a password stored behind the fingerprint only
 * the fingerprint is asked for: the password form shows when "Enter password" is chosen there or the fingerprint can't
 * be used, and backing out of the fingerprint cancels. [onForgotPassword] switches to resetting it with the recovery key.
 */
@Composable
fun VaultUnlockPrompt(location: String, onUnlocked: () -> Unit, onCancel: () -> Unit, onForgotPassword: () -> Unit) {
    val activity = LocalActivity.current as? BaseActivity ?: return
    val scope = rememberCoroutineScope()
    val name = vaultName(location)
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    val canUseFingerprint = remember { VaultBiometrics.isAvailable(activity) }
    var hasFingerprint by remember { mutableStateOf(canUseFingerprint && VaultStore.get(activity, location)?.hasFingerprint == true) }
    var rememberFingerprint by remember { mutableStateOf(false) }
    // hidden while the fingerprint is asked for, so no password form waits behind it
    var showForm by remember { mutableStateOf(!hasFingerprint) }
    val focusRequester = remember { FocusRequester() }

    fun unlock(value: String, fromFingerprint: Boolean) {
        isBusy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { VaultSessions.unlock(activity, location, value) }
                isBusy = false
                if (rememberFingerprint && !fromFingerprint) VaultBiometrics.save(activity, location, value) { onUnlocked() } else onUnlocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultPasswordException) {
                isBusy = false
                error = if (fromFingerprint) {
                    // the password was changed elsewhere since it was stored
                    VaultBiometrics.forget(activity, location)
                    hasFingerprint = false
                    showForm = true
                    activity.getString(R.string.vault_fingerprint_outdated)
                } else {
                    activity.getString(R.string.vault_wrong_password)
                }
            } catch (e: Exception) {
                isBusy = false
                showForm = true
                error = activity.describeError(e)
            }
        }
    }

    fun useFingerprint() {
        // asked for from the form, backing out of the fingerprint returns to it
        val fromForm = showForm
        showForm = false
        VaultBiometrics.load(activity, location, onPassword = { unlock(it, fromFingerprint = true) }, onFailure = { failure ->
            when (failure) {
                VaultBiometrics.Failure.CANCELLED -> if (fromForm) showForm = true else onCancel()
                VaultBiometrics.Failure.INVALIDATED -> {
                    hasFingerprint = false
                    error = activity.getString(R.string.vault_fingerprint_invalidated)
                    showForm = true
                }
                VaultBiometrics.Failure.USE_PASSWORD, VaultBiometrics.Failure.ERROR -> showForm = true
            }
        })
    }

    fun confirm() {
        if (password.isNotEmpty() && !isBusy) unlock(password, fromFingerprint = false)
    }

    LaunchedEffect(Unit) {
        if (hasFingerprint) useFingerprint()
    }
    LaunchedEffect(showForm) {
        if (showForm) runCatching { focusRequester.requestFocus() }
    }

    if (!showForm) {
        // after the fingerprint, while the vault opens
        if (isBusy) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                icon = { Icon(Icons.Outlined.EnhancedEncryption, contentDescription = null) },
                title = { Text(stringResource(R.string.vault_unlock_title)) },
                text = { BusyRow(stringResource(R.string.vault_unlocking)) },
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
        return
    }

    ContentDialog(
        title = stringResource(R.string.vault_unlock_title),
        confirmLabel = stringResource(R.string.vault_unlock),
        onConfirm = ::confirm,
        onDismiss = { if (!isBusy) onCancel() },
        confirmEnabled = password.isNotEmpty() && !isBusy,
        icon = Icons.Outlined.EnhancedEncryption,
    ) {
        Text(stringResource(R.string.vault_unlock_message, name))
        Spacer(Modifier.height(12.dp))
        SecretTextField(
            password,
            {
                password = it
                error = null
            },
            stringResource(R.string.password),
            modifier = Modifier.focusRequester(focusRequester),
            error = error,
            onDone = ::confirm,
        )
        if (canUseFingerprint && !hasFingerprint) {
            CheckRow(rememberFingerprint, stringResource(R.string.vault_remember_fingerprint)) { rememberFingerprint = it }
        }
        if (isBusy) BusyRow(stringResource(R.string.vault_unlocking))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onForgotPassword, enabled = !isBusy) { Text(stringResource(R.string.vault_forgot_password)) }
            if (hasFingerprint) {
                TextButton(onClick = ::useFingerprint, enabled = !isBusy) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.vault_use_fingerprint))
                }
            }
        }
    }
}

// endregion

// region creating

/**
 * Creates a new vault as the folder [parentPath]/name, on the phone or on a server: name, password twice, then the
 * recovery key is shown. [onCreated] gets the vault's location; it is unlocked and in the list already.
 */
@Composable
fun CreateVaultDialog(parentPath: String, validateName: (String) -> String?, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    val activity = LocalActivity.current as? BaseActivity ?: return
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<Pair<String, String>?>(null) }
    val nameError = name.takeIf { it.isNotBlank() }?.let(validateName)
    val passwordErrors = newPasswordErrors(password, repeated)

    created?.let { (location, recoveryKey) ->
        RecoveryKeyDialog(recoveryKey, requireConfirmation = true) { onCreated(location) }
        return
    }

    fun create() {
        isBusy = true
        error = null
        scope.launch {
            try {
                created = withContext(Dispatchers.IO) { createVault(activity, parentPath, name.trim(), password) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = activity.describeError(e)
            } finally {
                isBusy = false
            }
        }
    }

    val canCreate = name.isNotBlank() && nameError == null && passwordErrors.isComplete && !isBusy
    ContentDialog(
        title = stringResource(R.string.vault_create_title),
        confirmLabel = stringResource(R.string.create),
        onConfirm = { if (canCreate) create() },
        onDismiss = { if (!isBusy) onDismiss() },
        confirmEnabled = canCreate,
        icon = Icons.Outlined.EnhancedEncryption,
    ) {
        Text(stringResource(R.string.vault_create_message))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.vault_name)) },
            singleLine = true,
            isError = nameError != null,
            supportingText = nameError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        NewPasswordFields(password, repeated, passwordErrors, onPassword = { password = it }, onRepeated = { repeated = it })
        Text(
            stringResource(R.string.vault_password_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isBusy) BusyRow(stringResource(R.string.vault_creating))
        error?.let { ErrorText(it) }
    }
}

/** Makes the new folder [name] in [parentPath] a vault; returns its location and recovery key. Blocks. */
private fun createVault(context: Context, parentPath: String, name: String, password: String): Pair<String, String> {
    val location: String
    val removeFolder: () -> Unit
    if (parentPath.isRemotePath()) {
        val server = TransferManager.getRemoteFileSystem(context, parentPath)
        val folder = server.createFolder(parentPath.getRemoteServerPath(), name)
        location = buildRemotePath(parentPath.getRemoteServerId(), folder)
        removeFolder = { server.delete(listOf(folder)) }
    } else {
        val folder = File(parentPath, name)
        if (folder.exists() || !folder.mkdirs()) throw LocalizedException(R.string.could_not_create_folder, folder.path)
        location = folder.path
        removeFolder = { folder.deleteRecursively() }
    }

    val storage = VaultSessions.storageFor(context, location)
    try {
        val (opened, recoveryKey) = VaultAccess.create(storage, password)
        VaultSessions.register(context, location, VaultFileSystem(storage, opened))
        return location to recoveryKey
    } catch (e: Exception) {
        storage.close()
        // the folder is new and holds nothing but the half made vault
        runCatching { removeFolder() }
        throw e
    }
}

// endregion

// region recovery key

/**
 * Shows the [recoveryKey] to write down or copy. With [requireConfirmation] (a new vault) it only closes once the user
 * confirmed having stored it.
 */
@Composable
fun RecoveryKeyDialog(recoveryKey: String, requireConfirmation: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    var isConfirmed by remember { mutableStateOf(!requireConfirmation) }
    ContentDialog(
        title = stringResource(R.string.vault_recovery_key_title),
        confirmLabel = stringResource(R.string.vault_done),
        onConfirm = { if (isConfirmed) onDone() },
        // a new vault's key mustn't get lost by a tap beside the dialog
        onDismiss = { if (!requireConfirmation) onDone() },
        confirmEnabled = isConfirmed,
        dismissLabel = null,
        icon = Icons.Outlined.Key,
    ) {
        Text(stringResource(R.string.vault_recovery_key_message))
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            Text(
                recoveryKey,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { copySecret(context, recoveryKey) }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.vault_recovery_key_copy))
        }
        if (requireConfirmation) {
            CheckRow(isConfirmed, stringResource(R.string.vault_recovery_key_confirm)) { isConfirmed = it }
        }
    }
}

/** Copies [text] marked as sensitive, so the keyboard's clipboard preview hides it. */
private fun copySecret(context: Context, text: String) {
    val clip = ClipData.newPlainText(context.getString(R.string.vault_recovery_key), text)
    clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    context.toast(R.string.vault_recovery_key_copied)
}

/** Asks for the password of the vault at [location], then shows its recovery key. */
@Composable
fun ShowRecoveryKeyDialog(location: String, onDismiss: () -> Unit) {
    val activity = LocalActivity.current as? BaseActivity ?: return
    var recoveryKey by remember { mutableStateOf<String?>(null) }
    recoveryKey?.let {
        RecoveryKeyDialog(it, requireConfirmation = false, onDone = onDismiss)
        return
    }
    PasswordCheckDialog(
        title = stringResource(R.string.vault_show_recovery_key),
        message = stringResource(R.string.vault_show_recovery_key_message),
        confirmLabel = stringResource(R.string.vault_show_key),
        onDismiss = onDismiss,
        check = { password -> withVaultStorage(activity, location) { VaultAccess.recoveryKey(it, password) } },
        onChecked = { recoveryKey = it },
    )
}

// endregion

// region passwords

/** Changes the password of the vault at [location], then [onChanged] follows; a password stored behind the fingerprint is stored anew. */
@Composable
fun ChangeVaultPasswordDialog(location: String, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val activity = LocalActivity.current as? BaseActivity ?: return
    val scope = rememberCoroutineScope()
    var oldPassword by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    val passwordErrors = newPasswordErrors(password, repeated)
    val canChange = oldPassword.isNotEmpty() && passwordErrors.isComplete && !isBusy

    fun change() {
        isBusy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { withVaultStorage(activity, location) { VaultAccess.changePassword(it, oldPassword, password) } }
                activity.toast(R.string.vault_password_changed)
                onChanged()
                if (VaultStore.get(activity, location)?.hasFingerprint == true) {
                    VaultBiometrics.forget(activity, location)
                    VaultBiometrics.save(activity, location, password) {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = activity.describeError(e)
                isBusy = false
            }
        }
    }

    ContentDialog(
        title = stringResource(R.string.vault_change_password),
        confirmLabel = stringResource(R.string.vault_change_password),
        onConfirm = { if (canChange) change() },
        onDismiss = { if (!isBusy) onDismiss() },
        confirmEnabled = canChange,
        icon = Icons.Outlined.Password,
    ) {
        SecretTextField(oldPassword, { oldPassword = it }, stringResource(R.string.vault_old_password))
        NewPasswordFields(password, repeated, passwordErrors, onPassword = { password = it }, onRepeated = { repeated = it })
        if (isBusy) BusyRow(stringResource(R.string.vault_changing_password))
        error?.let { ErrorText(it) }
    }
}

/**
 * Sets a new password for the vault at [location] with its recovery key; the vault is unlocked with it right away and
 * [onReset] follows.
 */
@Composable
fun ResetVaultPasswordDialog(location: String, onReset: () -> Unit, onDismiss: () -> Unit) {
    val activity = LocalActivity.current as? BaseActivity ?: return
    val scope = rememberCoroutineScope()
    var recoveryKey by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    val passwordErrors = newPasswordErrors(password, repeated)
    val unknownWords = recoveryKey.split(Regex("\\s+")).filter { it.isNotEmpty() && !RecoveryKey.isWord(it) }
    val canReset = recoveryKey.isNotBlank() && unknownWords.isEmpty() && passwordErrors.isComplete && !isBusy

    fun reset() {
        isBusy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    withVaultStorage(activity, location) { VaultAccess.resetPassword(it, recoveryKey, password) }
                    VaultBiometrics.forget(activity, location)
                    VaultSessions.unlock(activity, location, password)
                }
                activity.toast(R.string.vault_password_reset_done)
                onReset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = activity.describeError(e)
                isBusy = false
            }
        }
    }

    ContentDialog(
        title = stringResource(R.string.vault_reset_title),
        confirmLabel = stringResource(R.string.vault_reset_title),
        onConfirm = { if (canReset) reset() },
        onDismiss = { if (!isBusy) onDismiss() },
        confirmEnabled = canReset,
        icon = Icons.Outlined.Key,
    ) {
        Text(stringResource(R.string.vault_reset_message))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = recoveryKey,
            onValueChange = { recoveryKey = it },
            label = { Text(stringResource(R.string.vault_recovery_key)) },
            minLines = 3,
            isError = unknownWords.isNotEmpty(),
            supportingText = unknownWords.takeIf { it.isNotEmpty() }?.let { words ->
                { Text(stringResource(R.string.vault_recovery_key_unknown_words, words.take(MAX_SHOWN_WORDS).joinToString(", "))) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        NewPasswordFields(password, repeated, passwordErrors, onPassword = { password = it }, onRepeated = { repeated = it })
        if (isBusy) BusyRow(stringResource(R.string.vault_resetting_password))
        error?.let { ErrorText(it) }
    }
}

/**
 * Asks for the password of a vault and hands it to [check] (in the background), its result to [onChecked]; an error
 * keeps the dialog open.
 */
@Composable
private fun <T> PasswordCheckDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    check: (String) -> T,
    onChecked: (T) -> Unit,
) {
    val activity = LocalActivity.current as? BaseActivity ?: return
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    fun confirm() {
        if (password.isEmpty() || isBusy) return
        isBusy = true
        error = null
        scope.launch {
            try {
                onChecked(withContext(Dispatchers.IO) { check(password) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = activity.describeError(e)
            } finally {
                isBusy = false
            }
        }
    }

    ContentDialog(
        title = title,
        confirmLabel = confirmLabel,
        onConfirm = ::confirm,
        onDismiss = { if (!isBusy) onDismiss() },
        confirmEnabled = password.isNotEmpty() && !isBusy,
        icon = Icons.Outlined.Key,
    ) {
        Text(message)
        Spacer(Modifier.height(12.dp))
        SecretTextField(password, {
            password = it
            error = null
        }, stringResource(R.string.password), modifier = Modifier.focusRequester(focusRequester), error = error, onDone = ::confirm)
        if (isBusy) BusyRow(stringResource(R.string.vault_checking_password))
    }
}

/** What is wrong with a new password and its repetition, null where nothing is (or nothing was typed yet). */
private class NewPasswordErrors(val password: String?, val repeated: String?, val isComplete: Boolean)

@Composable
private fun newPasswordErrors(password: String, repeated: String): NewPasswordErrors {
    val tooShort = password.length < VaultAccess.MIN_PASSWORD_LENGTH
    val differs = repeated != password
    return NewPasswordErrors(
        password = if (password.isNotEmpty() && tooShort) {
            pluralStringResource(R.plurals.vault_password_too_short, VaultAccess.MIN_PASSWORD_LENGTH, VaultAccess.MIN_PASSWORD_LENGTH)
        } else {
            null
        },
        repeated = if (repeated.isNotEmpty() && differs) stringResource(R.string.vault_passwords_differ) else null,
        isComplete = !tooShort && !differs,
    )
}

@Composable
private fun NewPasswordFields(password: String, repeated: String, errors: NewPasswordErrors, onPassword: (String) -> Unit, onRepeated: (String) -> Unit) {
    SecretTextField(password, onPassword, stringResource(R.string.vault_new_password), error = errors.password)
    SecretTextField(repeated, onRepeated, stringResource(R.string.vault_repeat_password), error = errors.repeated)
}

// endregion

@Composable
private fun CheckRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BusyRow(label: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
}

/** Runs [block] on the encrypted files of the vault at [location], with a connection of its own for server vaults. */
private fun <T> withVaultStorage(context: Context, location: String, block: (VaultStorage) -> T): T {
    val storage = VaultSessions.storageFor(context, location)
    try {
        return block(storage)
    } finally {
        storage.close()
    }
}

/** The vault's folder name. */
fun vaultName(location: String) = location.trimEnd('/').substringAfterLast('/')

/** ClipDescription.EXTRA_IS_SENSITIVE, which Android 12 already honors under this name. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
private const val MAX_SHOWN_WORDS = 3
