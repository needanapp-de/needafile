package de.needanapp.needafile.files

import android.os.StatFs
import android.text.format.DateUtils
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ActionTile
import de.needanapp.design.components.TileCardAction
import de.needanapp.design.components.TileGroup
import de.needanapp.design.components.rememberCompactTileLines
import de.needanapp.design.components.scrollbar
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.formatSize
import de.needanapp.needafile.core.internalStorage
import de.needanapp.needafile.core.removableStorage
import de.needanapp.needafile.remote.RemoteServersStore
import de.needanapp.needafile.remote.getRemoteServerId
import de.needanapp.needafile.servers.protocolIcon
import de.needanapp.needafile.servers.protocolLabel
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.vault.VaultSessions
import de.needanapp.needafile.vault.VaultStore
import de.needanapp.needafile.vault.vaultRootPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A tile in a line of a TileCard. */
private typealias Tile = @Composable (Modifier, Shape) -> Unit

/** A storage of the phone among the places: its name, where it starts and what kind it is. */
private class Storage(@StringRes val label: Int, val path: String, val kind: LocationKind)

/** Free and total bytes of a storage. */
class StorageSpace(val free: Long, val total: Long) {
    val usedShare get() = if (total > 0) 1f - free.toFloat() / total else 0f
}

/**
 * The places a tab can show, in place of the active tab's folder, as small tiles in groups: the phone's storages with
 * their free space, the servers, the vaults (unlocked first) and the folders opened lately, below the tabs. Tapping one opens it in a new
 * tab; servers and vaults can be added here too. When another app picks a file, only the phone's own places.
 * [serversVersion] changes when servers were added or edited.
 */
@Composable
fun PlacesPage(
    model: FileBrowserModel,
    contentPadding: PaddingValues,
    nestedScrollConnection: NestedScrollConnection,
    extraBottomSpace: Dp,
    serversVersion: Int,
    onShowTabPath: (Int, Rect) -> Unit,
    onAddServer: () -> Unit,
    onAddVault: () -> Unit,
) {
    val activity = LocalActivity.current as? BaseActivity ?: return
    val isPicking = model.isPicking
    val storages = remember {
        buildList {
            add(Storage(R.string.internal, activity.internalStorage, LocationKind.PHONE))
            if (activity.removableStorage.isNotEmpty()) add(Storage(R.string.sd_card, activity.removableStorage, LocationKind.SD_CARD))
            if (activity.config.enableRootAccess) add(Storage(R.string.root, "/", LocationKind.ROOT))
        }
    }
    val servers = remember(serversVersion) { RemoteServersStore.getServers(activity) }
    // reading the version shows locking and unlocking right away
    val vaultsVersion = VaultSessions.version
    val vaults = remember(vaultsVersion, serversVersion) { VaultStore.all(activity).sortedByDescending { VaultSessions.isUnlocked(it.location) } }
    val recent = remember(model.recentFolders, serversVersion) { model.recentPlaces() }
    val now = remember(model.recentFolders) { System.currentTimeMillis() }
    // up to three small tiles to a line on a phone, more on a tablet; each group takes fewer when its names need it
    val density = LocalDensity.current
    val groupWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() } - GROUP_MARGIN * 2
    val mostColumns = (groupWidth.value / density.fontScale / MIN_TILE_WIDTH).toInt().coerceIn(1, MAX_COLUMNS)
    val groupSpacing = Modifier.padding(horizontal = GROUP_MARGIN, vertical = 6.dp)

    val listState = rememberLazyListState()
    val listPadding = PaddingValues(
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding() + PillSpace + extraBottomSpace,
    )
    LazyColumn(
        state = listState,
        contentPadding = listPadding,
        modifier = Modifier.fillMaxSize().scrollbar(listState, listPadding).nestedScroll(nestedScrollConnection),
    ) {
        item(key = "tabs") { TabsRow(model, onShowTabPath, Modifier.padding(bottom = 4.dp)) }
        item(key = "storages") {
            val places = storages.map { storage ->
                val space = if (storage.kind == LocationKind.ROOT) null else rememberStorageSpace(storage.path, model.transferRunning)
                Place(
                    stringResource(storage.label),
                    space?.let { stringResource(R.string.storage_free_of, it.free.formatSize().keptTogether(), it.total.formatSize().keptTogether()) },
                    locationIcon(storage.kind),
                    fill = space?.usedShare,
                ) { model.openPlace(storage.path) }
            }
            PlaceGroup(stringResource(R.string.places_device), places, mostColumns, groupWidth, groupSpacing)
        }
        // server and vault files can't be handed to another app
        if (!isPicking) {
            item(key = "servers") {
                val places = servers.map { server ->
                    Place(server.getDisplayName(), stringResource(protocolLabel(server.type)), protocolIcon(server.type)) {
                        model.openPlace(server.getStartPath())
                    }
                }
                val action = TileCardAction(stringResource(R.string.places_add), Icons.Outlined.Add, onAddServer)
                PlaceGroup(stringResource(R.string.servers), places, mostColumns, groupWidth, groupSpacing, action)
            }
            item(key = "vaults") {
                val places = vaults.map { vault ->
                    val isUnlocked = VaultSessions.isUnlocked(vault.location)
                    val place = model.locationLabel(vault.location)
                    Place(
                        vault.name,
                        // the lock tells locked ones apart, unlocked ones say so
                        if (isUnlocked) {
                            stringResource(R.string.vault_state_and_place, stringResource(R.string.vault_unlocked_state), place)
                        } else {
                            stringResource(R.string.place_on, place)
                        },
                        if (isUnlocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    ) {
                        // a locked vault asks for its password once its folder is shown
                        model.openPlace(if (isUnlocked) vaultRootPath(vault.location) else vault.location)
                    }
                }
                val action = TileCardAction(stringResource(R.string.places_add), Icons.Outlined.Add, onAddVault)
                PlaceGroup(stringResource(R.string.vaults), places, mostColumns, groupWidth, groupSpacing, action)
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "recent") {
                val places = recent.map { folder ->
                    val crumbs = model.breadcrumbs(folder.path)
                    Place(
                        crumbs.last().first,
                        // where it lies, and below that when it was opened
                        crumbs.dropLast(1).joinToString(PATH_SEPARATOR) { it.first } + "\n" + recentTime(folder.time, now),
                        Icons.Outlined.Folder,
                    ) { model.openPlace(folder.path) }
                }
                PlaceGroup(stringResource(R.string.places_recent), places, mostColumns, groupWidth, groupSpacing)
            }
        }
    }
}

/** One place as a tile: its name, a line about it, its icon, how full it is (storages only) and what tapping does. */
private class Place(val title: String, val summary: String?, val icon: ImageVector, val fill: Float? = null, val onClick: () -> Unit)

/** A group of places as small tiles, as many to a line as their names allow without breaking a word, at most [mostColumns]. */
@Composable
private fun PlaceGroup(
    title: String,
    places: List<Place>,
    mostColumns: Int,
    width: Dp,
    modifier: Modifier,
    action: TileCardAction? = null,
) {
    val lines = rememberCompactTileLines(places, width, mostColumns) { it.title to it.summary }
    TileGroup(title, modifier, action) {
        lines.forEach { lineOfPlaces ->
            line(
                lineOfPlaces.map<Place, Tile> { place ->
                    { m, shape ->
                        ActionTile(
                            place.title,
                            place.onClick,
                            shape,
                            m,
                            place.icon,
                            summary = place.summary,
                            inline = true,
                            fill = place.fill,
                            compact = true,
                        )
                    }
                },
            )
        }
    }
}

/** A size like "81 GB" kept on one line. */
private fun String.keptTogether() = replace(' ', '\u00A0')

/** When a folder was opened, e.g. "5 min. ago" or "Yesterday", starting with a capital; within the last minute "Just now". */
@Composable
private fun recentTime(time: Long, now: Long): String = if (now - time < DateUtils.MINUTE_IN_MILLIS) {
    stringResource(R.string.places_just_now)
} else {
    DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        .replaceFirstChar { it.titlecase() }
}

/** The free space of the storage at [root], read off the main thread and again when [refresh] changes; null until known. */
@Composable
fun rememberStorageSpace(root: String, refresh: Any? = null): StorageSpace? {
    val space by produceState<StorageSpace?>(null, root, refresh) {
        value = withContext(Dispatchers.IO) {
            runCatching { StatFs(root).let { StorageSpace(it.availableBytes, it.totalBytes) } }.getOrNull()
        }
    }
    return space
}

/**
 * What the header says below a folder's name about its place: the storage and its free space ("Phone · 58 GB free"),
 * the server and its protocol, the vault or the archive. At the place's top, where the name already is the place,
 * only what is left (the free space, the protocol).
 */
@Composable
fun placeSubtitle(model: FileBrowserModel, path: String): String? {
    val context = LocalContext.current
    val root = model.locationRoot(path)
    val atRoot = path.trimEnd('/').length <= root.trimEnd('/').length
    val label = model.locationLabel(path)
    return when (model.locationKind(path)) {
        LocationKind.SERVER -> {
            val type = remember(path.getRemoteServerId()) { RemoteServersStore.getServer(context, path.getRemoteServerId())?.type }
            val protocol = type?.let { stringResource(protocolLabel(it)) }
            when {
                protocol == null -> label.takeUnless { atRoot }
                atRoot -> protocol
                else -> stringResource(R.string.place_detail, label, protocol)
            }
        }

        LocationKind.VAULT -> if (atRoot) stringResource(R.string.vault) else stringResource(R.string.place_detail, stringResource(R.string.vault), label)
        LocationKind.ARCHIVE, LocationKind.ROOT -> label.takeUnless { atRoot }
        LocationKind.PHONE, LocationKind.SD_CARD -> {
            val space = rememberStorageSpace(root, model.transferRunning)
            when {
                space == null -> label.takeUnless { atRoot }
                atRoot -> stringResource(R.string.storage_free_of, space.free.formatSize(), space.total.formatSize())
                else -> stringResource(R.string.place_detail, label, stringResource(R.string.storage_free, space.free.formatSize()))
            }
        }
    }
}

private const val MIN_TILE_WIDTH = 120f
private val GROUP_MARGIN = 16.dp
private const val MAX_COLUMNS = 4
private const val PATH_SEPARATOR = " › "
