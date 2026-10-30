package de.needanapp.needafile.files

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.EnhancedEncryption
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import de.needanapp.design.components.CardAction
import de.needanapp.design.components.CardActionGroup
import de.needanapp.design.components.CardActionGroups
import de.needanapp.design.components.CardActionRow
import de.needanapp.design.components.CardMenu
import de.needanapp.design.components.EmptyState
import de.needanapp.design.components.FastScrollbar
import de.needanapp.design.components.FloatingSurface
import de.needanapp.design.components.LoadingState
import de.needanapp.design.components.MarqueeText
import de.needanapp.design.components.PillDefaults
import de.needanapp.design.components.RefreshBox
import de.needanapp.design.components.SteadyDeleteInput
import de.needanapp.design.components.SurfaceProgress
import de.needanapp.design.components.TabChip
import de.needanapp.design.components.designCard
import de.needanapp.design.theme.LocalDesignColors
import de.needanapp.design.theme.LocalDesignShapes
import de.needanapp.design.theme.NeedanappMotion
import de.needanapp.design.theme.emphasizedSpec
import de.needanapp.needafile.R
import de.needanapp.needafile.archive.isArchivePath
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.Sorting
import de.needanapp.needafile.core.ViewType
import de.needanapp.needafile.core.isVideoFast
import de.needanapp.needafile.core.launchSystemFileManager
import de.needanapp.needafile.core.restrictedFolderUri
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.remote.isRemotePath
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.transfer.TransferManager
import de.needanapp.needafile.transfer.TransferManager.TransferState
import dev.chrisbanes.haze.HazeState
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Room below the list for the pill (and the paste bar above it), so the last entries can scroll into view. */
/** How long a folder of the same place may load before the previous folder's entries give way to the loading animation. */
private const val STALE_ENTRIES_MS = 300L

internal val PillSpace = 104.dp

/** What the pill covers at the bottom, its gap below included; dragging to select scrolls along near it. */
private val PillArea = 72.dp

/**
 * The file browser: the tabs as chips, then the folder's entries as list or grid, or the search results. Scrolls below
 * the header of the app shell.
 */
@Composable
fun FileBrowserPage(
    model: FileBrowserModel,
    contentPadding: PaddingValues,
    nestedScrollConnection: NestedScrollConnection,
    extraBottomSpace: Dp,
    onItemClick: (FileEntry) -> Unit,
    onItemLongClick: (FileEntry) -> Unit,
    onShowTabPath: (Int, Rect) -> Unit,
    cardMenu: (@Composable () -> CardMenu)? = null,
    validateName: (FileEntry, String) -> String? = { _, _ -> null },
    onRename: (FileEntry, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val path = model.currentPath
    val isSearching = model.searchResults != null
    // the previous folder's entries stay while a folder of the same place loads, so quick ones don't flicker; those of
    // another place and those of a slow folder give way to a loading animation
    val isStale = !isSearching && model.itemsPath != path
    var waitedLong by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        delay(STALE_ENTRIES_MS)
        waitedLong = true
    }
    val hidesStale = isStale && (waitedLong || !model.isLoading || model.locationRoot(model.itemsPath) != model.locationRoot(path))
    val shown = if (hidesStale) emptyList() else model.searchResults ?: model.items
    val top = contentPadding.calculateTopPadding()
    val bottom = contentPadding.calculateBottomPadding() + PillSpace + extraBottomSpace

    val haptics = LocalHapticFeedback.current
    val view = model.view
    val groups = remember(shown, view.groupBy, isSearching) {
        if (isSearching || view.groupBy == GroupBy.NONE) null else groupEntries(context, shown, view.groupBy, model.sorting)
    }
    val sorting = remember(model.items, path) { context.config.getFolderSorting(path) }
    // the bubble of the scrollbar handle names the group an entry is in, or what the order runs by
    val labelSorting = if (isSearching) Sorting.BY_NAME else model.sorting
    val scrollLabels = remember(shown, groups, labelSorting) {
        val byKey = HashMap<Any, String>()
        if (groups != null) {
            groups.forEach { group ->
                byKey["group:${group.key}"] = group.label
                group.items.forEach { byKey[it.path] = group.label }
            }
        } else {
            shown.forEach { if (!it.isSectionTitle) byKey[it.path] = scrollLabel(it, labelSorting) }
        }
        byKey
    }
    val scrollLabel: (Any) -> String? = { scrollLabels[it] }

    // a long press selects, dragging on from there selects every entry up to the one under the finger
    val drag = remember { DragSelection() }
    val order = if (groups == null) shown else groups.filterNot { model.isGroupCollapsed(it.key) }.flatMap { it.items }
    val onLongClick: (FileEntry) -> Unit = { item ->
        onItemLongClick(item)
        drag.begin(item, model.selection)
    }
    val coveredBelow = contentPadding.calculateBottomPadding() + PillArea + extraBottomSpace

    Box(Modifier.fillMaxSize()) {
        RefreshBox(
            isRefreshing = false,
            onRefresh = { model.refresh() },
            enabled = !isSearching && !model.isSelecting,
            indicatorTop = top,
            modifier = Modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(
                Modifier.fillMaxSize().pinchSteps(enabled = !isSearching) { step ->
                    if (model.changeZoom(step)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                },
            ) {
                val isWide = maxWidth >= WIDE_LAYOUT
                if (!isSearching && (view.type == ViewType.GRID || view.type == ViewType.GALLERY)) {
                    val isGallery = view.type == ViewType.GALLERY
                    val gridState = rememberFolderScroll(model, path, ::LazyGridState) { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
                    val side = if (isGallery) 6.dp else 12.dp
                    val sidePx = with(LocalDensity.current) { side.roundToPx() }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridColumns(view.type, view.zoom, maxWidth.value)),
                        state = gridState,
                        contentPadding = PaddingValues(top = top, bottom = bottom, start = side, end = side),
                        modifier = Modifier
                            .fillMaxSize()
                            .dragSelection(drag, model, order, gridState, { gridState.keyAt(it, sidePx) }, top, coveredBelow)
                            .nestedScroll(nestedScrollConnection),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) { TabsRow(model, onShowTabPath, Modifier.padding(bottom = 8.dp)) }
                        model.lockedVault?.let { location ->
                            item(span = { GridItemSpan(maxLineSpan) }) { LockedVaultBanner(onUnlock = { model.requestVaultUnlock(location) }) }
                        }
                        emitGroups(groups, shown, model, header = { group ->
                            item(key = "group:${group.key}", span = { GridItemSpan(maxLineSpan) }) { GroupHeader(group, model) }
                        }) { entries ->
                            items(entries, key = { it.path }) { item ->
                                val selected = item.path in model.selection
                                if (isGallery) {
                                    GalleryCell(item, selected, model.listAsCards, onClick = { onItemClick(item) }, onLongClick = { onLongClick(item) })
                                } else {
                                    FileGridCell(
                                        item = item,
                                        selected = selected,
                                        showName = model.showFileNamesInGrid || item.isDirectory,
                                        asCard = model.listAsCards,
                                        onClick = { onItemClick(item) },
                                        onLongClick = { onLongClick(item) },
                                    )
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) { StatusFooter(model, shown.isEmpty(), isSearching) }
                    }
                    FastScrollbar(gridState, Modifier.matchParentSize(), PaddingValues(top = top, bottom = bottom), scrollLabel)
                } else {
                    val listState = rememberScrollState(model, path, isSearching)
                    val isDetails = !isSearching && view.type == ViewType.DETAILS
                    val zoom = if (isSearching) ZOOM_NORMAL else view.zoom
                    val asCards = model.listAsCards
                    // the card of a single selected entry opens with the actions right away, a drag closes it
                    val openCard = if (cardMenu != null && asCards && model.selection.size == 1 && !drag.hasMoved) model.selection.keys.first() else null
                    LaunchedEffect(listState, openCard) {
                        snapshotFlow { openCard != null && listState.layoutInfo.visibleItemsInfo.none { it.key == openCard } }
                            .collect { model.openCardOutOfView = it }
                    }
                    DisposableEffect(model) { onDispose { model.openCardOutOfView = false } }
                    // once the finger is off, an opened card running below the screen moves up into view
                    val bottomInset = with(LocalDensity.current) { (contentPadding.calculateBottomPadding() + CARD_MARGIN).roundToPx() }
                    // so does one unfolded further, as long as its top stays in view
                    LaunchedEffect(listState, openCard, model.cardExpanded) {
                        if (openCard == null) return@LaunchedEffect
                        snapshotFlow { drag.isHolding }.first { !it }
                        delay(CARD_SETTLE_MILLIS)
                        val layout = listState.layoutInfo
                        val card = layout.visibleItemsInfo.firstOrNull { it.key == openCard } ?: return@LaunchedEffect
                        val overshoot = minOf(card.offset + card.size - (layout.viewportEndOffset - bottomInset), card.offset)
                        if (overshoot > 0) listState.animateScrollBy(overshoot.toFloat())
                    }
                    // a name is edited only in the card that is open, and each card opens folded
                    LaunchedEffect(openCard) {
                        if (model.renaming != openCard) model.renaming = null
                        model.cardExpanded = false
                    }
                    if (openCard != null && model.cardExpanded && model.renaming == null) {
                        BackHandler { model.cardExpanded = false }
                    }
                    // the card being renamed stays above the keyboard, which the list leaves room for
                    val density = LocalDensity.current
                    val ime = WindowInsets.ime
                    val keyboard = with(density) { ime.getBottom(this).toDp() }
                    LaunchedEffect(listState, model.renaming) {
                        val renaming = model.renaming ?: return@LaunchedEffect
                        val margin = with(density) { CARD_MARGIN.roundToPx() }
                        snapshotFlow { ime.getBottom(density) }.collect { keyboardHeight ->
                            val layout = listState.layoutInfo
                            val card = layout.visibleItemsInfo.firstOrNull { it.key == renaming } ?: return@collect
                            val overshoot = card.offset + card.size - (layout.viewportEndOffset - keyboardHeight - margin)
                            if (overshoot > 0) listState.scrollBy(overshoot.toFloat())
                        }
                    }
                    val listPadding = PaddingValues(top = top, bottom = maxOf(bottom, keyboard + CARD_MARGIN))
                    LazyColumn(
                        state = listState,
                        contentPadding = listPadding,
                        modifier = Modifier
                            .fillMaxSize()
                            .dragSelection(drag, model, order, listState, listState::keyAt, top, coveredBelow)
                            .nestedScroll(nestedScrollConnection),
                    ) {
                        if (!isSearching) {
                            item(key = "tabs") { TabsRow(model, onShowTabPath, Modifier.padding(bottom = 4.dp)) }
                            model.lockedVault?.let { location ->
                                item(key = "vault_banner") { LockedVaultBanner(onUnlock = { model.requestVaultUnlock(location) }) }
                            }
                        }
                        if (isDetails && shown.isNotEmpty()) {
                            item(key = "details_header") { DetailsHeader(sorting, zoom, isWide, asCards) { kind -> model.sortBy(kind) } }
                        }
                        emitGroups(groups, shown, model, header = { group ->
                            item(key = "group:${group.key}") { GroupHeader(group, model) }
                        }) { entries ->
                            items(entries, key = { if (it.isSectionTitle) "section:${it.path}" else it.path }) { item ->
                                val selected = item.path in model.selection
                                if (item.isSectionTitle) {
                                    SectionTitle(item) { model.open(item.path) }
                                } else {
                                    val menu = if (item.path == openCard && cardMenu != null) cardMenu() else null
                                    FileRow(
                                        item = item,
                                        selected = selected,
                                        onClick = { onItemClick(item) },
                                        onLongClick = { onLongClick(item) },
                                        onIconClick = { model.toggleSelection(item) },
                                        zoom = zoom,
                                        asCard = asCards,
                                        actions = menu?.actions,
                                        moreGroups = menu?.groups?.takeIf { model.cardExpanded },
                                        rename = if (item.path == openCard && item.path == model.renaming) {
                                            InlineRename(
                                                validate = { validateName(item, it) },
                                                onConfirm = { onRename(item, it) },
                                                onCancel = { model.renaming = null },
                                            )
                                        } else {
                                            null
                                        },
                                        isDetails = isDetails,
                                        isWide = isWide,
                                    )
                                }
                            }
                        }
                        item(key = "footer") { StatusFooter(model, shown.isEmpty(), isSearching) }
                    }
                    FastScrollbar(listState, Modifier.matchParentSize(), listPadding, scrollLabel)
                }
            }
        }

        AnimatedVisibility(
            // with nothing in the list the loading animation there says it already
            visible = (model.isLoading || model.isSearching) && shown.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = top),
        ) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        }
    }
}

@Composable
private fun rememberScrollState(model: FileBrowserModel, path: String, isSearching: Boolean): LazyListState {
    if (isSearching) return rememberLazyListState()
    return rememberFolderScroll(model, path, ::LazyListState) { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
}

/**
 * The scroll state of the folder [path], at the position it had when it was left. The previous folder's entries stay
 * until [path] is loaded; a position restored on them would be cut down to their length and lost, so the state starts
 * at the top until then and is created anew with the remembered position once the entries are there.
 */
@Composable
private fun <S : Any> rememberFolderScroll(
    model: FileBrowserModel,
    path: String,
    create: (index: Int, offset: Int) -> S,
    position: (S) -> Pair<Int, Int>,
): S {
    val isLoaded = model.itemsPath == path
    val state = remember(path, isLoaded) {
        val (index, offset) = if (isLoaded) model.scrollPosition(path) else 0 to 0
        create(index, offset)
    }
    DisposableEffect(state) {
        onDispose {
            if (isLoaded) {
                val (index, offset) = position(state)
                model.rememberScroll(path, index, offset)
            }
        }
    }
    return state
}

/**
 * Below the entries: an empty state, or a loading animation with the place's icon while the folder opens. When the
 * folder couldn't be read, a way to try again; while a password or a certificate is asked for, nothing.
 */
@Composable
private fun StatusFooter(model: FileBrowserModel, isEmpty: Boolean, isSearching: Boolean) {
    val path = model.currentPath
    when {
        !isEmpty -> Unit
        model.isSearching -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        model.isLoading -> LoadingState(
            stringResource(R.string.folder_opening, model.breadcrumbs(path).last().first),
            icon = locationIcon(model.locationKind(path)),
        )

        isSearching -> EmptyState(Icons.Outlined.SearchOff, stringResource(R.string.no_items_found))
        model.isRestrictedFolder -> {
            val activity = LocalActivity.current
            EmptyState(
                Icons.Outlined.Lock,
                stringResource(R.string.folder_restricted_title),
                message = stringResource(R.string.folder_restricted_message),
                action = activity?.let {
                    {
                        FilledTonalButton(onClick = { it.launchSystemFileManager(it.restrictedFolderUri(model.currentPath)) }) {
                            Text(stringResource(R.string.folder_restricted_open))
                        }
                    }
                },
            )
        }

        model.loadError != null -> EmptyState(
            if (path.isRemotePath()) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline,
            stringResource(R.string.folder_open_failed, model.breadcrumbs(path).last().first),
            message = model.loadError,
            action = {
                FilledTonalButton(onClick = { model.refresh() }) { Text(stringResource(R.string.folder_try_again)) }
            },
        )

        model.waitsForAnswer -> Unit

        else -> EmptyState(Icons.Outlined.FolderOff, stringResource(R.string.folder_empty))
    }
}

/** The open folder is a locked vault: its files are encrypted, the button asks for the password. */
@Composable
private fun LockedVaultBanner(onUnlock: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.secondaryContainer)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onSecondaryContainer)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.vault_locked_banner), style = MaterialTheme.typography.titleSmall, color = colors.onSecondaryContainer)
            Text(stringResource(R.string.vault_locked_banner_message), style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer)
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onUnlock) { Text(stringResource(R.string.vault_unlock)) }
    }
}

// region tabs and path

/**
 * The places chip, then the open tabs as chips, each with the symbol of its place. A tap shows a tab; a tap on the
 * shown one, or a long press on any, its whole path ([onShowTabPath] with the tab and where its chip lies on the screen).
 */
@Composable
internal fun TabsRow(model: FileBrowserModel, onShowTabPath: (Int, Rect) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "places") {
            TabChip(stringResource(R.string.places), model.showsPlaces, { model.showPlaces() }, icon = Icons.Outlined.Home)
        }
        itemsIndexed(model.tabs.toList()) { index, tabPath ->
            val place = remember { ChipPlace() }
            val isShown = !model.showsPlaces && index == model.activeTab
            val showPath = { place.coordinates?.takeIf { it.isAttached }?.let { onShowTabPath(index, Rect(it.positionInRoot(), it.size.toSize())) } }
            TabChip(
                label = model.breadcrumbs(tabPath).last().first,
                selected = isShown,
                onClick = {
                    when {
                        isShown -> showPath()
                        index == model.activeTab -> model.leavePlaces()
                        else -> model.switchTab(index)
                    }
                },
                modifier = Modifier.onGloballyPositioned { place.coordinates = it },
                icon = locationIcon(model.locationKind(tabPath)),
                onLongClick = { showPath() },
                onLongClickLabel = stringResource(R.string.tab_path_show),
                onClickLabel = if (isShown) stringResource(R.string.tab_path_show) else null,
            )
        }
    }
}

/** Where a chip was laid out last, for the card that opens below it. */
private class ChipPlace {
    var coordinates: LayoutCoordinates? = null
}

fun locationIcon(kind: LocationKind): ImageVector = when (kind) {
    LocationKind.PHONE -> Icons.Outlined.PhoneAndroid
    LocationKind.SD_CARD -> Icons.Outlined.SdCard
    LocationKind.ROOT -> Icons.Outlined.Tag
    LocationKind.SERVER -> Icons.Outlined.Dns
    LocationKind.ARCHIVE -> Icons.Outlined.FolderZip
    LocationKind.VAULT -> Icons.Outlined.EnhancedEncryption
}

// endregion

// region entries

/**
 * A file or folder in a list: icon (tap selects), name and details; also used outside the browser. [zoom]: compact rows
 * have one line with the size on the right, large ones big previews. [asCard] draws it as a card of its own, as low as
 * its two lines allow.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileRow(
    item: FileEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onIconClick: () -> Unit,
    zoom: Int = ZOOM_NORMAL,
    asCard: Boolean = false,
    actions: List<CardAction>? = null,
    moreGroups: List<CardActionGroup>? = null,
    rename: InlineRename? = null,
    isDetails: Boolean = false,
    isWide: Boolean = false,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val isCompact = zoom == ZOOM_COMPACT
    val details = remember(item, isCompact) { if (isCompact) describeItemShort(context, item) else describeItem(context, item) }
    val iconSize = when {
        isDetails -> detailsIconSize(zoom)
        zoom == ZOOM_COMPACT -> 28.dp
        zoom == ZOOM_LARGE -> if (asCard) 64.dp else 72.dp
        else -> if (asCard) 36.dp else 44.dp
    }
    val iconShape = RoundedCornerShape(if (zoom == ZOOM_LARGE && !isDetails) 16.dp else if (isCompact || asCard || isDetails) 8.dp else 12.dp)
    val surface = if (asCard) {
        // each entry a card of its own, light against the background as the design's cards are
        val shapes = LocalDesignShapes.current
        Modifier
            .padding(horizontal = 12.dp, vertical = shapes.rowGap)
            .designCard(RoundedCornerShape(shapes.group))
    } else {
        Modifier
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(if (isCompact || isDetails) 12.dp else 16.dp))
    }
    // the name edited in place, its base selected as in the dialog
    val isRenaming = rename != null
    val newName = remember(item.path, isRenaming) {
        val extensionStart = item.name.lastIndexOf('.').takeIf { !item.isDirectory && it > 0 } ?: item.name.length
        TextFieldState(item.name, TextRange(0, extensionStart))
    }
    val nameError = rename?.validate?.invoke(newName.text.toString())
    val confirmRename = { if (nameError == null) rename?.onConfirm?.invoke(newName.text.toString()) }
    if (rename != null) BackHandler(onBack = rename.onCancel)
    Column(
        Modifier
            .fillMaxWidth()
            .then(surface)
            .background(if (selected) colors.secondaryContainer else Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = !isRenaming,
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
                .heightIn(min = if (isDetails) detailsRowHeight(zoom) else rowHeight(zoom, asCard))
                .padding(horizontal = if (asCard) 10.dp else 8.dp, vertical = if (isCompact || isDetails) 4.dp else if (asCard) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val selectLabel = stringResource(R.string.select_item)
            FileIcon(
                item = item,
                selected = selected,
                size = iconSize,
                shape = iconShape,
                modifier = Modifier
                    .clip(iconShape)
                    .clickable(enabled = !isRenaming, onClickLabel = selectLabel, onClick = onIconClick),
            )
            Spacer(Modifier.width(if (isCompact || asCard || isDetails) 12.dp else 16.dp))
            val nameColor = if (selected) colors.onSecondaryContainer else colors.onSurface
            // the details on a selected entry in its own text color, faded as on the others
            val detailColor = if (selected) LocalDesignColors.current.onSelectionVariant else colors.onSurfaceVariant
            if (isRenaming) {
                Column(Modifier.weight(1f)) {
                    NameField(newName, onDone = confirmRename)
                    // what is wrong with the name, otherwise the details as before
                    val hint = nameError?.takeIf { newName.text.isNotEmpty() }
                    Text(
                        hint ?: details,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hint != null) colors.error else detailColor,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            } else if (isDetails) {
                // the name, then on wide screens size, date and type in columns, otherwise date over size at the end
                val columns = remember(item, isWide) { detailColumns(context, item, isWide) }
                val nameStyle = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
                MarqueeText(item.name, Modifier.weight(1f), style = nameStyle, color = nameColor)
                val style = MaterialTheme.typography.bodySmall
                if (isWide) {
                    Text(columns.size, style = style, color = detailColor, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(SIZE_COLUMN).padding(start = 4.dp))
                    Text(columns.date, style = style, color = detailColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(DATE_COLUMN_WIDE).padding(start = 12.dp))
                    Text(columns.type, style = style, color = detailColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(TYPE_COLUMN).padding(start = 12.dp))
                } else if (columns.date.isNotEmpty() || columns.size.isNotEmpty()) {
                    Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                        if (columns.date.isNotEmpty()) Text(columns.date, style = style, color = detailColor, maxLines = 1)
                        if (columns.size.isNotEmpty()) Text(columns.size, style = style, color = detailColor, maxLines = 1)
                    }
                }
            } else if (isCompact) {
                MarqueeText(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = nameColor)
                if (details.isNotEmpty()) {
                    Text(details, style = MaterialTheme.typography.bodySmall, color = detailColor, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
                }
            } else {
                Column(Modifier.weight(1f)) {
                    MarqueeText(item.name, style = MaterialTheme.typography.bodyLarge, color = nameColor)
                    if (details.isNotEmpty()) {
                        Text(details, style = MaterialTheme.typography.bodySmall, color = detailColor, maxLines = 1)
                    }
                }
            }
        }
        if (asCard) {
            AnimatedVisibility(
                visible = actions != null,
                enter = expandVertically(emphasizedSpec()) + fadeIn(emphasizedSpec()),
                exit = shrinkVertically(emphasizedSpec(NeedanappMotion.SHORT_DURATION)) + fadeOut(emphasizedSpec(NeedanappMotion.SHORT_DURATION)),
            ) {
                // the last actions stay while the card closes
                val last = remember { LastActions() }
                if (actions != null) last.actions = actions
                if (moreGroups != null) last.groups = moreGroups
                if (rename != null) {
                    RenameButtons(canConfirm = nameError == null, onCancel = rename.onCancel, onConfirm = confirmRename)
                } else {
                    val contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface
                    Column {
                        CardActionRow(last.actions, contentColor)
                        AnimatedVisibility(
                            visible = moreGroups != null,
                            enter = expandVertically(emphasizedSpec()) + fadeIn(emphasizedSpec()),
                            exit = shrinkVertically(emphasizedSpec(NeedanappMotion.SHORT_DURATION)) + fadeOut(emphasizedSpec(NeedanappMotion.SHORT_DURATION)),
                        ) {
                            CardActionGroups(last.groups, contentColor, if (selected) colors.secondaryContainer else LocalDesignColors.current.card)
                        }
                    }
                }
            }
        }
    }
}

/** Renaming in the opened card: [validate] tells what is wrong with a name, null when it is fine. */
class InlineRename(val validate: (String) -> String?, val onConfirm: (String) -> Unit, val onCancel: () -> Unit)

/**
 * The name as a text field, focused with the keyboard up as it appears. The cursor handle dragged past the edge scrolls
 * on through the name; a held delete key takes one character after the other, slowly.
 */
@Composable
private fun NameField(state: TextFieldState, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    SteadyDeleteInput {
        BasicTextField(
            state = state,
            lineLimits = TextFieldLineLimits.SingleLine,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { onDone() },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            decorator = { field ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surface)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) { field() }
            },
        )
    }
}

/** Below the name being edited, in place of the card's actions. */
@Composable
private fun RenameButtons(canConfirm: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        // filled, as the tonal one would vanish on the selected card
        Button(onClick = onConfirm, enabled = canConfirm) { Text(stringResource(R.string.rename)) }
    }
}

/** The actions an opened card showed, and the groups it unfolded, kept for its closing animation. */
private class LastActions {
    var actions: List<CardAction> = emptyList()
    var groups: List<CardActionGroup> = emptyList()
}

/** Until an opened or unfolded card has its full height. */
private const val CARD_SETTLE_MILLIS = NeedanappMotion.MEDIUM_DURATION + 50L
private val CARD_MARGIN = 16.dp

/** The least height of a list entry: as low as two lines allow for cards, a little more air for plain rows. */
private fun rowHeight(zoom: Int, isCard: Boolean) = when (zoom) {
    ZOOM_COMPACT -> if (isCard) 44.dp else 48.dp
    ZOOM_LARGE -> if (isCard) 76.dp else 88.dp
    else -> if (isCard) 52.dp else 64.dp
}

/** Column widths of the details view on wide screens; the name takes the rest. */
private val SIZE_COLUMN = 76.dp
private val DATE_COLUMN_WIDE = 148.dp
private val TYPE_COLUMN = 72.dp

/** From this width on, the details view adds a type column and the time, the grids fit more columns. */
private val WIDE_LAYOUT = 600.dp

private fun detailsIconSize(zoom: Int) = when (zoom) {
    ZOOM_COMPACT -> 24.dp
    ZOOM_LARGE -> 40.dp
    else -> 32.dp
}

private fun detailsRowHeight(zoom: Int) = when (zoom) {
    ZOOM_COMPACT -> 48.dp
    ZOOM_LARGE -> 64.dp
    else -> 52.dp
}

/** The column titles of the details view; tapping one sorts by it, the active one shows the direction. */
@Composable
private fun DetailsHeader(sorting: Int, zoom: Int, isWide: Boolean, asCards: Boolean, onSort: (Int) -> Unit) {
    val isDescending = sorting and Sorting.DESCENDING != 0
    // in line with the rows' columns: their margin and padding, on cards a little more
    val edge = if (asCards) 22.dp else 16.dp
    Row(
        // narrow, the last title's text ends where date and size end
        Modifier.fillMaxWidth().padding(start = edge + detailsIconSize(zoom) + 12.dp, end = if (isWide) edge else edge - 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun ColumnTitle(label: Int, kind: Int, modifier: Modifier, alignEnd: Boolean = false) {
            val isActive = sorting and kind != 0
            val color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = stringResource(R.string.sort_by)) { onSort(kind) }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
                if (isActive) {
                    Icon(
                        if (isDescending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                        contentDescription = stringResource(if (isDescending) R.string.descending else R.string.ascending),
                        tint = color,
                        modifier = Modifier.padding(start = 2.dp).size(14.dp),
                    )
                }
            }
        }
        ColumnTitle(R.string.name, Sorting.BY_NAME, Modifier.weight(1f))
        if (isWide) {
            ColumnTitle(R.string.size, Sorting.BY_SIZE, Modifier.width(SIZE_COLUMN), alignEnd = true)
            ColumnTitle(R.string.sort_date, Sorting.BY_DATE, Modifier.width(DATE_COLUMN_WIDE).padding(start = 8.dp))
            ColumnTitle(R.string.sort_type, Sorting.BY_EXTENSION, Modifier.width(TYPE_COLUMN).padding(start = 8.dp))
        } else {
            // date and size stand over each other in the rows, their titles side by side to stay easy to tap
            ColumnTitle(R.string.sort_date, Sorting.BY_DATE, Modifier)
            ColumnTitle(R.string.size, Sorting.BY_SIZE, Modifier, alignEnd = true)
        }
    }
}

/** A picture or video as a square preview without text; folders and other files carry their name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCell(item: FileEntry, selected: Boolean, asCard: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier
            .padding(if (asCard) 3.dp else 2.dp)
            .aspectRatio(1f)
            .then(if (asCard) Modifier.designCard(RoundedCornerShape(GALLERY_CARD_RADIUS)) else Modifier.clip(RoundedCornerShape(6.dp)))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .semantics { contentDescription = item.name },
    ) {
        FileIcon(item, selected = false, size = maxWidth, shape = RectangleShape)
        if (item.isDirectory || !item.hasThumbnail()) {
            MarqueeText(
                item.name,
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(colors.surfaceContainerHighest.copy(alpha = NAME_STRIP_ALPHA))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
            )
        } else if (item.path.isVideoFast()) {
            // a dark disc keeps the symbol visible on light pictures too
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(Color.Black.copy(alpha = BADGE_ALPHA), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        if (selected) {
            Box(Modifier.matchParentSize().background(colors.primary.copy(alpha = SELECTED_TINT_ALPHA)))
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private val GALLERY_CARD_RADIUS = 12.dp
private const val NAME_STRIP_ALPHA = 0.88f
private const val BADGE_ALPHA = 0.5f
private const val SELECTED_TINT_ALPHA = 0.35f

/** A group heading: name and count; a tap folds the group away or opens it again. */
@Composable
private fun GroupHeader(group: EntryGroup, model: FileBrowserModel) {
    val isCollapsed = model.isGroupCollapsed(group.key)
    val rotation by animateFloatAsState(if (isCollapsed) -90f else 0f, label = "group_arrow")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = stringResource(if (isCollapsed) R.string.group_expand else R.string.group_collapse)) { model.toggleGroup(group.key) }
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            group.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(group.items.size.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).size(20.dp).rotate(rotation),
        )
    }
}

/** Emits [items] as they are, or each group as its heading followed by its entries unless it is collapsed. */
private inline fun emitGroups(
    groups: List<EntryGroup>?,
    items: List<FileEntry>,
    model: FileBrowserModel,
    header: (EntryGroup) -> Unit,
    entries: (List<FileEntry>) -> Unit,
) {
    if (groups == null) {
        entries(items)
        return
    }
    groups.forEach { group ->
        header(group)
        if (!model.isGroupCollapsed(group.key)) entries(group.items)
    }
}

/**
 * Two fingers spread or pinched by about a quarter make one zoom step ([onStep] +1 bigger, -1 smaller); one finger
 * still scrolls. Watches the touches before the list does, so the list doesn't scroll while pinching.
 */
private fun Modifier.pinchSteps(enabled: Boolean, onStep: (Int) -> Unit): Modifier = if (!enabled) this else composed {
    val currentOnStep by rememberUpdatedState(onStep)
    pointerInput(Unit) {
        awaitEachGesture {
            var scale = 1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    scale *= event.calculateZoom()
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    when {
                        scale > PINCH_STEP -> {
                            currentOnStep(1)
                            scale = 1f
                        }

                        scale < 1f / PINCH_STEP -> {
                            currentOnStep(-1)
                            scale = 1f
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

private const val PINCH_STEP = 1.25f

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCell(item: FileEntry, selected: Boolean, showName: Boolean, asCard: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    // as a card light against the background, otherwise a tile in the surface's tone
    val tile = if (asCard) {
        Modifier
            .padding(5.dp)
            .designCard(RoundedCornerShape(LocalDesignShapes.current.group))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
    } else {
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLow)
    }
    Column(
        modifier = Modifier
            .then(tile)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            FileIcon(item, selected, size = maxWidth, shape = RoundedCornerShape(12.dp))
        }
        if (showName) {
            MarqueeText(
                item.name,
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A folder heading in the search results; opens that folder. */
@Composable
private fun SectionTitle(item: FileEntry, onClick: () -> Unit) {
    MarqueeText(
        item.name,
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

// endregion

/**
 * Floats above the pill while the clipboard holds items: what would be pasted where, with "Paste"; during a transfer
 * its progress with "Cancel", the whole bar filling up as it goes. Gone while [isCovered] by something unfolded below it.
 */
@Composable
fun PasteBar(
    model: FileBrowserModel,
    hazeState: HazeState?,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onClear: () -> Unit,
    isCovered: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val visible = (model.transferRunning || model.clipboard.isNotEmpty()) && !isCovered
    // nothing can be pasted into an archive
    val isReadOnly = model.currentPath.isArchivePath()
    val state = model.transferState
    val problem = model.transferProblem
    val transferProgress = model.transferProgress
    val barProgress = when {
        !model.transferRunning -> null
        transferProgress != null && transferProgress.bytesTotal > 0 ->
            SurfaceProgress.Fraction(transferProgress.bytesDone.toFloat() / transferProgress.bytesTotal)
        state == TransferState.RUNNING -> SurfaceProgress.Unknown
        else -> null
    }
    AnimatedVisibility(visible = visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically(), modifier = modifier) {
        FloatingSurface(PillDefaults.shape, hazeState, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), progress = barProgress) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val progress = model.transferProgress
                        val title = when {
                            !model.transferRunning -> {
                                val label = if (model.clipboardIsCut) R.plurals.cut_items else R.plurals.clipboard_items
                                pluralStringResource(label, model.clipboard.size, model.clipboard.size)
                            }
                            state == TransferState.WAITING -> stringResource(R.string.transfer_waiting)
                            state == TransferState.PAUSED && problem != null -> stringResource(R.string.transfer_paused_no_connection)
                            state == TransferState.PAUSED -> stringResource(R.string.transfer_paused)
                            progress == null -> stringResource(R.string.transfer_preparing)
                            else -> stringResource(TransferManager.kind.progress, progress.fileIndex, progress.fileCount)
                        }
                        // waiting and paused copies explain themselves, that may take a second line
                        val maxLines = if (model.transferRunning && state != TransferState.RUNNING) 2 else 1
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
                        val subtitle = when {
                            !model.transferRunning && isReadOnly -> stringResource(R.string.archive_read_only)
                            !model.transferRunning -> stringResource(R.string.paste_target, model.displayPath(model.currentPath))
                            state == TransferState.WAITING ->
                                stringResource(R.string.transfer_waiting_until, DateFormat.getTimeFormat(context).format(Date(model.transferWaitingUntil)))
                            state == TransferState.PAUSED && problem != null -> context.describeError(problem)
                            progress == null -> ""
                            else -> progress.fileName
                        }
                        if (subtitle.isNotEmpty() && maxLines == 1) {
                            // the file being copied or the target folder, scrolling through when long
                            MarqueeText(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalDesignColors.current.onPillVariant)
                        } else if (subtitle.isNotEmpty()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalDesignColors.current.onPillVariant,
                                maxLines = maxLines,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    when {
                        !model.transferRunning -> {
                            IconButton(onClick = onClear) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear_clipboard))
                            }
                            FilledTonalButton(onClick = onPaste, enabled = !isReadOnly, colors = PillDefaults.tonalButtonColors(), shape = PillDefaults.buttonShape) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.paste))
                            }
                        }

                        state == TransferState.RUNNING -> {
                            if (TransferManager.canPause) {
                                IconButton(onClick = onPause) {
                                    Icon(Icons.Outlined.Pause, contentDescription = stringResource(R.string.transfer_pause))
                                }
                            }
                            FilledTonalButton(onClick = onCancel, colors = PillDefaults.tonalButtonColors(), shape = PillDefaults.buttonShape) { Text(stringResource(R.string.cancel)) }
                        }

                        state == TransferState.WAITING -> {
                            IconButton(onClick = onCancel) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
                            }
                            FilledTonalIconButton(onClick = onResume, colors = PillDefaults.tonalIconButtonColors(), shape = PillDefaults.buttonShape) {
                                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.transfer_retry_now))
                            }
                        }

                        else -> {
                            IconButton(onClick = onCancel) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
                            }
                            FilledTonalButton(onClick = onResume, colors = PillDefaults.tonalButtonColors(), shape = PillDefaults.buttonShape) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.transfer_resume))
                            }
                        }
                    }
                }
            }
        }
    }
}
