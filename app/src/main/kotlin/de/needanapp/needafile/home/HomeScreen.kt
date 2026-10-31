package de.needanapp.needafile.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ActionPill
import de.needanapp.design.components.AppScaffold
import de.needanapp.design.components.CardAction
import de.needanapp.design.components.CardActionGroup
import de.needanapp.design.components.CardMenu
import de.needanapp.design.components.NavigationPill
import de.needanapp.design.components.PillAction
import de.needanapp.design.components.PillItem
import de.needanapp.design.components.PillMenu
import de.needanapp.design.components.SelectionCard
import de.needanapp.design.components.rememberPillScrollBehavior
import de.needanapp.design.components.rememberSearchState
import de.needanapp.design.theme.ThemeSettings
import de.needanapp.needafile.R
import de.needanapp.needafile.archive.ArchivePasswordPrompt
import de.needanapp.needafile.archive.archiveRootPath
import de.needanapp.needafile.archive.isArchiveFile
import de.needanapp.needafile.archive.isArchivePath
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.hideKeyboard
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.files.FileAction
import de.needanapp.needafile.files.FileBrowserModel
import de.needanapp.needafile.files.FileBrowserPage
import de.needanapp.needafile.files.FileDialog
import de.needanapp.needafile.files.FileDialogHost
import de.needanapp.needafile.files.FileInfoCard
import de.needanapp.needafile.files.FileOperations
import de.needanapp.needafile.files.FolderPick
import de.needanapp.needafile.files.InfoFold
import de.needanapp.needafile.files.PasteBar
import de.needanapp.needafile.files.PlacesPage
import de.needanapp.needafile.files.SearchFilterRows
import de.needanapp.needafile.files.TabPathOverlay
import de.needanapp.needafile.files.TabPathRequest
import de.needanapp.needafile.files.createBar
import de.needanapp.needafile.files.isVirtualPath
import de.needanapp.needafile.files.placeSubtitle
import de.needanapp.needafile.files.selectionNames
import de.needanapp.needafile.files.selectionSummary
import de.needanapp.needafile.files.serverIdOf
import de.needanapp.needafile.pick.PickDialog
import de.needanapp.needafile.pick.PickDialogHost
import de.needanapp.needafile.pick.PickRequest
import de.needanapp.needafile.pick.onFileTapped
import de.needanapp.needafile.pick.pickPillContent
import de.needanapp.needafile.pick.returnPickedFiles
import de.needanapp.needafile.pick.saveHere
import de.needanapp.needafile.remote.RemoteConnections
import de.needanapp.needafile.remote.RemoteServer
import de.needanapp.needafile.remote.RemoteServersStore
import de.needanapp.needafile.servers.ServerEditor
import de.needanapp.needafile.servers.ServerEditorRequest
import de.needanapp.needafile.servers.ServerForm
import de.needanapp.needafile.servers.ServerInfoCard
import de.needanapp.needafile.servers.ServersPage
import de.needanapp.needafile.servers.TrustPrompt
import de.needanapp.needafile.settings.SettingsPage
import de.needanapp.needafile.settings.SettingsSubPage
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.transfer.TransferClipboard
import de.needanapp.needafile.transfer.TransferManager
import de.needanapp.needafile.vault.SavedVault
import de.needanapp.needafile.vault.VaultAccess
import de.needanapp.needafile.vault.VaultBiometrics
import de.needanapp.needafile.vault.VaultSessions
import de.needanapp.needafile.vault.VaultStore
import de.needanapp.needafile.vault.VaultUnlockPrompt
import de.needanapp.needafile.vault.VaultsPage
import de.needanapp.needafile.vault.getVaultLocation
import de.needanapp.needafile.vault.hideFromRecents
import de.needanapp.needafile.vault.isVaultPath
import de.needanapp.needafile.viewer.Viewers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The pages of the pill, side by side in this order, and the settings, which open on top of them from the menu. */
private enum class HomePage { FILES, SERVERS, VAULTS, SETTINGS }

private const val PILL_PAGES = 3

/** What the pill and an opened card offer while selecting, in the same order, before "more". */
private val SELECTION_ACTIONS = listOf(FileAction.COPY, FileAction.CUT, FileAction.RENAME, FileAction.DELETE)

/** The buttons of the info card; renaming happens in the list, so it waits under "more". */
private val INFO_ACTIONS = listOf(FileAction.COPY, FileAction.CUT, FileAction.DELETE)

/** The connection the server card last showed, kept while it slides away. */
private class LastServer {
    var server: RemoteServer? = null
}

/** The items the info card last showed, kept while it slides away. */
private class LastInfo {
    var items: List<FileEntry>? = null
}

@Composable
fun HomeScreen(activity: HomeActivity, model: FileBrowserModel, themeSettings: ThemeSettings) {
    // the pages of the pill lie side by side; the settings open from the menu on top of them and lead back there
    val pagerState = rememberPagerState { PILL_PAGES }
    var inSettings by rememberSaveable { mutableStateOf(false) }
    var settingsSubPage by rememberSaveable { mutableStateOf<SettingsSubPage?>(null) }
    val scope = rememberCoroutineScope()
    // the header follows a page once a swipe has come to rest, so it doesn't jump midway
    val page = if (inSettings) HomePage.SETTINGS else HomePage.entries[pagerState.settledPage]
    // read again when the settings close, where it is switched
    val swipePages = remember(inSettings) { activity.config.swipePages }
    // a sideways swipe that doesn't change the page mustn't end as a tap on the entry under the finger
    val swallowedSwipe = rememberDraggableState { }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    // a long-pressed tab shows its whole path over the screen
    var tabPath by remember { mutableStateOf<TabPathRequest?>(null) }
    var serversVersion by remember { mutableIntStateOf(0) }
    val operations = remember(activity, model) { FileOperations(activity, model) { dialog = it } }
    val search = rememberSearchState()
    val pillScroll = rememberPillScrollBehavior()
    val isFiles = page == HomePage.FILES
    // another app waits for a file: only the files page, the pill carries the main action
    val pick = model.pickRequest
    var pickDialog by remember { mutableStateOf<PickDialog?>(null) }
    // adding or editing a server replaces the page until saved or left with back
    var serverEditor by remember { mutableStateOf<ServerEditorRequest?>(null) }
    val serverForm = remember(serverEditor) { serverEditor?.let { ServerForm(it.server) } }

    // search runs while typing or picking a kind of file, a moment after the last key
    LaunchedEffect(search.isActive, search.query, model.searchFilter, model.currentPath) {
        if (search.isActive && isFiles) {
            delay(SEARCH_DELAY_MS)
            model.search(search.query)
        } else {
            model.closeSearch()
        }
    }
    LaunchedEffect(model.currentPath) {
        if (search.isActive) search.close()
    }
    // a vault's names and thumbnails don't stay in the recent apps after it locks
    val showsVault = model.currentPath.isVaultPath()
    LaunchedEffect(showsVault) { activity.hideFromRecents(showsVault) }
    LaunchedEffect(search.isActive) {
        if (!search.isActive) model.searchFilter = null
    }
    LaunchedEffect(model.filesPageRequests) {
        if (model.filesPageRequests > 0) {
            inSettings = false
            settingsSubPage = null
            serverEditor = null
            pagerState.scrollToPage(HomePage.FILES.ordinal)
        }
    }
    LaunchedEffect(model.settingsPageRequests) {
        if (model.settingsPageRequests > 0) {
            inSettings = true
            settingsSubPage = null
            serverEditor = null
        }
    }
    // a new page or folder starts with the pill in view, wherever the last one was scrolled to; so does the end of a
    // copy, archive job or deletion, whose progress bar kept the pill in view meanwhile
    LaunchedEffect(page, settingsSubPage, serverEditor, model.currentPath, model.showsPlaces, model.showsTransfer) { pillScroll.show() }


    fun openSettings() {
        inSettings = true
        settingsSubPage = null
        search.close()
        activity.hideKeyboard()
    }

    fun leaveSettings() {
        inSettings = false
        settingsSubPage = null
    }

    /** Slides to a page of the pill, as a swipe would. */
    fun showPage(target: HomePage) {
        inSettings = false
        settingsSubPage = null
        if (pagerState.targetPage != target.ordinal) scope.launch { pagerState.animateScrollToPage(target.ordinal) }
    }

    // a new vault goes into a folder picked first, the open one unless that is in an archive or a vault
    fun createVault() {
        val current = model.currentPath
        val inVault = { path: String -> path.isVaultPath() || VaultStore.all(activity).any { path == it.location || path.startsWith(it.location.trimEnd('/') + "/") } }
        val start = current.takeUnless { it.isArchivePath() || inVault(it) } ?: activity.config.homeFolder
        dialog = FileDialog.PickFolder(FolderPick(R.string.vault_create_where, R.string.create_here, start, allowServers = true) { folder ->
            if (folder.isArchivePath() || inVault(folder)) activity.toast(R.string.vault_not_here) else dialog = FileDialog.CreateVault(folder)
        })
    }

    fun showServerDialog(server: RemoteServer?, openAfterwards: Boolean) {
        search.close()
        activity.hideKeyboard()
        serverEditor = ServerEditorRequest(server, openAfterwards)
    }

    fun saveServer(request: ServerEditorRequest, form: ServerForm) {
        val saved = form.build() ?: return
        activity.hideKeyboard()
        RemoteServersStore.upsert(activity, saved)
        RemoteConnections.invalidate(saved.id)
        model.refreshServerNames()
        serversVersion++
        serverEditor = null
        pillScroll.show()
        if (request.openAfterwards) {
            showPage(HomePage.FILES)
            model.openInTab(saved.getStartPath())
        }
    }

    fun onItemClick(item: FileEntry) {
        when {
            model.isSelecting && (pick == null || !item.isDirectory) -> model.toggleSelection(item)
            item.isDirectory -> model.open(item.path)
            pick != null -> activity.onFileTapped(pick, item.path) { pickDialog = it }
            // the configuration of a locked vault opens the vault, as in Cryptomator
            model.lockedVault != null && (item.name == VaultAccess.CONFIG_FILE || item.name == VaultAccess.MASTERKEY_FILE) ->
                model.requestVaultUnlock(model.lockedVault!!)
            // archives open like folders, read-only
            item.path.isArchiveFile() -> model.open(archiveRootPath(item.path))
            // a vault picture: the viewer swipes through the folder's others, decrypting them as they come
            item.path.isVaultPath() && Viewers.isImage(item.name) -> Viewers.openVaultPictures(activity, model.shownInOrder(activity), item)
            else -> operations.openFile(item) { Viewers.openOrHandOver(activity, it, single = item.path.isVirtualPath()) }
        }
    }

    fun onItemLongClick(item: FileEntry) {
        if (pick != null) {
            model.toggleSelection(item)
            return
        }

        // a long press selects (dragging on selects more); the pill then holds the actions, "more" the whole menu
        model.select(listOf(item))
    }

    BackHandler(enabled = isFiles && model.isSelecting) { model.clearSelection() }
    BackHandler(enabled = isFiles && !model.isSelecting && model.canGoUp()) { model.goUp() }
    BackHandler(enabled = isFiles && model.showsPlaces) { model.leavePlaces() }
    BackHandler(enabled = page == HomePage.SETTINGS && settingsSubPage != null) { settingsSubPage = null }
    BackHandler(enabled = page == HomePage.SETTINGS && settingsSubPage == null) { leaveSettings() }
    BackHandler(enabled = page == HomePage.SERVERS || page == HomePage.VAULTS) { showPage(HomePage.FILES) }
    BackHandler(enabled = serverEditor != null) { serverEditor = null }

    // reading the version shows locking and unlocking right away
    val unlockedVaults = remember(VaultSessions.version) { VaultSessions.unlockedLocations() }
    val shownVault = model.currentPath.takeIf { isFiles && !model.showsPlaces && it.isVaultPath() }?.getVaultLocation()?.takeIf { it in unlockedVaults }
    // the pages of the pill; the settings open from its menu. The vaults' lock is open, with a count, while some are.
    val pages = listOf(
        PillItem(Icons.Outlined.Folder, stringResource(R.string.files_tab)),
        PillItem(Icons.Outlined.Dns, stringResource(R.string.servers)),
        PillItem(
            if (unlockedVaults.isEmpty()) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
            stringResource(R.string.vaults),
            badgeCount = unlockedVaults.size.takeIf { it > 0 },
        ),
    )
    val crumbs = if (isFiles && model.currentPath.isNotEmpty()) model.breadcrumbs(model.currentPath) else emptyList()
    val editor = serverEditor
    val title = when {
        editor != null -> stringResource(if (editor.server == null) R.string.add_server else R.string.edit_server)
        isFiles && model.isSelecting -> pluralStringResource(R.plurals.selected_items, model.selection.size, model.selection.size)
        isFiles && model.showsPlaces -> stringResource(R.string.places)
        isFiles -> crumbs.lastOrNull()?.first ?: pages[0].label
        page == HomePage.SETTINGS && settingsSubPage != null -> stringResource(settingsSubPage!!.title)
        page == HomePage.SETTINGS -> stringResource(R.string.settings)
        else -> pages[page.ordinal].label
    }
    val showsClipboard = isFiles && (model.clipboard.isNotEmpty() || model.showsTransfer)
    // the places offer nothing to paste into, a running copy shows there all the same
    val pasteBarHidden = model.showsPlaces && !model.showsTransfer
    val isSelectingFiles = isFiles && model.isSelecting

    // the selection card floats over the files, which leave room for it as for the pill
    val showsSelectionCard = editor == null && isSelectingFiles && !model.hasOpenCard
    var selectionCardHeight by remember { mutableStateOf(0.dp) }
    val selectionCardSpace = if (showsSelectionCard) (selectionCardHeight - SELECTION_PILL_HEIGHT.dp).coerceAtLeast(0.dp) else 0.dp
    // a changed selection shows its actions folded
    LaunchedEffect(model.selection.size) { model.cardExpanded = false }

    /**
     * The buttons of a card for [items]: [row] side by side, deleting on red, then [extra] and "more", which unfolds the
     * rest grouped below them, apart from [left] out ones. [onAction] runs before any of the actions.
     */
    @Composable
    fun cardMenu(
        items: List<FileEntry>,
        row: List<FileAction>,
        isExpanded: Boolean,
        onExpand: (Boolean) -> Unit,
        extra: CardAction? = null,
        left: Set<FileAction> = emptySet(),
        onAction: () -> Unit = {},
    ): CardMenu {
        val available = if (items.isEmpty()) emptyList() else operations.availableActions(items)
        val actions = row.map { action ->
            CardAction(action.vector, stringResource(action.label), enabled = action in available, isDestructive = action == FileAction.DELETE) {
                onAction()
                operations.perform(action, items)
            }
        } + listOfNotNull(extra) + CardAction(
            icon = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.MoreHoriz,
            label = stringResource(if (isExpanded) R.string.less_actions else R.string.more_actions),
            isActive = isExpanded,
        ) {
            onExpand(!isExpanded)
        }
        // what fits no group still shows, with the last one
        val rest = available - row.toSet() - FileAction.SELECT - left
        val ungrouped = rest - FileAction.CARD_GROUPS.flatMap { it.second }.toSet()
        val groups = FileAction.CARD_GROUPS.mapIndexedNotNull { index, (title, members) ->
            val shown = members.filter { it in rest } + if (index == FileAction.CARD_GROUPS.lastIndex) ungrouped else emptyList()
            shown.takeIf { it.isNotEmpty() }?.let { list ->
                CardActionGroup(stringResource(title), list.map { action ->
                    CardAction(action.vector, stringResource(action.shortLabel)) {
                        onExpand(false)
                        onAction()
                        operations.perform(action, items)
                    }
                })
            }
        }
        return CardMenu(actions, groups)
    }

    /** The buttons of an opened card or the selection card. */
    @Composable
    fun cardMenu(): CardMenu = cardMenu(model.selectedItems, SELECTION_ACTIONS, model.cardExpanded, { model.cardExpanded = it })

    // a saved connection floats in place of the pill the same way, kept while it slides away
    var serverInfo by remember { mutableStateOf<RemoteServer?>(null) }
    val lastServer = remember { LastServer() }
    serverInfo?.let { lastServer.server = it }
    LaunchedEffect(page) { serverInfo = null }

    fun deleteServer(server: RemoteServer) {
        VaultSessions.lockServer(activity, server.id)
        VaultStore.all(activity).filter { serverIdOf(it.location) == server.id }.forEach { VaultBiometrics.forget(activity, it.location) }
        VaultStore.forgetServer(activity, server.id)
        RemoteServersStore.delete(activity, server.id)
        RemoteConnections.invalidate(server.id)
        model.refreshServerNames()
        model.closeServerTabs(server.id)
        serversVersion++
    }

    // what is known about some items floats in place of the pill, the info unfolded; it stays while it slides away
    val infoItems = (dialog as? FileDialog.Info)?.items
    val lastInfo = remember { LastInfo() }
    if (infoItems != null) lastInfo.items = infoItems
    var infoFold by remember { mutableStateOf(InfoFold.DETAILS) }
    LaunchedEffect(infoItems) { infoFold = InfoFold.DETAILS }
    // and goes when the files show something else
    LaunchedEffect(model.currentPath, page) { if (dialog is FileDialog.Info) dialog = null }
    fun closeInfo() {
        if (dialog is FileDialog.Info) dialog = null
    }

    // a swipe changes the page unless selecting, searching or picking for another app keeps the files in view
    val canSwipe = swipePages && pick == null && !model.isSelecting && !search.isActive

    AppScaffold(
        title = title,
        // below the folder's name its place, e.g. the storage and its free space
        subtitle = when {
            editor != null || !isFiles || model.isSelecting -> null
            model.showsPlaces -> if (pick == null) stringResource(R.string.places_hint) else null
            model.currentPath.isNotEmpty() -> placeSubtitle(model, model.currentPath)
            else -> null
        },
        search = rememberSearchStateFor(editor == null && isFiles && !model.isSelecting && !model.showsPlaces, search),
        navigationIcon = when {
            editor != null -> {
                { IconButton(onClick = { serverEditor = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.go_back)) } }
            }

            isFiles && model.isSelecting -> {
                { IconButton(onClick = { model.clearSelection() }) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel_selection)) } }
            }

            isFiles && !model.showsPlaces && model.canGoUp() -> {
                { IconButton(onClick = { model.goUp() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.go_up)) } }
            }

            page == HomePage.SETTINGS && settingsSubPage != null -> {
                { IconButton(onClick = { settingsSubPage = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.go_back)) } }
            }

            page == HomePage.SETTINGS -> {
                { IconButton(onClick = { leaveSettings() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.go_back)) } }
            }

            pick != null -> {
                { IconButton(onClick = { activity.finish() }) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel_picking)) } }
            }

            else -> null
        },
        actions = {
            if (editor != null && serverForm != null) {
                TextButton(onClick = { saveServer(editor, serverForm) }) { Text(stringResource(R.string.server_save)) }
            } else if (isFiles && model.isSelecting && (pick == null || pick.allowMultiple)) {
                IconButton(onClick = { model.selectAll() }) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.select_all)) }
            }
        },
        headerBottom = when {
            editor == null && isFiles && search.isActive -> {
                // picking a kind of file is a search of its own, the keyboard makes way for the results
                {
                    SearchFilterRows(model.searchFilter) {
                        model.searchFilter = it
                        activity.hideKeyboard()
                    }
                }
            }

            else -> null
        },
        bottomBar = { hazeState ->
            if (pick != null) {
                val content = pickPillContent(pick, model.selection.size)
                ActionPill(
                    label = content.label,
                    icon = content.icon,
                    onClick = when {
                        !content.isAction -> null
                        pick.kind == PickRequest.Kind.CREATE_DOCUMENT -> {
                            { activity.saveHere(pick, model.currentPath) { pickDialog = it } }
                        }

                        else -> {
                            { activity.returnPickedFiles(model.selectedItems.map { it.path }) }
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    hazeState = hazeState,
                    leadingAction = PillAction(Icons.Outlined.Add, stringResource(R.string.create_new_title)) { dialog = FileDialog.Create },
                    trailingAction = PillAction(Icons.Outlined.Tune, stringResource(R.string.folder_options)) { dialog = FileDialog.Options },
                    visible = pillScroll.isVisible || model.isSelecting,
                )
            } else {
                // above the pill, which stays in view meanwhile; not in a column with it, as its unfolded menu covers the screen;
                // above the selection card too, but out of the way while that is unfolded
                if (isFiles && editor == null) {
                    PasteBar(
                        model = model,
                        hazeState = hazeState,
                        onPaste = { operations.paste() },
                        onCancel = { TransferManager.cancel() },
                        onPause = { TransferManager.pause() },
                        onResume = { TransferManager.resume() },
                        onClear = { TransferClipboard.clear() },
                        isCovered = showsSelectionCard && model.cardExpanded || infoItems != null || pasteBarHidden,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = PASTE_BAR_OFFSET.dp + selectionCardSpace),
                    )
                }
                NavigationPill(
                    items = pages,
                    // where a swipe or a tap is heading; the settings hide the pill and lead back to its page
                    selectedIndex = pagerState.targetPage,
                    onSelect = {
                        showPage(HomePage.entries[it])
                        search.close()
                        pillScroll.show()
                        activity.hideKeyboard()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    hazeState = hazeState,
                    leadingAction = when {
                        page == HomePage.SERVERS -> PillAction(Icons.Outlined.Add, stringResource(R.string.add_server)) { showServerDialog(null, openAfterwards = false) }
                        page == HomePage.VAULTS -> PillAction(Icons.Outlined.Add, stringResource(R.string.vault_create_title)) { createVault() }
                        else -> null
                    },
                    // the settings on every page, the folder options below them; locking the open vaults closest to the button:
                    // the one open alone, or with several the one this tab shows and then all of them
                    trailingMenu = PillMenu(
                        Icons.Outlined.Menu,
                        stringResource(R.string.menu),
                        listOfNotNull(
                            PillAction(Icons.Outlined.Settings, stringResource(R.string.settings)) { openSettings() },
                            if (isFiles && !model.showsPlaces) PillAction(Icons.Outlined.Tune, stringResource(R.string.folder_options)) { dialog = FileDialog.Options } else null,
                            (unlockedVaults.singleOrNull() ?: shownVault)?.let { location ->
                                PillAction(Icons.Outlined.Lock, stringResource(R.string.vault_lock_named, SavedVault(location).name)) {
                                    model.lockVault(location)
                                    activity.toast(R.string.vault_locked_toast)
                                }
                            },
                            if (unlockedVaults.size > 1) {
                                PillAction(Icons.Outlined.Lock, stringResource(R.string.vault_lock_all)) {
                                    model.lockAllVaults()
                                    activity.toast(R.string.vaults_locked_toast)
                                }
                            } else {
                                null
                            },
                        ),
                    ),
                    visible = editor == null && page != HomePage.SETTINGS && !isSelectingFiles && infoItems == null && serverInfo == null && (pillScroll.isVisible || showsClipboard),
                    // the plus turns the pill into the bar of what can be created here
                    leadingBar = if (isFiles && !model.isSelecting && !model.showsPlaces && !model.currentPath.isArchivePath()) {
                        createBar(model, show = { dialog = it }, onAddServer = { showServerDialog(null, openAfterwards = true) })
                    } else {
                        null
                    },
                )
                // while selecting, the actions for the selection take the place of the pages
                // unless a single entry is selected, whose opened card has them
                val selection = model.selectedItems
                SelectionCard(
                    count = selection.size,
                    summary = selectionSummary(selection),
                    names = selectionNames(selection),
                    menu = cardMenu(),
                    expanded = model.cardExpanded,
                    onFold = { model.cardExpanded = false },
                    onSelectAll = { model.selectAll() },
                    onHeightChange = { selectionCardHeight = it },
                    visible = showsSelectionCard && infoItems == null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                lastServer.server?.let { server ->
                    ServerInfoCard(
                        server = server,
                        onOpen = {
                            showPage(HomePage.FILES)
                            model.openInTab(server.getStartPath())
                        },
                        onEdit = { showServerDialog(server, openAfterwards = false) },
                        onDelete = { deleteServer(server) },
                        onClose = { serverInfo = null },
                        visible = serverInfo != null && page == HomePage.SERVERS,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                lastInfo.items?.let { items ->
                    FileInfoCard(
                        items = items,
                        menu = cardMenu(
                            items,
                            INFO_ACTIONS,
                            isExpanded = infoFold == InfoFold.MORE,
                            onExpand = { infoFold = if (it) InfoFold.MORE else InfoFold.DETAILS },
                            // shown again, it closes the card
                            extra = CardAction(Icons.Outlined.Info, stringResource(R.string.info_short), isActive = infoFold == InfoFold.DETAILS) {
                                if (infoFold == InfoFold.DETAILS) closeInfo() else infoFold = InfoFold.DETAILS
                            },
                            left = setOf(FileAction.PROPERTIES, FileAction.ARCHIVE_INFO),
                            onAction = ::closeInfo,
                        ),
                        fold = infoFold,
                        onBack = { if (infoFold == InfoFold.MORE) infoFold = InfoFold.DETAILS else closeInfo() },
                        visible = infoItems != null,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        },
    ) { padding ->
        if (editor != null && serverForm != null) {
            ServerEditor(serverForm, padding, pillScroll.nestedScrollConnection)
        } else if (page == HomePage.SETTINGS) {
            SettingsPage(
                themeSettings = themeSettings,
                subPage = settingsSubPage,
                onSubPage = { settingsSubPage = it },
                contentPadding = padding,
                nestedScrollConnection = pillScroll.nestedScrollConnection,
                onSettingsChanged = { model.refresh() },
                onBackupRestored = {
                    model.reloadTabs()
                    activity.recreate()
                },
                onShowIntroduction = activity::showOnboarding,
            )
        } else HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().draggable(swallowedSwipe, Orientation.Horizontal, enabled = !canSwipe),
            userScrollEnabled = canSwipe,
            key = { it },
        ) { pageIndex ->
            when (HomePage.entries[pageIndex]) {
                HomePage.FILES -> if (model.showsPlaces) {
                    PlacesPage(
                        model = model,
                        contentPadding = padding,
                        nestedScrollConnection = pillScroll.nestedScrollConnection,
                        extraBottomSpace = if (showsClipboard && !pasteBarHidden) PASTE_BAR_SPACE.dp else 0.dp,
                        serversVersion = serversVersion,
                        onShowTabPath = { index, chip -> tabPath = TabPathRequest(index, chip) },
                        onAddServer = { showServerDialog(null, openAfterwards = true) },
                        onAddVault = { createVault() },
                    )
                } else FileBrowserPage(
                    model = model,
                    contentPadding = padding,
                    nestedScrollConnection = pillScroll.nestedScrollConnection,
                    extraBottomSpace = (if (showsClipboard) PASTE_BAR_SPACE.dp else 0.dp) + selectionCardSpace,
                    onItemClick = ::onItemClick,
                    onItemLongClick = ::onItemLongClick,
                    onShowTabPath = { index, chip -> tabPath = TabPathRequest(index, chip) },
                    cardMenu = if (pick == null) {
                        { cardMenu() }
                    } else {
                        null
                    },
                    validateName = { item, name -> operations.validateNewName(item.path.substringBeforeLast('/'), name, currentName = item.name) },
                    onRename = { item, name ->
                        model.renaming = null
                        if (name.trim() != item.name) operations.renameTo(item, name.trim())
                    },
                )

                HomePage.SERVERS -> ServersPage(
                    version = serversVersion,
                    contentPadding = padding,
                    nestedScrollConnection = pillScroll.nestedScrollConnection,
                    onOpen = { server ->
                        showPage(HomePage.FILES)
                        model.openInTab(server.getStartPath())
                    },
                    onEdit = { showServerDialog(it, openAfterwards = false) },
                    onInfo = { serverInfo = it },
                    onAdd = { showServerDialog(null, openAfterwards = false) },
                    onServersAdded = {
                        model.refreshServerNames()
                        serversVersion++
                    },
                )

                HomePage.VAULTS -> VaultsPage(
                    model = model,
                    contentPadding = padding,
                    nestedScrollConnection = pillScroll.nestedScrollConnection,
                    onOpen = { location -> model.showVault(location) },
                    onCreate = { createVault() },
                    show = { dialog = it },
                )

                HomePage.SETTINGS -> Unit
            }
        }
    }

    TabPathOverlay(model, tabPath.takeIf { isFiles && editor == null }, onDismiss = { tabPath = null })
    FileDialogHost(
        dialog = dialog,
        model = model,
        operations = operations,
        onDismiss = { dialog = null },
        show = { dialog = it },
        onAddServer = { showServerDialog(null, openAfterwards = true) },
    )
    PickDialogHost(pickDialog, model.currentPath, onDismiss = { pickDialog = null }, show = { pickDialog = it })
    model.trustPrompt?.let { decision ->
        TrustPrompt(decision, onTrusted = { model.refresh() }, onDismiss = { model.trustPrompt = null })
    }
    // a vault locked while elsewhere asks for its password once its files are shown again
    model.vaultUnlockRequest?.takeIf { isFiles }?.let { request ->
        key(request) {
            VaultUnlockPrompt(
                request.location,
                onUnlocked = { model.answerVaultUnlock(unlocked = true) },
                onCancel = { model.answerVaultUnlock(unlocked = false) },
                onForgotPassword = {
                    model.answerVaultUnlock(unlocked = false)
                    dialog = FileDialog.ResetVaultPassword(request.location)
                },
            )
        }
    }
    model.archiveUnlockRequest?.let { request ->
        ArchivePasswordPrompt(
            request.location,
            request.isWrong,
            onPassword = { model.answerArchivePassword(it) },
            onCancel = { model.answerArchivePassword(null) },
        )
    }
}

/** The magnifier only where searching makes sense. */
@Composable
private fun rememberSearchStateFor(enabled: Boolean, search: de.needanapp.design.components.SearchState) = if (enabled || search.isActive) search else null

private const val SEARCH_DELAY_MS = 250L
private const val PASTE_BAR_SPACE = 80

/** The height of the pill the selection card takes the place of, which the files leave room for already. */
private const val SELECTION_PILL_HEIGHT = 52

// the pill's bottom margin, its height and the gap above it
private const val PASTE_BAR_OFFSET = 16 + 52 + 12
