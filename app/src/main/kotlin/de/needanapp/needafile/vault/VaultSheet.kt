package de.needanapp.needafile.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ActionTile
import de.needanapp.design.components.OptionsSheet
import de.needanapp.design.components.TileCard
import de.needanapp.design.components.TileCardScope
import de.needanapp.design.components.verticalScrollWithBar
import de.needanapp.needafile.R
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.files.FileBrowserModel
import de.needanapp.needafile.files.FileDialog
import de.needanapp.needafile.settings.config

/**
 * What can be done with a saved vault: a header with its state, where it lies and how it locks, then open and lock as
 * large tiles, its password and key, and removing it from the list.
 */
@Composable
internal fun VaultSheet(
    vault: SavedVault,
    model: FileBrowserModel,
    onOpen: (String) -> Unit,
    show: (FileDialog) -> Unit,
    onForget: () -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    // inside the sheet the context is the sheet's window, not the activity
    val context = LocalContext.current
    val isUnlocked = VaultSessions.isUnlocked(vault.location)

    fun pick(action: () -> Unit) {
        onDismiss()
        action()
    }

    OptionsSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(
            Modifier.verticalScrollWithBar().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VaultHeader(vault, isUnlocked, model)
            TileCard(null) {
                line(
                    listOfNotNull(
                        { m, shape ->
                            ActionTile(stringResource(R.string.vault_open), { pick { onOpen(vault.location) } }, shape, m, Icons.Outlined.FolderOpen, centered = true)
                        },
                        if (isUnlocked) {
                            { m, shape ->
                                ActionTile(stringResource(R.string.vault_lock), { pick { model.lockVault(vault.location) } }, shape, m, Icons.Outlined.Lock, centered = true)
                            }
                        } else {
                            null
                        },
                    ),
                )
            }
            TileCard(stringResource(R.string.vault_keys)) {
                vaultKeyLines(vault.location, vault.hasFingerprint, onDialog = { dialog -> pick { show(dialog) } }) {
                    pick {
                        VaultBiometrics.forget(context, vault.location)
                        context.toast(R.string.vault_fingerprint_forgotten)
                        onChanged()
                    }
                }
            }
            TileCard(null) {
                line({ m, shape ->
                    ActionTile(
                        stringResource(R.string.vault_forget),
                        { pick(onForget) },
                        shape,
                        m,
                        Icons.Outlined.Delete,
                        summary = stringResource(R.string.vault_forget_summary),
                        inline = true,
                        destructive = true,
                    )
                })
            }
        }
    }
}

/** The vault's name in large, below it whether it is open and where it lies, how it locks and whether a fingerprint opens it. */
@Composable
private fun VaultHeader(vault: SavedVault, isUnlocked: Boolean, model: FileBrowserModel) {
    val colors = MaterialTheme.colorScheme
    val lockMinutes = LocalContext.current.config.vaultLockMinutes
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(colors.primary.copy(alpha = HEADER_CIRCLE_ALPHA)), contentAlignment = Alignment.Center) {
            Icon(if (isUnlocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock, contentDescription = null, tint = colors.primary)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(vault.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val state = stringResource(if (isUnlocked) R.string.vault_unlocked_state else R.string.vault_locked_state)
            Text(
                stringResource(R.string.vault_state_and_path, state, model.displayPath(vault.location)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            if (isUnlocked) {
                Text(
                    when {
                        lockMinutes < 0 -> stringResource(R.string.vault_status_locks_manual)
                        lockMinutes == 0 -> stringResource(R.string.vault_status_locks_now)
                        else -> pluralStringResource(R.plurals.vault_status_locks_after, lockMinutes, lockMinutes)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            if (vault.hasFingerprint) {
                Text(stringResource(R.string.vault_status_fingerprint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * The lines for a vault's password and recovery key, and for forgetting its fingerprint when it has one; shared by the
 * vault's sheet and the folder options inside it.
 */
internal fun TileCardScope.vaultKeyLines(location: String, hasFingerprint: Boolean, onDialog: (FileDialog) -> Unit, onForgetFingerprint: () -> Unit) {
    line({ m, shape ->
        ActionTile(stringResource(R.string.vault_change_password), { onDialog(FileDialog.ChangeVaultPassword(location)) }, shape, m, Icons.Outlined.Password, inline = true)
    })
    line({ m, shape ->
        ActionTile(stringResource(R.string.vault_show_recovery_key), { onDialog(FileDialog.ShowRecoveryKey(location)) }, shape, m, Icons.Outlined.Key, inline = true)
    })
    line({ m, shape ->
        ActionTile(stringResource(R.string.vault_reset_password), { onDialog(FileDialog.ResetVaultPassword(location)) }, shape, m, Icons.Outlined.LockReset, inline = true)
    })
    if (hasFingerprint) {
        line({ m, shape ->
            ActionTile(stringResource(R.string.vault_fingerprint_forget), onForgetFingerprint, shape, m, Icons.Outlined.Fingerprint, inline = true)
        })
    }
}

private const val HEADER_CIRCLE_ALPHA = 0.16f
