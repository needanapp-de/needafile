package de.needanapp.needafile.vault

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.EnhancedEncryption
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ActionTile
import de.needanapp.design.components.BottomPillSpacer
import de.needanapp.design.components.ChoiceTile
import de.needanapp.design.components.EmptyState
import de.needanapp.design.components.RowPosition
import de.needanapp.design.components.SignalButton
import de.needanapp.design.components.SwipeActionRow
import de.needanapp.design.components.SwitchTile
import de.needanapp.design.components.TileCard
import de.needanapp.design.components.scrollbar
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.files.FileBrowserModel
import de.needanapp.needafile.files.FileDialog
import de.needanapp.needafile.settings.config

/**
 * The saved vaults as cards of tiles: the unlocked ones with a button to lock them, then the locked ones, then the
 * settings of all vaults. A tap on a vault shows what can be done with it; a swipe removes it from the list.
 */
@Composable
fun VaultsPage(
    model: FileBrowserModel,
    contentPadding: PaddingValues,
    nestedScrollConnection: NestedScrollConnection,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    show: (FileDialog) -> Unit,
) {
    val activity = LocalActivity.current as? BaseActivity ?: return
    val config = activity.config
    // reading the version shows locking and unlocking right away
    val sessionsVersion = VaultSessions.version
    var listVersion by remember { mutableIntStateOf(0) }
    val vaults = remember(sessionsVersion, listVersion) { VaultStore.all(activity) }
    val (unlocked, locked) = remember(vaults, sessionsVersion) { vaults.partition { VaultSessions.isUnlocked(it.location) } }
    var menuFor by remember { mutableStateOf<SavedVault?>(null) }
    var lockMinutes by remember { mutableIntStateOf(config.vaultLockMinutes) }
    var lockOnScreenOff by remember { mutableStateOf(config.vaultLockOnScreenOff) }
    val cardPadding = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)

    // an unlocked vault is locked first; its files stay where they are
    fun forget(vault: SavedVault) {
        if (VaultSessions.isUnlocked(vault.location)) model.lockVault(vault.location)
        VaultBiometrics.forget(activity, vault.location)
        VaultStore.forget(activity, vault.location)
        listVersion++
    }

    @Composable
    fun VaultCard(title: String, entries: List<SavedVault>, isUnlocked: Boolean) {
        TileCard(title, cardPadding) {
            entries.forEach { vault ->
                line({ m, shape ->
                    // keyed, so an open row doesn't pass on to the next vault when its own goes away
                    key(vault.location) {
                        SwipeActionRow(
                            RowPosition.SINGLE,
                            stringResource(R.string.vault_remove),
                            onAction = { forget(vault) },
                            modifier = m,
                            accessibilityLabel = stringResource(R.string.vault_forget),
                            shape = shape,
                        ) {
                            ActionTile(
                                vault.name,
                                { menuFor = vault },
                                shape,
                                Modifier.fillMaxWidth(),
                                icon = if (isUnlocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                                summary = model.displayPath(vault.location),
                                inline = true,
                                trailing = {
                                    if (isUnlocked) {
                                        FilledTonalButton(onClick = { model.lockVault(vault.location) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                                            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                            Text(stringResource(R.string.vault_lock_short))
                                        }
                                    } else {
                                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                            )
                        }
                    }
                })
            }
        }
    }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize().scrollbar(listState, contentPadding).nestedScroll(nestedScrollConnection),
    ) {
        if (vaults.isEmpty()) {
            item {
                EmptyState(
                    Icons.Outlined.EnhancedEncryption,
                    stringResource(R.string.vaults_empty_title),
                    message = stringResource(R.string.vaults_empty_message),
                    action = { SignalButton(onClick = onCreate) { Text(stringResource(R.string.vault_create_title)) } },
                )
            }
        }
        if (unlocked.isNotEmpty()) item { VaultCard(stringResource(R.string.vault_unlocked_state), unlocked, isUnlocked = true) }
        if (locked.isNotEmpty()) item { VaultCard(stringResource(R.string.vault_locked_state), locked, isUnlocked = false) }
        item {
            TileCard(stringResource(R.string.options), cardPadding) {
                label(stringResource(R.string.vault_auto_lock))
                VAULT_LOCK_CHOICES.chunked(LOCK_CHOICES_PER_LINE).forEach { choices ->
                    line(
                        choices.map { minutes ->
                            { m, shape ->
                                ChoiceTile(vaultLockTileLabel(minutes), lockMinutes == minutes, {
                                    lockMinutes = minutes
                                    config.vaultLockMinutes = minutes
                                }, shape, m, stacked = true)
                            }
                        },
                    )
                }
                line({ m, shape ->
                    SwitchTile(stringResource(R.string.vault_lock_screen_off), lockOnScreenOff, { value ->
                        lockOnScreenOff = value
                        config.vaultLockOnScreenOff = value
                    }, shape, m, Icons.Outlined.ScreenLockPortrait, summary = stringResource(R.string.vault_lock_summary))
                })
            }
        }
        if (vaults.isNotEmpty()) {
            item {
                TileCard(null, cardPadding) {
                    if (unlocked.size > 1) {
                        line({ m, shape ->
                            ActionTile(stringResource(R.string.vault_lock_all), {
                                model.lockAllVaults()
                                activity.toast(R.string.vaults_locked_toast)
                            }, shape, m, Icons.Outlined.Lock, inline = true)
                        })
                    }
                    line({ m, shape -> ActionTile(stringResource(R.string.vault_create_title), onCreate, shape, m, Icons.Outlined.Add, inline = true) })
                }
            }
        }
        item { BottomPillSpacer() }
    }

    menuFor?.let { vault ->
        VaultSheet(
            vault,
            model,
            onOpen = onOpen,
            show = show,
            onForget = { forget(vault) },
            onChanged = { listVersion++ },
            onDismiss = { menuFor = null },
        )
    }
}

/** How long unlocked vaults stay open in the background; 0 locks right away, -1 never on its own. */
private val VAULT_LOCK_CHOICES = listOf(0, 1, 5, 15, 60, -1)
private const val LOCK_CHOICES_PER_LINE = 3

@Composable
private fun vaultLockTileLabel(minutes: Int) = when {
    minutes < 0 -> stringResource(R.string.vault_lock_tile_manual)
    minutes == 0 -> stringResource(R.string.vault_lock_tile_now)
    else -> pluralStringResource(R.plurals.vault_lock_tile_minutes, minutes, minutes)
}
