package de.needanapp.needafile.files

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.needanapp.needafile.R
import de.needanapp.needafile.archive.ArchivePasswordException
import de.needanapp.needafile.archive.ArchiveSessions
import de.needanapp.needafile.archive.getArchiveInnerPath
import de.needanapp.needafile.archive.getArchiveLocation
import de.needanapp.needafile.archive.isArchivePath
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.Sorting
import de.needanapp.needafile.core.ViewType
import de.needanapp.needafile.core.entryOrder
import de.needanapp.needafile.core.removableStorage
import de.needanapp.needafile.core.showError
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.core.volumeName
import de.needanapp.needafile.core.volumeRoot
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.pick.PickRequest
import de.needanapp.needafile.remote.NeedsTrustDecision
import de.needanapp.needafile.remote.RemoteServersStore
import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.remote.findTrustDecision
import de.needanapp.needafile.remote.getRemoteParentPath
import de.needanapp.needafile.remote.getRemoteServerId
import de.needanapp.needafile.remote.getRemoteServerPath
import de.needanapp.needafile.remote.isRemotePath
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.transfer.TransferClipboard
import de.needanapp.needafile.transfer.TransferKind
import de.needanapp.needafile.transfer.TransferManager
import de.needanapp.needafile.transfer.describeTransferResult
import de.needanapp.needafile.vault.StoredEntry
import de.needanapp.needafile.vault.VaultAccess
import de.needanapp.needafile.vault.VaultLockedException
import de.needanapp.needafile.vault.VaultSessions
import de.needanapp.needafile.vault.getVaultInnerPath
import de.needanapp.needafile.vault.getVaultLocation
import de.needanapp.needafile.vault.isVaultPath
import de.needanapp.needafile.vault.vaultRootPath
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a tab points to, for its chip: a phone storage, the SD card, root, a server, an archive or a vault. */
enum class LocationKind { PHONE, SD_CARD, ROOT, SERVER, ARCHIVE, VAULT }

/**
 * State of the file browser: tabs, the open folder, its items, search, selection, clipboard and running transfers.
 * Lives across configuration changes; the activity attaches itself for the work that needs it (dialogs, permissions).
 */
class FileBrowserModel : ViewModel() {
    private var activity: BaseActivity? = null

    val tabs = mutableStateListOf<String>()
    var activeTab by mutableIntStateOf(0)
        private set
    var currentPath by mutableStateOf("")
        private set

    /** The places (phone, servers, vaults, recent folders) are shown instead of the active tab's folder. */
    var showsPlaces by mutableStateOf(false)
        private set

    /** Folders opened lately, newest first, see [recordRecentFolder]; shown among the places. */
    var recentFolders by mutableStateOf<List<RecentFolder>>(emptyList())
        private set
    var items by mutableStateOf<List<FileEntry>>(emptyList())
        private set

    /** The folder [items] belong to; while another one loads, they are still the previous folder's. */
    var itemsPath by mutableStateOf("")
        private set

    /** How [items] are sorted, the [Sorting] flags of their folder. */
    var sorting by mutableIntStateOf(Sorting.DEFAULT)
        private set
    var isLoading by mutableStateOf(false)
        private set

    /** Why the current folder couldn't be read, e.g. a server out of reach; null when it could. */
    var loadError by mutableStateOf<String?>(null)
        private set

    /** The current folder is read once a password or a certificate is answered; until then there is nothing to show. */
    var waitsForAnswer by mutableStateOf(false)
        private set

    /** The current folder is one Android keeps apps out of, see [RestrictedFolderException]. */
    var isRestrictedFolder by mutableStateOf(false)
        private set
    /** How the folder is shown; with [isAutoGallery] the gallery stands in for the stored view. */
    var view by mutableStateOf(FolderView(ViewType.DETAILS, ZOOM_NORMAL, GroupBy.NONE, isCustom = false))
        private set

    /** The folder holds mostly pictures and videos and has no view of its own, so it shows as gallery. */
    var isAutoGallery by mutableStateOf(false)
        private set
    var showFileNamesInGrid by mutableStateOf(true)
        private set
    var listAsCards by mutableStateOf(true)
        private set

    /** Entries show as cards here, so a single selected one opens with its actions in place of the pill. */
    val opensCards get() = listAsCards && (searchResults != null || view.type == ViewType.LIST || view.type == ViewType.DETAILS)

    /** The opened card is scrolled out of view, so the pill stands in for it. */
    var openCardOutOfView by mutableStateOf(false)

    /** The single selected entry shows as an opened card in view, its actions with it. */
    val hasOpenCard get() = selection.size == 1 && opensCards && !openCardOutOfView

    /** The entry whose name is edited right in its opened card. */
    var renaming by mutableStateOf<String?>(null)

    /** The opened card shows the rest of the actions too. */
    var cardExpanded by mutableStateOf(false)

    /** Collapsed group headings, by folder, grouping and group. */
    private val collapsedGroups = mutableStateMapOf<String, Boolean>()

    /** Search results grouped by folder, null while not searching. */
    var searchResults by mutableStateOf<List<FileEntry>?>(null)
        private set
    var isSearching by mutableStateOf(false)
        private set

    /** The kind of files the search is narrowed to, null for any; reset with the search. */
    var searchFilter by mutableStateOf<SearchFilter?>(null)

    /** Selected items by path, in the order they were picked. */
    val selection = mutableStateMapOf<String, FileEntry>()
    val isSelecting get() = selection.isNotEmpty()

    var clipboard by mutableStateOf(TransferClipboard.items)
        private set
    var clipboardIsCut by mutableStateOf(TransferClipboard.isCut)
        private set
    var transferRunning by mutableStateOf(TransferManager.isRunning)
        private set
    var transferKind by mutableStateOf(TransferManager.kind)
        private set
    var transferProgress by mutableStateOf(TransferManager.currentProgress)
        private set
    var transferState by mutableStateOf(TransferManager.state)
        private set

    /** The connection problem a waiting or given-up copy has, and until when a waiting one keeps trying. */
    var transferProblem by mutableStateOf(TransferManager.problem)
        private set
    var transferWaitingUntil by mutableStateOf(TransferManager.waitingUntil)
        private set

    /** The running deletion takes long enough to show its progress and mark its entries; quick ones go by unseen. */
    var deletionShown by mutableStateOf(false)
        private set
    private var deletionTimer: Job? = null

    /** The running transfer shows above the pill: any but a deletion that is still quick. */
    val showsTransfer get() = transferRunning && (transferKind != TransferKind.DELETE || deletionShown)

    /** Where the deletion shown has come to among the selected entries, null while none is shown. */
    val deletionMarks: DeletionMarks?
        get() {
            if (!deletionShown) return null
            val paths = TransferManager.sourcePaths
            val done = (transferProgress?.itemsDone ?: 0).coerceIn(0, paths.size)
            return DeletionMarks(paths.take(done).toHashSet(), paths.getOrNull(done), paths.drop(done + 1).toHashSet())
        }

    /** A server presented a key or certificate that isn't confirmed yet; the screen asks and reloads once it is trusted. */
    var trustPrompt by mutableStateOf<NeedsTrustDecision?>(null)

    /** The archive being opened hides its file names behind a password; the screen asks for it. */
    var archiveUnlockRequest by mutableStateOf<ArchiveUnlockRequest?>(null)
        private set

    /** A locked vault is to be opened; the screen asks for its password. */
    var vaultUnlockRequest by mutableStateOf<VaultUnlockRequest?>(null)
        private set

    /** The open folder is a vault that stays locked, the location of it; its encrypted files are shown. */
    var lockedVault by mutableStateOf<String?>(null)
        private set

    /** Vaults whose password request was turned down; their folder then shows the encrypted files until left. */
    private val declinedVaults = HashSet<String>()

    private var serverNames = emptyMap<String, String>()
    private val scrollPositions = HashMap<String, Pair<Int, Int>>()
    private var loadGeneration = 0
    private var searchGeneration = 0

    /** Mime types another app wants to pick, `""` = everything. */
    private var wantedMimeTypes = listOf("")

    /** Set while another app uses needafile to pick or save a file: local folders only, the user's tabs stay untouched. */
    var pickRequest: PickRequest? = null
        private set
    val isPicking get() = pickRequest != null

    private val clipboardListener: () -> Unit = {
        clipboard = TransferClipboard.items
        clipboardIsCut = TransferClipboard.isCut
    }
    private val transferListener: (TransferManager.Event) -> Unit = { onTransferEvent(it) }
    private val vaultListener: (String) -> Unit = { onVaultChanged(it) }

    init {
        TransferClipboard.addListener(clipboardListener)
        TransferManager.addListener(transferListener)
        VaultSessions.addListener(vaultListener)
    }

    override fun onCleared() {
        TransferClipboard.removeListener(clipboardListener)
        TransferManager.removeListener(transferListener)
        VaultSessions.removeListener(vaultListener)
    }

    /**
     * [freshStart]: the app was closed properly before (see HomeActivity), so it starts with one tab in the home folder
     * instead of the saved tabs. Not when picking, that leaves the user's tabs alone.
     */
    fun attach(activity: BaseActivity, pickRequest: PickRequest?, freshStart: Boolean = false) {
        this.activity = activity
        refreshServerNames()
        if (recentFolders.isEmpty()) recentFolders = activity.config.recentFolders
        if (tabs.isEmpty() && freshStart && pickRequest == null) {
            tabs.add(activity.config.homeFolder)
            activeTab = 0
            saveTabs()
        }
        if (tabs.isEmpty()) {
            val config = activity.config
            // tabs of servers deleted meanwhile, e.g. by a restored backup, are left out
            val isKnown = { tab: String -> serverIdOf(tab)?.let(serverNames::containsKey) ?: true }
            val savedTabs = config.fileTabs.filter(isKnown).ifEmpty { listOf(config.homeFolder) }
            val savedActive = config.fileTabs.take(config.activeFileTab).count(isKnown).coerceIn(0, savedTabs.lastIndex)
            if (pickRequest != null) {
                this.pickRequest = pickRequest
                wantedMimeTypes = pickRequest.mimeTypes
                // one tab, starting where the user was unless that is a server
                tabs.add(savedTabs[savedActive].takeUnless { it.isVirtualPath() } ?: config.homeFolder)
            } else {
                tabs.addAll(savedTabs)
                activeTab = savedActive
            }
        }
    }

    fun detach(activity: BaseActivity) {
        if (this.activity === activity) {
            this.activity = null
        }
    }

    // region folders

    /** Opens the folder of the active tab, e.g. at app start; local folders that vanished fall back to [fallbackPath]. */
    fun openActiveTab(fallbackPath: String) {
        val path = tabs.getOrNull(activeTab) ?: fallbackPath
        open(if (isStillThere(path)) path else fallbackPath)
    }

    /** Server folders are checked when loading; archives and vaults on the phone and local folders right away. */
    private fun isStillThere(path: String): Boolean = when {
        path.isRemotePath() -> true
        path.isArchivePath() -> isStillThere(path.getArchiveLocation()) || File(path.getArchiveLocation()).isFile
        path.isVaultPath() -> isStillThere(path.getVaultLocation())
        else -> File(path).isDirectory
    }

    fun open(path: String) {
        val activity = activity ?: return

        // the asking app gets file URIs, server and archive files have none
        if (isPicking && path.isVirtualPath()) {
            return
        }

        val newPath = path.trimEnd('/').ifEmpty { "/" }
        if (newPath != currentPath) {
            selection.clear()
            closeSearch()
            // a vault turned down before asks again when its folder is opened anew
            declinedVaults.clear()
            lockedVault = null
        }
        currentPath = newPath
        tabs[activeTab] = newPath
        showsPlaces = false
        saveTabs()
        rememberRecent(newPath)
        load()
    }

    /**
     * Notes [path] among the recent folders, unless it is a place of its own (a storage, a server) or lies in an archive
     * or a vault, whose names don't go into the settings; not while another app picks a file.
     */
    private fun rememberRecent(path: String) {
        val activity = activity ?: return
        if (isPicking || path.isArchivePath() || path.isVaultPath() || path == locationRoot(path).trimEnd('/').ifEmpty { "/" }) return
        recentFolders = recordRecentFolder(recentFolders, path, System.currentTimeMillis())
        activity.config.recentFolders = recentFolders
    }

    /** The [recentFolders] that can still be opened: no servers deleted meanwhile, no local folders gone; local ones only when picking. */
    fun recentPlaces(): List<RecentFolder> = recentFolders.filter { folder ->
        val path = folder.path
        when {
            path.isVirtualPath() -> !isPicking && serverIdOf(path)?.let(serverNames::containsKey) == true
            else -> File(path).isDirectory
        }
    }

    /** Shows the places in place of the folder; what was selected or searched there is left. */
    fun showPlaces() {
        selection.clear()
        closeSearch()
        showsPlaces = true
    }

    fun leavePlaces() {
        showsPlaces = false
    }

    /** Opens a place in a tab of its own; when picking for another app, in the one tab there is. */
    fun openPlace(path: String) {
        if (isPicking) open(path) else addTab(path)
    }

    fun refresh() = load()

    /** Counts requests to bring the files page to the front, e.g. a folder opened from another screen. */
    var filesPageRequests by mutableIntStateOf(0)
        private set

    /** Counts requests to show the settings, e.g. from the app's entry in the system settings. */
    var settingsPageRequests by mutableIntStateOf(0)
        private set

    fun showSettings() {
        settingsPageRequests++
    }

    /** Opens [path] and asks the screen to show the files page. */
    fun showInFiles(path: String) {
        open(path)
        filesPageRequests++
    }

    /**
     * Shows the vault at [location] in the files page: in a tab already showing it, else in the active tab. A locked
     * vault asks for its password first.
     */
    fun showVault(location: String) {
        showsPlaces = false
        val existing = tabs.indexOfFirst { it == location || (it.isVaultPath() && it.getVaultLocation() == location) }
        if (existing >= 0) switchTab(existing)
        if (!(currentPath.isVaultPath() && currentPath.getVaultLocation() == location)) requestVaultUnlock(location)
        filesPageRequests++
    }

    private fun load() {
        val activity = activity ?: return
        val path = currentPath
        val config = activity.config
        val storedView = config.folderView(path)
        showFileNamesInGrid = config.displayFilenames
        listAsCards = config.listAsCards
        isLoading = true
        loadError = null
        waitsForAnswer = false
        isRestrictedFolder = false
        val generation = ++loadGeneration
        FileLoader(activity).load(
            path = path,
            showHidden = config.showHidden,
            wantedMimeTypes = wantedMimeTypes,
            withChildCounts = (storedView.type == ViewType.LIST || storedView.type == ViewType.DETAILS) && !path.isVirtualPath(),
            onLoaded = { loaded ->
                if (generation == loadGeneration) {
                    sorting = config.getFolderSorting(path)
                    // view and entries change together, so the new folder doesn't flash up in the old view
                    isAutoGallery = !storedView.isCustom && config.autoGallery && !path.isVirtualPath() && isMediaFolder(loaded)
                    view = if (isAutoGallery) storedView.copy(type = ViewType.GALLERY, zoom = config.generalZoom(ViewType.GALLERY)) else storedView
                    if (checkForVault(path, loaded)) return@load
                    items = loaded.sortedWith(entryOrder(sorting))
                    itemsPath = path
                    isLoading = false
                    // items that vanished can't stay selected
                    selection.keys.retainAll(items.mapTo(HashSet()) { it.path })
                }
            },
            onChildCounts = { counts ->
                if (generation == loadGeneration) {
                    items = items.map { item -> counts[item.path]?.let { item.copy(children = it) } ?: item }
                }
            },
            onError = { error ->
                if (generation == loadGeneration) {
                    val trustDecision = error.findTrustDecision()
                    val passwordError = generateSequence(error) { it.cause }.filterIsInstance<ArchivePasswordException>().firstOrNull()
                    val lockedError = generateSequence(error) { it.cause }.filterIsInstance<VaultLockedException>().firstOrNull()
                    isLoading = false
                    waitsForAnswer = trustDecision != null || lockedError != null || passwordError != null
                    if (trustDecision != null) {
                        trustPrompt = trustDecision
                    } else if (lockedError != null && path.isVaultPath()) {
                        // locked meanwhile, e.g. in the background: asked again, then the same folder opens
                        vaultUnlockRequest = VaultUnlockRequest(lockedError.location, path)
                    } else if (passwordError != null && path.isArchivePath()) {
                        archiveUnlockRequest = ArchiveUnlockRequest(path.getArchiveLocation(), passwordError.isWrong)
                    } else if (error is RestrictedFolderException) {
                        isRestrictedFolder = true
                    } else {
                        // the list says so, with a way to try again
                        loadError = activity.describeError(error)
                    }
                }
            },
        )
    }

    /** Retries the archive with [password], or leaves it when the user gave up ([password] = null). */
    fun answerArchivePassword(password: String?) {
        val prompt = archiveUnlockRequest ?: return
        archiveUnlockRequest = null
        if (password == null) {
            if (currentPath.isArchivePath() && currentPath.getArchiveLocation() == prompt.location) open(parentFolder(prompt.location))
        } else {
            ArchiveSessions.setPassword(prompt.location, password)
            refresh()
        }
    }

    /**
     * Whether the folder [loaded] at [path] is a vault: an unlocked one opens instead of its encrypted files, for a
     * locked one the password is asked for. True when another folder opens instead.
     */
    private fun checkForVault(path: String, loaded: List<FileEntry>): Boolean {
        // vaults lie on the phone or on servers; another app picking a file only gets the phone's files
        val canBeVault = !isPicking && (!path.isVirtualPath() || path.isRemotePath())
        if (!canBeVault || !VaultAccess.isVault(loaded.map { StoredEntry(it.name, it.isDirectory, 0, 0) })) {
            lockedVault = null
            return false
        }
        if (VaultSessions.isUnlocked(path)) {
            open(vaultRootPath(path))
            return true
        }
        lockedVault = path
        if (path !in declinedVaults && vaultUnlockRequest == null) vaultUnlockRequest = VaultUnlockRequest(path, vaultRootPath(path))
        return false
    }

    /** Asks for the password of the vault at [location], then opens [target] in it. */
    fun requestVaultUnlock(location: String, target: String = vaultRootPath(location)) {
        if (VaultSessions.isUnlocked(location)) open(target) else vaultUnlockRequest = VaultUnlockRequest(location, target)
    }

    /** The password request ended: unlocked, then its target opens; or turned down, then the encrypted files show. */
    fun answerVaultUnlock(unlocked: Boolean) {
        val request = vaultUnlockRequest ?: return
        vaultUnlockRequest = null
        if (unlocked) {
            open(request.target)
            return
        }
        val isInside = currentPath.isVaultPath() && currentPath.getVaultLocation() == request.location
        if (isInside) open(request.location)
        declinedVaults.add(request.location)
    }

    /** The shown entries (the folder's or the search results) in the order the list shows them, with its groups. */
    fun shownInOrder(context: Context): List<FileEntry> {
        val shown = searchResults ?: items
        if (searchResults != null || view.groupBy == GroupBy.NONE) return shown
        return groupEntries(context, shown, view.groupBy, sorting).flatMap { it.items }
    }

    /** Locks the vault at [location]; the tabs showing it close first. */
    fun lockVault(location: String) {
        val activity = activity ?: return
        closeVaultTabs(location)
        VaultSessions.lock(activity, location)
    }

    fun lockAllVaults() = VaultSessions.unlockedLocations().forEach(::lockVault)

    /** A vault was locked or unlocked; the tabs of a locked one close, also when it was locked from the notification. */
    private fun onVaultChanged(location: String) {
        if (!VaultSessions.isUnlocked(location)) closeVaultTabs(location)
    }

    private fun closeVaultTabs(location: String) = closeTabs { it.isVaultPath() && it.getVaultLocation() == location }

    /** At the top of an archive or vault, going up leaves it for the folder holding it. */
    fun canGoUp() = currentPath.isArchivePath() || currentPath.isVaultPath() || currentPath.trimEnd('/').length > locationRoot(currentPath).trimEnd('/').length

    /** Opens the parent folder; false at the root of the tab's location. */
    fun goUp(): Boolean {
        val root = locationRoot(currentPath).trimEnd('/')
        val path = currentPath.trimEnd('/')
        if (path.length <= root.length) {
            when {
                currentPath.isArchivePath() -> open(parentFolder(currentPath.getArchiveLocation()))
                currentPath.isVaultPath() -> open(parentFolder(currentPath.getVaultLocation()))
                else -> return false
            }
            return true
        }
        open(path.substringBeforeLast('/').ifEmpty { "/" })
        return true
    }

    /** The folder holding [path], on the phone, on a server or in an archive. */
    private fun parentFolder(path: String): String = if (path.isVirtualPath()) {
        buildVirtualPathLike(path, getRemoteParentPath(path.getVirtualInnerPath()))
    } else {
        File(path).parent ?: "/"
    }

    fun rememberScroll(path: String, index: Int, offset: Int) {
        scrollPositions[path] = index to offset
    }

    fun scrollPosition(path: String) = scrollPositions[path] ?: (0 to 0)

    // endregion

    // region tabs

    fun switchTab(index: Int) {
        if (index == activeTab || index !in tabs.indices) return
        activeTab = index
        open(tabs[index])
    }

    fun addTab(path: String) {
        tabs.add(path)
        activeTab = tabs.lastIndex
        open(path)
    }

    /** Opens [path] in the tab at [index], e.g. a folder further up its path. */
    fun openInTab(index: Int, path: String) {
        if (index !in tabs.indices) return
        activeTab = index
        open(path)
    }

    /** Closes the tab at [index] unless it is the last one; the places stay in view if they were. */
    fun closeTab(index: Int) {
        if (tabs.size <= 1 || index !in tabs.indices) return
        val wasActive = index == activeTab
        tabs.removeAt(index)
        if (index < activeTab) activeTab--
        activeTab = activeTab.coerceAtMost(tabs.lastIndex)
        if (wasActive) {
            val keepPlaces = showsPlaces
            open(tabs[activeTab])
            showsPlaces = keepPlaces
        } else {
            saveTabs()
        }
    }

    /** Closes the tabs showing [serverId] or an archive on it, once the server was deleted. */
    fun closeServerTabs(serverId: String) = closeTabs { serverIdOf(it) == serverId }

    /** Closes the tabs whose path [isGone]; when none is left, one tab shows the home folder. */
    private fun closeTabs(isGone: (String) -> Boolean) {
        val activity = activity ?: return
        if (tabs.none(isGone)) return
        val activeGone = isGone(tabs[activeTab])
        val removedBefore = tabs.take(activeTab).count(isGone)
        tabs.removeAll(isGone)
        if (tabs.isEmpty()) tabs.add(activity.config.homeFolder)
        activeTab = (activeTab - removedBefore).coerceIn(0, tabs.lastIndex)
        if (activeGone) open(tabs[activeTab]) else saveTabs()
    }

    /** Shows [path] in a tab: switches to a tab already showing that server, or opens a new tab for it. */
    fun openInTab(path: String) {
        refreshServerNames()
        val existing = if (path.isRemotePath()) tabs.indexOfFirst { serverIdOf(it) == path.getRemoteServerId() } else -1
        when (existing) {
            -1 -> addTab(path)
            activeTab -> open(currentPath)
            else -> switchTab(existing)
        }
    }

    private fun saveTabs() {
        if (isPicking) return
        val config = activity?.config ?: return
        // a tab inside a vault is kept as the vault's folder: no decrypted names in the settings, and it asks again
        config.fileTabs = tabs.map { if (it.isVaultPath()) it.getVaultLocation() else it }
        config.activeFileTab = activeTab
    }

    /** Closing the app with "back": the next start, also from a shortcut or another app, begins in the home folder. */
    fun forgetTabs() {
        if (isPicking) return
        val config = activity?.config ?: return
        config.fileTabs = emptyList()
        config.activeFileTab = 0
    }

    /** Takes the tabs from the settings again, e.g. after a restored backup removed servers some of them showed. */
    fun reloadTabs() {
        val activity = activity ?: return
        if (isPicking) return
        tabs.clear()
        attach(activity, null)
        openActiveTab(activity.config.homeFolder)
    }

    fun refreshServerNames() {
        val activity = activity ?: return
        serverNames = RemoteServersStore.getServers(activity).associate { it.id to it.getDisplayName() }
    }

    fun locationRoot(path: String): String {
        val activity = activity ?: return "/"
        return if (path.isVirtualPath()) buildVirtualPathLike(path, "/") else path.volumeRoot(activity)
    }

    fun locationLabel(path: String): String {
        val activity = activity ?: return ""
        if (path.isRemotePath()) {
            return serverNames[path.getRemoteServerId()] ?: activity.getString(R.string.unknown_server)
        }
        if (path.isArchivePath()) {
            return path.getArchiveLocation().substringAfterLast('/')
        }
        if (path.isVaultPath()) {
            return path.getVaultLocation().substringAfterLast('/')
        }
        val basePath = path.volumeRoot(activity)
        return if (basePath == "/") activity.getString(R.string.root) else activity.volumeName(basePath)
    }

    fun locationKind(path: String): LocationKind {
        val activity = activity ?: return LocationKind.PHONE
        val removableStorage = activity.removableStorage
        return when {
            path.isRemotePath() -> LocationKind.SERVER
            path.isArchivePath() -> LocationKind.ARCHIVE
            path.isVaultPath() -> LocationKind.VAULT
            removableStorage.isNotEmpty() && path.volumeRoot(activity) == removableStorage -> LocationKind.SD_CARD
            path.volumeRoot(activity) == "/" -> LocationKind.ROOT
            else -> LocationKind.PHONE
        }
    }

    /**
     * The folders from the location's root down to [path], each with its label, for the path row. Inside an archive
     * the path to the archive comes first, so its folder is one tap away.
     */
    fun breadcrumbs(path: String): List<Pair<String, String>> {
        val root = locationRoot(path).trimEnd('/')
        val crumbs = ArrayList<Pair<String, String>>()
        if (path.isArchivePath()) crumbs.addAll(breadcrumbs(parentFolder(path.getArchiveLocation())))
        if (path.isVaultPath()) crumbs.addAll(breadcrumbs(parentFolder(path.getVaultLocation())))
        crumbs.add(locationLabel(path) to locationRoot(path))
        var current = root
        path.removePrefix(root).trim('/').split('/').filter { it.isNotEmpty() }.forEach { segment ->
            current = "$current/$segment"
            crumbs.add(segment to current)
        }
        return crumbs
    }

    /** A readable place, e.g. "Internal/Download" or "NAS:/photos". */
    fun displayPath(path: String): String = when {
        path.isRemotePath() -> "${locationLabel(path)}:${path.getRemoteServerPath()}"
        path.isArchivePath() -> displayPath(path.getArchiveLocation()) + path.getArchiveInnerPath().trimEnd('/')
        path.isVaultPath() -> displayPath(path.getVaultLocation()) + path.getVaultInnerPath().trimEnd('/')
        else -> breadcrumbs(path).joinToString("/") { it.first }
    }

    // endregion

    // region search

    /** Searches for [query] in the name, and for files of [searchFilter]; with that, the query may be empty. */
    fun search(query: String) {
        val activity = activity ?: return
        val generation = ++searchGeneration
        val filter = searchFilter
        val text = if (query.isBlank()) "" else query
        val matches = { item: FileEntry -> item.name.contains(text, ignoreCase = true) && filter?.matches(item.name, item.isDirectory) != false }
        when {
            text.isEmpty() && filter == null -> closeSearch()
            // server folders are only filtered, searching them recursively would take a request per folder
            currentPath.isVirtualPath() -> {
                searchResults = items.filter(matches)
                isSearching = false
            }

            filter == null && text.length < MIN_SEARCH_LENGTH -> {
                searchResults = items.filter(matches)
                isSearching = false
            }

            else -> {
                isSearching = true
                FileLoader(activity).search(text, filter, currentPath, activity.config.showHidden, { generation != searchGeneration }) {
                    if (generation == searchGeneration) {
                        searchResults = it
                        isSearching = false
                    }
                }
            }
        }
    }

    fun closeSearch() {
        searchGeneration++
        searchResults = null
        isSearching = false
    }

    // endregion

    // region selection

    /** While another app picks, only files can be selected, and only if it takes several. */
    fun canSelect(item: FileEntry) = !item.isSectionTitle && pickRequest.let { it == null || it.allowMultiple && !item.isDirectory }

    fun toggleSelection(item: FileEntry) {
        if (selection.remove(item.path) == null && canSelect(item)) {
            selection[item.path] = item
        }
    }

    fun select(items: List<FileEntry>) {
        items.filter(::canSelect).forEach { selection[it.path] = it }
    }

    /** Selects [before] and [range], nothing else; dragging over entries passes here with the range so far. */
    fun setSelection(before: Map<String, FileEntry>, range: List<FileEntry>) {
        val wanted = before + range.filter(::canSelect).associateBy { it.path }
        selection.keys.retainAll(wanted.keys)
        wanted.forEach { (path, item) -> if (path !in selection) selection[path] = item }
    }

    fun selectAll() = select(searchResults ?: items)

    fun clearSelection() = selection.clear()

    val selectedItems: List<FileEntry> get() = selection.values.toList()

    // endregion

    // region clipboard and transfers

    private fun onTransferEvent(event: TransferManager.Event) {
        // read before the progress is cleared with the end of the work
        val marks = deletionMarks
        transferRunning = TransferManager.isRunning
        transferKind = TransferManager.kind
        transferProgress = TransferManager.currentProgress
        transferState = TransferManager.state
        transferProblem = TransferManager.problem
        transferWaitingUntil = TransferManager.waitingUntil
        if (transferRunning && transferKind == TransferKind.DELETE && !deletionShown && deletionTimer == null) {
            deletionTimer = viewModelScope.launch {
                delay(QUIET_DELETION_MS)
                deletionShown = TransferManager.isRunning && TransferManager.kind == TransferKind.DELETE
            }
        }
        if (event !is TransferManager.Event.Finished) {
            return
        }

        // the entries gone stay away until the folder is read anew
        marks?.gone?.takeIf { it.isNotEmpty() }?.let { gone ->
            items = items.filterNot { it.path in gone }
            searchResults = searchResults?.filterNot { it.path in gone }
        }
        val wasShown = deletionShown
        deletionTimer?.cancel()
        deletionTimer = null
        deletionShown = false

        // in the background the service's notification tells the result instead; a quick deletion goes by without a word
        val activity = activity
        val message = activity?.describeTransferResult(event.itemCount, event.kind, event.error, event.bytes)
        val isQuiet = event.kind == TransferKind.DELETE && !wasShown && event.error == null
        if (activity != null && message != null && TransferManager.isUiVisible && !isQuiet) {
            when (event.error) {
                null, is TransferCancelledException -> activity.toast(message)
                else -> activity.showError(message)
            }
        }
        // a move also changes the folder it came from; a deletion reloads what it was started from itself
        if (event.kind != TransferKind.DELETE && (event.targetPath == currentPath || event.kind == TransferKind.MOVE)) {
            refresh()
        }
    }

    // endregion

    // region view

    /**
     * One zoom step, [step] > 0 makes entries bigger; false at the end of the view's range. Saved like the view: for
     * the folder when it has its own, otherwise in general.
     */
    fun changeZoom(step: Int): Boolean {
        val config = activity?.config ?: return false
        val zoom = (view.zoom + step).coerceIn(0, zoomLevels(view.type) - 1)
        if (zoom == view.zoom) return false
        view = view.copy(zoom = zoom)
        if (isAutoGallery) config.setZoom(ViewType.GALLERY, zoom) else config.saveFolderView(currentPath, view)
        return true
    }

    /**
     * Shows the folder as [newView] and saves it, for this folder only when [FolderView.isCustom]. In a folder shown as
     * gallery automatically, another view type only applies to this folder.
     */
    fun changeView(newView: FolderView) {
        val config = activity?.config ?: return
        val saved = if (isAutoGallery && newView.type != ViewType.GALLERY) newView.copy(isCustom = true) else newView
        if (isAutoGallery && !saved.isCustom) {
            // zoom and grouping change in general, the folder stays a gallery
            config.setZoom(ViewType.GALLERY, saved.zoom)
            config.groupBy = saved.groupBy.id
            view = saved
            return
        }
        // back to the general view: a photo folder may turn into a gallery again
        val reload = saved.type != view.type || isAutoGallery || (view.isCustom && !saved.isCustom)
        config.saveFolderView(currentPath, saved)
        view = saved
        if (reload) refresh()
    }

    /** A column of the details view was tapped: sorts by it, or turns the order round when it already sorts by it. */
    fun sortBy(kind: Int) {
        val config = activity?.config ?: return
        val sorting = config.getFolderSorting(currentPath)
        val newSorting = if (sorting and kind != 0) {
            sorting xor Sorting.DESCENDING
        } else {
            kind or (sorting and Sorting.NUMBERS_BY_VALUE)
        }
        if (config.hasCustomSorting(currentPath)) config.saveCustomSorting(currentPath, newSorting) else config.sorting = newSorting
        refresh()
    }

    private fun groupKey(group: String) = "$currentPath|${view.groupBy.id}|$group"

    fun isGroupCollapsed(group: String) = collapsedGroups[groupKey(group)] == true

    fun toggleGroup(group: String) {
        val key = groupKey(group)
        if (collapsedGroups[key] == true) collapsedGroups.remove(key) else collapsedGroups[key] = true
    }

    // endregion

    companion object {
        private const val MIN_SEARCH_LENGTH = 2

        /** How long a deletion runs unseen; most are over by then, so nothing flashes up. */
        private const val QUIET_DELETION_MS = 500L
    }
}

/**
 * Where a deletion has come to among the selected entries: those [gone] already, the one deleted right now and those
 * still [waiting] for it.
 */
class DeletionMarks(val gone: Set<String>, val current: String?, val waiting: Set<String>) {
    fun markOf(path: String) = when {
        path == current -> DeletionMark.NOW
        path in waiting -> DeletionMark.WAITING
        else -> null
    }
}

enum class DeletionMark { NOW, WAITING }

/** The archive at [location] needs a password to show its content; [isWrong] when the last one didn't fit. */
data class ArchiveUnlockRequest(val location: String, val isWrong: Boolean)

/** The vault at [location] needs its password; [target] (a folder in it) opens once it is unlocked. */
data class VaultUnlockRequest(val location: String, val target: String)
