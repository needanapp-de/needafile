package de.needanapp.needafile.files

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ChipRow
import de.needanapp.design.components.EmptyState
import de.needanapp.design.components.ListRow
import de.needanapp.design.components.MarqueeText
import de.needanapp.design.components.OptionsSheet
import de.needanapp.design.components.RowPosition
import de.needanapp.design.components.SignalButton
import de.needanapp.design.components.SignalCircularProgressIndicator
import de.needanapp.design.components.TextInputDialog
import de.needanapp.design.components.scrollbar
import de.needanapp.design.theme.LocalDesignShapes
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.displayPath
import de.needanapp.needafile.core.internalStorage
import de.needanapp.needafile.core.isAValidFilename
import de.needanapp.needafile.core.removableStorage
import de.needanapp.needafile.core.showError
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.remote.NeedsTrustDecision
import de.needanapp.needafile.remote.RemoteServer
import de.needanapp.needafile.remote.RemoteServersStore
import de.needanapp.needafile.remote.SERVER_TYPE_SMB
import de.needanapp.needafile.remote.buildRemotePath
import de.needanapp.needafile.remote.findTrustDecision
import de.needanapp.needafile.remote.getRemoteParentPath
import de.needanapp.needafile.remote.getRemoteServerId
import de.needanapp.needafile.remote.getRemoteServerPath
import de.needanapp.needafile.remote.isRemotePath
import de.needanapp.needafile.servers.TrustPrompt
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.transfer.TransferManager
import de.needanapp.needafile.vault.VaultSessions
import de.needanapp.needafile.vault.VaultStore
import de.needanapp.needafile.vault.getVaultLocation
import de.needanapp.needafile.vault.isVaultPath
import de.needanapp.needafile.vault.vaultName
import de.needanapp.needafile.vault.vaultRootPath
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asks for a folder: [title] above the list, [confirmLabel] on the button, [onPicked] gets its path. On the phone
 * only, unless [allowServers]: then the saved servers and the unlocked vaults can be picked too and [onPicked] may get
 * a server or vault path.
 */
class FolderPick(
    @StringRes val title: Int,
    @StringRes val confirmLabel: Int,
    val startPath: String,
    val allowServers: Boolean = false,
    val onPicked: (String) -> Unit,
)

/**
 * A place the picker can switch to: a storage of the phone, a saved [server] or the unlocked vault at [vault]. Paths
 * have no trailing slash.
 */
private class PickerPlace(
    val label: String,
    val root: String,
    val start: String,
    val kind: LocationKind,
    val server: RemoteServer? = null,
    val vault: String? = null,
) {
    val isOnPhone get() = server == null && vault == null
}

/** The subfolders of a folder, and the names of everything in it to tell whether a new folder's name is taken. */
private class FolderContent(val folders: List<Pair<String, String>>, val names: Set<String>)

/**
 * Picks a folder as a sheet: the places (internal storage, SD card, servers) as chips on top, below the current
 * folder with "up" and "new folder", its subfolders, and the confirming button at the bottom.
 */
@Composable
fun FolderPickerSheet(pick: FolderPick, onDismiss: () -> Unit) {
    val activity = LocalActivity.current as? BaseActivity ?: return
    val config = activity.config
    val scope = rememberCoroutineScope()
    val phoneLabel = stringResource(R.string.internal)
    val sdCardLabel = stringResource(R.string.sd_card)
    val places = remember {
        buildList {
            add(PickerPlace(phoneLabel, activity.internalStorage, activity.internalStorage, LocationKind.PHONE))
            activity.removableStorage.takeIf { it.isNotEmpty() }?.let { add(PickerPlace(sdCardLabel, it, it, LocationKind.SD_CARD)) }
            if (pick.allowServers) {
                RemoteServersStore.getServers(activity).forEach { server ->
                    val root = buildRemotePath(server.id, "/").trimEnd('/')
                    add(PickerPlace(server.getDisplayName(), root, server.getStartPath().trimEnd('/'), LocationKind.SERVER, server))
                }
                VaultSessions.unlockedLocations().forEach { location ->
                    val root = vaultRootPath(location).trimEnd('/')
                    add(PickerPlace(vaultName(location), root, root, LocationKind.VAULT, vault = location))
                }
            }
        }
    }

    fun placeOf(path: String): PickerPlace? = when {
        path.isVaultPath() -> places.firstOrNull { it.vault == path.getVaultLocation() }
        path.isRemotePath() -> places.firstOrNull { it.server?.id == path.getRemoteServerId() }
        else -> places.filter { it.isOnPhone && (path == it.root || path.startsWith("${it.root}/")) }.maxByOrNull { it.root.length }
    }

    // an unlocked vault's folder shows its decrypted content, a locked one isn't entered: its files are encrypted
    fun shown(folder: String) = if (!folder.isVaultPath() && VaultSessions.isUnlocked(folder)) vaultRootPath(folder).trimEnd('/') else folder

    var path by rememberSaveable {
        val start = pick.startPath.trimEnd('/').let { start ->
            if (!VaultSessions.isUnlocked(start) && VaultStore.get(activity, start) != null) parentOf(start) else shown(start)
        }
        val isUsable = placeOf(start) != null && (start.isVirtualPath() || File(start).isDirectory)
        mutableStateOf(if (isUsable) start else activity.internalStorage)
    }
    var reloads by remember { mutableIntStateOf(0) }
    var content by remember { mutableStateOf<FolderContent?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var trustPrompt by remember { mutableStateOf<NeedsTrustDecision?>(null) }
    var creating by remember { mutableStateOf(false) }
    val place = placeOf(path) ?: places.first()
    // the top of an SMB server lists its shares, files go into one of them
    val isShareList = place.server?.type == SERVER_TYPE_SMB && path.getRemoteServerPath() == "/"

    LaunchedEffect(path, reloads) {
        content = null
        error = null
        try {
            content = withContext(Dispatchers.IO) { loadFolder(activity, path, config.showHidden) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
            trustPrompt = e.findTrustDecision()
        }
    }

    OptionsSheet(onDismiss = onDismiss, title = stringResource(pick.title), skipPartiallyExpanded = true) {
        // every place as a chip side by side, the current one chosen, as the type chips of the search
        if (places.size > 1) {
            // starting at the current place, which may lie far to the right
            ChipRow(places, initialIndex = places.indexOf(place)) { candidate ->
                FilterChip(
                    selected = candidate === place,
                    onClick = { if (candidate !== place) path = candidate.start },
                    label = { Text(candidate.label, maxLines = 1) },
                    leadingIcon = {
                        Icon(locationIcon(candidate.kind), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { path = parentOf(path) }, enabled = path != place.root) {
                Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.go_up))
            }
            MarqueeText(
                if (place.isOnPhone) activity.displayPath(path) else "${place.label}:${path.getVirtualInnerPath()}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(onClick = { creating = true }, enabled = content != null && !isShareList) {
                Icon(Icons.Outlined.CreateNewFolder, stringResource(R.string.create_new_folder))
            }
        }
        if (isShareList) {
            Text(
                stringResource(R.string.picker_open_share),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 4.dp),
            )
        }
        // each folder's list starts at its top, as it did when the list was made anew after loading
        val folderList = remember(content) { LazyListState() }
        Box(Modifier.fillMaxWidth().height(LIST_HEIGHT.dp), contentAlignment = Alignment.Center) {
            val shown = content
            val failure = error
            when {
                failure != null -> EmptyState(Icons.Outlined.CloudOff, activity.describeError(failure), action = {
                    TextButton(onClick = { reloads++ }) { Text(stringResource(R.string.picker_retry)) }
                })

                shown == null -> SignalCircularProgressIndicator()
                shown.folders.isEmpty() -> EmptyState(Icons.Outlined.FolderOff, stringResource(R.string.no_subfolders))
                else -> LazyColumn(
                    state = folderList,
                    modifier = Modifier.fillMaxSize().scrollbar(folderList),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(LocalDesignShapes.current.rowGap),
                ) {
                    itemsIndexed(shown.folders, key = { _, (_, folderPath) -> folderPath }) { index, (name, folderPath) ->
                        ListRow(positionOf(index, shown.folders.size), name, icon = Icons.Outlined.Folder, onClick = { path = shown(folderPath) })
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            SignalButton(onClick = {
                onDismiss()
                pick.onPicked(path)
            }, enabled = content != null && !isShareList) { Text(stringResource(pick.confirmLabel)) }
        }
    }

    trustPrompt?.let { decision ->
        TrustPrompt(decision, onTrusted = { reloads++ }, onDismiss = { trustPrompt = null })
    }

    if (creating) {
        val names = content?.names.orEmpty()
        TextInputDialog(
            title = stringResource(R.string.create_new_folder),
            label = stringResource(R.string.folder_name),
            initialValue = "",
            confirmLabel = stringResource(R.string.create),
            onConfirm = { input ->
                val name = input.trim()
                val parent = path
                scope.launch {
                    try {
                        val created = withContext(Dispatchers.IO) { createFolder(activity, parent, name) }
                        if (created != null) {
                            path = created
                        } else {
                            activity.toast(activity.getString(R.string.could_not_create_folder, File(parent, name).path))
                            reloads++
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        activity.showError(activity.describeError(e))
                        reloads++
                    }
                }
            },
            onDismiss = { creating = false },
            validate = { name ->
                when {
                    !name.trim().isAValidFilename() -> activity.getString(R.string.invalid_name)
                    name.trim() in names -> activity.getString(R.string.name_taken)
                    else -> null
                }
            },
        )
    }
}

/** Reads the folder [path], on the phone, on a server or in a vault; blocks, so call it off the main thread. */
private fun loadFolder(activity: BaseActivity, path: String, showHidden: Boolean): FolderContent {
    fun isShown(name: String) = showHidden || !name.startsWith(".")
    if (path.isVirtualPath()) {
        val entries = TransferManager.getRemoteFileSystem(activity, path).listFiles(path.getVirtualInnerPath())
        val folders = entries.filter { it.isDirectory && isShown(it.name) }
            .sortedBy { it.name.lowercase() }
            .map { it.name to buildVirtualPathLike(path, it.path).trimEnd('/') }
        return FolderContent(folders, entries.mapTo(HashSet()) { it.name })
    }

    val entries = File(path).listFiles().orEmpty()
    val folders = entries.filter { it.isDirectory && isShown(it.name) }
        .sortedBy { it.name.lowercase() }
        .map { it.name to it.path }
    return FolderContent(folders, entries.mapTo(HashSet()) { it.name })
}

/**
 * Creates the folder [name] in [parent] and returns its path, null if the phone refused; a server throws its error.
 * Blocks, so call it off the main thread.
 */
private fun createFolder(activity: BaseActivity, parent: String, name: String): String? {
    if (parent.isVirtualPath()) {
        val created = TransferManager.getRemoteFileSystem(activity, parent).createFolder(parent.getVirtualInnerPath(), name)
        return buildVirtualPathLike(parent, created).trimEnd('/')
    }

    val folder = File(parent, name)
    return folder.path.takeIf { folder.mkdirs() }
}

private fun parentOf(path: String): String = if (path.isVirtualPath()) {
    buildVirtualPathLike(path, getRemoteParentPath(path.getVirtualInnerPath())).trimEnd('/')
} else {
    File(path).parent ?: path
}

/** The corner shape of row [index] of [count] rows. */
fun positionOf(index: Int, count: Int) = when {
    count == 1 -> RowPosition.SINGLE
    index == 0 -> RowPosition.FIRST
    index == count - 1 -> RowPosition.LAST
    else -> RowPosition.MIDDLE
}

private const val LIST_HEIGHT = 360
