package de.needanapp.needafile.files

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.ColorUtils
import de.needanapp.needafile.R
import de.needanapp.needafile.archive.ArchiveSessions
import de.needanapp.needafile.archive.CompressJob
import de.needanapp.needafile.archive.ExtractJob
import de.needanapp.needafile.archive.getArchiveLocation
import de.needanapp.needafile.archive.isArchiveFile
import de.needanapp.needafile.archive.isArchivePath
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.BusyWork
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.copyTargetStart
import de.needanapp.needafile.core.copyToClipboard
import de.needanapp.needafile.core.deleteFiles
import de.needanapp.needafile.core.getFilenameFromPath
import de.needanapp.needafile.core.getParentPath
import de.needanapp.needafile.core.inBackground
import de.needanapp.needafile.core.internalStorage
import de.needanapp.needafile.core.isAValidFilename
import de.needanapp.needafile.core.needsRoot
import de.needanapp.needafile.core.openFile
import de.needanapp.needafile.core.renameFile
import de.needanapp.needafile.core.setAs
import de.needanapp.needafile.core.sharePaths
import de.needanapp.needafile.core.showError
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.core.toggleItemVisibility
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.home.HomeActivity
import de.needanapp.needafile.remote.RemoteServersStore
import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.remote.getRemoteServerId
import de.needanapp.needafile.remote.isRemotePath
import de.needanapp.needafile.root.RootShell
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.system.LauncherIcon
import de.needanapp.needafile.transfer.DeleteJob
import de.needanapp.needafile.transfer.TransferClipboard
import de.needanapp.needafile.transfer.TransferJob
import de.needanapp.needafile.transfer.TransferKind
import de.needanapp.needafile.transfer.TransferManager
import de.needanapp.needafile.transfer.TransferManager.toBrowserFile
import de.needanapp.needafile.transfer.TransferProgress
import de.needanapp.needafile.vault.VaultSessions
import de.needanapp.needafile.vault.VaultShares
import de.needanapp.needafile.vault.isVaultPath
import de.needanapp.needafile.viewer.Viewers
import java.io.File
import java.io.IOException

/**
 * Everything that can be done with files and folders, phone and server alike. [onChanged] reloads the folder after a
 * change; [requestDialog] shows one of the browser's own dialogs.
 */
class FileOperations(
    private val activity: BaseActivity,
    private val model: FileBrowserModel,
    private val requestDialog: (FileDialog) -> Unit,
) {
    private val config = activity.config
    private val localCopy = LocalCopy(activity, requestDialog)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onChanged() {
        model.clearSelection()
        model.refresh()
    }

    /** What makes sense for [items]: server and archive items support fewer actions, some only fit a single file. */
    fun availableActions(items: List<FileEntry>): List<FileAction> {
        val isSingle = items.size == 1
        val isSingleFile = isSingle && !items.first().isDirectory
        if (items.first().path.isArchivePath()) {
            return listOfNotNull(
                FileAction.COPY,
                FileAction.SHARE.takeIf { isSingleFile },
                FileAction.EXTRACT_TO,
                FileAction.OPEN_WITH.takeIf { isSingleFile },
                FileAction.SELECT.takeIf { !model.isSelecting },
                FileAction.COPY_PATH.takeIf { isSingle },
            )
        }
        // servers and vaults
        if (items.first().path.isWritableVirtualPath()) {
            return listOfNotNull(
                FileAction.COPY,
                FileAction.CUT,
                FileAction.RENAME.takeIf { isSingle },
                FileAction.SHARE.takeIf { isSingleFile },
                FileAction.DELETE,
                FileAction.OPEN_WITH.takeIf { isSingleFile },
                FileAction.SELECT.takeIf { !model.isSelecting },
                FileAction.MOVE_TO,
                FileAction.COPY_TO,
            )
        }

        val hasHidden = items.any { it.name.startsWith(".") }
        val hasVisible = items.any { !it.name.startsWith(".") }
        return listOfNotNull(
            FileAction.COPY,
            FileAction.CUT,
            FileAction.RENAME,
            FileAction.SHARE,
            FileAction.DELETE,
            FileAction.OPEN_WITH.takeIf { isSingleFile },
            FileAction.OPEN_AS.takeIf { isSingleFile },
            FileAction.SELECT.takeIf { !model.isSelecting },
            FileAction.MOVE_TO,
            FileAction.COPY_TO,
            FileAction.COMPRESS,
            FileAction.DECOMPRESS.takeIf { items.any { it.path.isArchiveFile() } },
            FileAction.ARCHIVE_INFO.takeIf { isSingleFile && items.first().path.isArchiveFile() },
            FileAction.HIDE.takeIf { hasVisible },
            FileAction.UNHIDE.takeIf { hasHidden },
            FileAction.SET_AS.takeIf { isSingleFile },
            FileAction.CREATE_SHORTCUT.takeIf { isSingle },
            FileAction.COPY_PATH.takeIf { isSingle },
            FileAction.PROPERTIES,
        )
    }

    fun perform(action: FileAction, items: List<FileEntry>) {
        if (items.isEmpty()) return
        val first = items.first()
        val isRemote = first.path.isVirtualPath()
        when (action) {
            FileAction.COPY -> copyToClipboard(items)
            FileAction.CUT -> cutToClipboard(items)
            FileAction.RENAME -> rename(items)
            FileAction.PROPERTIES -> showProperties(items)
            FileAction.SHARE -> when {
                first.path.isVaultPath() -> activity.sharePaths(listOf(vaultUri(first)))
                isRemote -> downloadRemoteFile(first) { activity.sharePaths(arrayListOf(it.absolutePath)) }
                else -> share(items)
            }

            FileAction.HIDE -> toggleVisibility(items, hide = true)
            FileAction.UNHIDE -> toggleVisibility(items, hide = false)
            FileAction.CREATE_SHORTCUT -> createShortcut(first.path)
            FileAction.COPY_PATH -> copyPath(first.path)
            FileAction.SET_AS -> activity.setAs(first.path)
            FileAction.OPEN_WITH -> when {
                first.path.isVaultPath() -> activity.openFile(vaultUri(first), true)
                isRemote -> downloadRemoteFile(first) { activity.openFile(it.absolutePath, true) }
                else -> activity.openFile(first.path, true)
            }

            FileAction.OPEN_AS -> requestDialog(FileDialog.OpenAs(first.path))
            FileAction.COPY_TO -> copyMoveTo(items, isCopy = true)
            FileAction.MOVE_TO -> copyMoveTo(items, isCopy = false)
            FileAction.COMPRESS -> compress(items)
            FileAction.DECOMPRESS -> decompress(items)
            FileAction.EXTRACT_TO -> extractTo(items)
            FileAction.ARCHIVE_INFO -> showProperties(listOf(first))
            FileAction.DELETE -> if (config.skipDeleteConfirmation) delete(items) else requestDialog(FileDialog.Delete(items))
            FileAction.SELECT -> model.select(items.filterIsInstance<FileEntry>())
            FileAction.SELECT_ALL -> model.selectAll()
        }
    }

    // region opening

    /**
     * A tap on a file: open it, or download a server file (extract an archive file) into the cache first. Vault files
     * needafile has no viewer for go to another app right away, which reads them from the vault.
     */
    fun openFile(item: FileEntry, onLocalFile: (String) -> Unit) {
        when {
            item.path.isVaultPath() && Viewers.kindOf(item.name) == null -> activity.openFile(vaultUri(item), false)
            item.path.isVirtualPath() -> downloadRemoteFile(item) { onLocalFile(it.absolutePath) }
            else -> onLocalFile(item.path)
        }
    }

    /** The uri another app reads the vault file [item] through, decrypted as it reads and only while the vault is open. */
    private fun vaultUri(item: FileEntry) = VaultShares.uriFor(activity, item.path, item.toBrowserFile()).toString()

    /**
     * Server and archive files are copied into the cache first, then handled like any local file. Vault files are
     * decrypted into the app's own cache instead, for needafile's viewers only, and deleted when the vault locks. A
     * dialog shows how far it got once it takes a moment, and can stop it.
     */
    private fun downloadRemoteFile(item: FileEntry, callback: (File) -> Unit) = withArchivePassword(listOf(item)) {
        val title = when {
            item.path.isArchivePath() -> R.string.decompressing
            item.path.isVaultPath() -> R.string.vault_decrypting
            else -> R.string.downloading_file
        }
        val fetch = FetchProgress(item.name, title)
        // small files are there before a dialog would be worth it
        mainHandler.postDelayed({ if (!fetch.isFinished) requestDialog(FileDialog.Fetching(fetch)) }, FETCH_DIALOG_DELAY_MS)
        inBackground {
            val isCancelled = { fetch.isCancelled }
            val onProgress = { progress: TransferProgress -> activity.runOnUiThread { fetch.update(progress) } }
            try {
                val localFile = if (item.path.isVaultPath()) {
                    VaultSessions.decryptForViewing(activity, item.path, item.toBrowserFile(), isCancelled, onProgress)
                } else {
                    val cacheFolder = File(activity.externalCacheDir ?: activity.cacheDir, REMOTE_FILES_CACHE_FOLDER)
                    cacheFolder.mkdirs()
                    TransferManager.getRemoteFileSystem(activity, item.path).download(listOf(item.toBrowserFile()), cacheFolder, isCancelled, onProgress).first()
                }
                activity.runOnUiThread {
                    fetch.finish()
                    if (!fetch.isCancelled) callback(localFile)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    fetch.finish()
                    if (e !is TransferCancelledException && !fetch.isCancelled) activity.showError(activity.describeError(e))
                }
            }
        }
    }

    /**
     * Runs [action] once the archive holding [items] can be extracted: archives whose file names are readable may
     * still encrypt the content, their password is asked for (and checked) first. Other items run [action] right away.
     */
    private fun withArchivePassword(items: List<FileEntry>, action: () -> Unit) {
        val path = items.firstOrNull()?.path?.takeIf { it.isArchivePath() } ?: return action()
        val location = path.getArchiveLocation()
        inBackground {
            try {
                val needsPassword = ArchiveSessions.get(activity, location).needsPassword
                activity.runOnUiThread { if (needsPassword) askArchivePassword(location, isWrong = false, action) else action() }
            } catch (e: Exception) {
                activity.runOnUiThread { activity.showError(activity.describeError(e)) }
            }
        }
    }

    private fun askArchivePassword(location: String, isWrong: Boolean, action: () -> Unit) {
        requestDialog(FileDialog.ArchivePassword(location, isWrong, onPassword = { password ->
            inBackground {
                try {
                    val scratch = File(activity.cacheDir, ARCHIVE_PROBE_FOLDER).apply { mkdirs() }
                    val isRight = ArchiveSessions.get(activity, location).isPasswordRight(password, scratch)
                    if (isRight) ArchiveSessions.setPassword(location, password)
                    activity.runOnUiThread { if (isRight) action() else askArchivePassword(location, isWrong = true, action) }
                } catch (e: Exception) {
                    activity.runOnUiThread { activity.showError(activity.describeError(e)) }
                }
            }
        }, onCancel = {}))
    }

    // endregion

    // region creating and renaming

    /** Checks a new name for [parentPath]: null when fine, otherwise the reason. */
    fun validateNewName(parentPath: String, name: String, currentName: String? = null): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> activity.getString(R.string.empty_name)
            !trimmed.isAValidFilename() -> activity.getString(R.string.invalid_name)
            trimmed == currentName -> null
            parentPath.isWritableVirtualPath() -> if (model.items.any { it.name == trimmed }) activity.getString(R.string.name_taken) else null
            File("${parentPath.trimEnd('/')}/$trimmed").exists() -> activity.getString(R.string.name_taken)
            else -> null
        }
    }

    fun create(parentPath: String, name: String, isFolder: Boolean) {
        if (parentPath.isWritableVirtualPath()) {
            if (isFolder) {
                runRemoteChange { TransferManager.getRemoteFileSystem(activity, parentPath).createFolder(parentPath.getVirtualInnerPath(), name) }
            }
            return
        }

        val path = "${parentPath.trimEnd('/')}/$name"
        val done: (Boolean) -> Unit = { success ->
            activity.runOnUiThread {
                if (success) {
                    model.refresh()
                } else {
                    val error = if (isFolder) R.string.could_not_create_folder else R.string.could_not_create_file
                    activity.showError(String.format(activity.getString(error), path))
                }
            }
        }
        try {
            if (isFolder) createFolder(path, done) else createFile(path, done)
        } catch (e: IOException) {
            activity.showError(e)
        }
    }

    private fun createFolder(path: String, done: (Boolean) -> Unit) =
        if (activity.needsRoot(path)) inBackground { done(RootShell.createFolder(path)) } else done(File(path).mkdirs())

    private fun createFile(path: String, done: (Boolean) -> Unit) =
        if (activity.needsRoot(path)) inBackground { done(RootShell.createFile(path)) } else done(File(path).createNewFile())

    private fun rename(items: List<FileEntry>) {
        val item = items.singleOrNull()
        when {
            item == null -> requestDialog(FileDialog.RenameMany(items))
            // an opened card takes the new name where the old one is
            model.hasOpenCard && item.path in model.selection -> {
                model.cardExpanded = false
                model.renaming = item.path
            }
            else -> requestDialog(FileDialog.Rename(item))
        }
    }

    fun renameTo(item: FileEntry, newName: String) {
        if (item.path.isWritableVirtualPath()) {
            runRemoteChange { TransferManager.getRemoteFileSystem(activity, item.path).rename(item.path.getVirtualInnerPath(), newName) }
            return
        }

        val newPath = "${item.path.getParentPath()}/$newName"
        activity.renameFile(item.path, newPath) { success ->
            if (success) {
                onChanged()
            } else {
                activity.toast(R.string.unknown_error_occurred)
            }
        }
    }

    /** Renames several items one after another; the folder reloads once all are done. */
    fun renameAll(renames: List<Pair<FileEntry, String>>) {
        var pending = renames.size
        var failed = 0
        renames.forEach { (item, name) ->
            val newPath = "${item.path.getParentPath()}/$name"
            activity.renameFile(item.path, newPath) { success ->
                if (!success) failed++
                pending--
                if (pending == 0) {
                    if (failed > 0) activity.toast(activity.resources.getQuantityString(R.plurals.rename_failed, failed, failed))
                    onChanged()
                }
            }
        }
    }

    /** Runs a server request in the background and reloads the folder afterwards, after [onDone]. */
    private fun runRemoteChange(onDone: () -> Unit = {}, change: () -> Unit) {
        inBackground {
            try {
                change()
            } catch (e: Exception) {
                activity.runOnUiThread { activity.showError(activity.describeError(e)) }
            }
            activity.runOnUiThread {
                onDone()
                onChanged()
            }
        }
    }

    // endregion

    // region clipboard

    /** Puts [items] into the app clipboard; the browser then offers to paste them in any tab or folder. */
    private fun copyToClipboard(items: List<FileEntry>) {
        TransferClipboard.copy(items)
        activity.toast(activity.resources.getQuantityString(R.plurals.copied_to_clipboard, items.size, items.size))
        model.clearSelection()
    }

    /** Like [copyToClipboard], only pasting moves the items. */
    private fun cutToClipboard(items: List<FileEntry>) {
        TransferClipboard.cut(items)
        activity.toast(activity.resources.getQuantityString(R.plurals.cut_to_clipboard, items.size, items.size))
        model.clearSelection()
    }

    fun paste() {
        val items = TransferClipboard.items
        if (items.isEmpty()) return
        val isMove = TransferClipboard.isCut

        val targetPath = model.currentPath
        if (targetPath.isArchivePath()) {
            activity.toast(R.string.archive_read_only)
            return
        }

        val isIntoItself = items.any { targetPath == it.path || targetPath.startsWith("${it.path}/") }
        if (isIntoItself || items.all { it.parentPath == targetPath }) {
            activity.toast(R.string.source_and_destination_same)
            return
        }

        if (!items.first().path.isVirtualPath() && !targetPath.isVirtualPath()) {
            pasteLocally(items, targetPath, isMove)
            return
        }

        val existingNames = model.items.mapTo(HashSet()) { it.name }
        transferWithConflicts(items, targetPath, existingNames, isMove, fromClipboard = true)
    }

    /**
     * Asks whether to overwrite items whose names [existingNames] already holds, then copies (or moves, [isMove]) in
     * the background; [onStarted] runs once it is on its way.
     */
    private fun transferWithConflicts(
        items: List<FileEntry>,
        targetPath: String,
        existingNames: Set<String>,
        isMove: Boolean = false,
        fromClipboard: Boolean = false,
        onStarted: () -> Unit = {},
    ) = withArchivePassword(items) {
        val conflicts = items.filter { it.name in existingNames }
        if (conflicts.isEmpty()) {
            transfer(items, targetPath, isMove, fromClipboard, onStarted)
        } else {
            requestDialog(FileDialog.TransferConflict(conflicts.map { it.name }) { overwrite ->
                transfer(if (overwrite) items else items - conflicts.toSet(), targetPath, isMove, fromClipboard, onStarted)
            })
        }
    }

    /** Like [transferWithConflicts], reading the names in [targetPath] first, which on a server takes a request. */
    private fun transferInto(items: List<FileEntry>, targetPath: String, isMove: Boolean, onStarted: () -> Unit = {}) {
        inBackground {
            try {
                val existingNames = if (targetPath.isVirtualPath()) {
                    TransferManager.getRemoteFileSystem(activity, targetPath).listFiles(targetPath.getVirtualInnerPath()).mapTo(HashSet()) { it.name }
                } else {
                    File(targetPath).list()?.toHashSet().orEmpty()
                }
                activity.runOnUiThread { transferWithConflicts(items, targetPath, existingNames, isMove, onStarted = onStarted) }
            } catch (e: Exception) {
                activity.runOnUiThread { activity.showError(activity.describeError(e)) }
            }
        }
    }

    /** Extracts items of an archive into a folder on the phone or a server, picked first; next to the archive by default. */
    private fun extractTo(items: List<FileEntry>) {
        val location = items.first().path.getArchiveLocation()
        val nextToArchive = File(location).parent?.takeIf { !location.isVirtualPath() && File(it).canWrite() }
        val start = nextToArchive ?: lastCopyTarget() ?: activity.internalStorage
        requestDialog(FileDialog.PickFolder(FolderPick(R.string.extract_to, R.string.extract_here, start, allowServers = true) { destination ->
            config.lastCopyPath = destination
            transferInto(items, destination, isMove = false)
        }))
    }

    /** Phone to phone; outside the shared storage it takes root. */
    private fun pasteLocally(items: List<FileEntry>, targetPath: String, isMove: Boolean) {
        if (activity.needsRoot(targetPath) || activity.needsRoot(items.first().path)) {
            TransferClipboard.clear()
            copyMoveRootItems(ArrayList(items), targetPath, isCopy = !isMove)
        } else {
            localCopy.copyMove(items, targetPath, isCopy = !isMove, fromClipboard = true) {}
        }
    }

    /** Copies where at least one side is a server or archive, in the background with progress in the paste bar. */
    private fun transfer(items: List<FileEntry>, targetPath: String, isMove: Boolean, fromClipboard: Boolean, onStarted: () -> Unit) {
        if (items.isEmpty()) {
            // everything was left out, the paste is done
            if (fromClipboard) TransferClipboard.clear()
            return
        }
        // without the permission the copy still runs, just without its progress notification
        activity.withNotificationPermission {
            if (TransferManager.start(activity.applicationContext, items, targetPath, isMove, fromClipboard)) {
                onStarted()
            } else {
                activity.toast(R.string.transfer_already_running)
            }
        }
    }

    // endregion

    // region other actions

    private fun showProperties(items: List<FileEntry>) = requestDialog(FileDialog.Info(items))

    /** Shares the selected files and everything inside selected folders; hidden files inside only while they show. */
    private fun share(items: List<FileEntry>) {
        val showHidden = config.showHidden
        fun isShown(file: File, item: FileEntry) = file.path == item.path || showHidden || !file.name.startsWith('.')
        val paths = items.flatMap { item ->
            File(item.path).walkTopDown()
                .onEnter { isShown(it, item) }
                .filter { !it.isDirectory && isShown(it, item) }
                .map { it.path }
                .toList()
        }
        activity.sharePaths(paths)
    }

    private fun toggleVisibility(items: List<FileEntry>, hide: Boolean) {
        var pending = items.size
        items.forEach { item ->
            activity.toggleItemVisibility(item.path, hide) {
                if (--pending == 0) onChanged()
            }
        }
    }

    private fun copyPath(path: String) {
        activity.copyToClipboard(if (path.isVirtualPath()) model.displayPath(path) else path)
        model.clearSelection()
    }

    /** Pins a home screen shortcut that shows the folder, or opens the file with its app. */
    private fun createShortcut(path: String) {
        val manager = activity.getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported) return

        inBackground {
            val opening = Intent(Intent.ACTION_VIEW, Uri.fromFile(File(path)), activity, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
            val shortcut = ShortcutInfo.Builder(activity, path)
                .setShortLabel(path.getFilenameFromPath())
                .setIcon(Icon.createWithBitmap(shortcutPicture(path)))
                .setIntent(opening)
                .build()
            activity.runOnUiThread { manager.requestPinShortcut(shortcut, null) }
        }
    }

    /**
     * What the shortcut shows: the preview of a picture or video, otherwise a round badge in the color of the app icon
     * with a folder or file symbol, dark on light colors.
     */
    private fun shortcutPicture(path: String): Bitmap {
        val size = activity.resources.getDimensionPixelSize(R.dimen.shortcut_size)
        val inset = activity.resources.getDimensionPixelSize(R.dimen.shortcut_symbol_inset)
        val inner = Rect(inset, inset, size - inset, size - inset)
        val picture = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(picture)

        val isFolder = File(path).isDirectory
        val preview = if (isFolder) null else loadThumbnailBitmap(activity, path, size)
        if (preview != null) {
            canvas.drawBitmap(preview.centerSquare(), null, inner, Paint(Paint.FILTER_BITMAP_FLAG))
            return picture
        }

        val badgeColor = LauncherIcon.resolve(activity).argb
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badgeColor })
        val symbol = activity.getDrawable(if (isFolder) R.drawable.ic_shortcut_folder else R.drawable.ic_shortcut_file)?.mutate()
        symbol?.setTint(if (badgeColor.isLight()) SHORTCUT_DARK_SYMBOL else Color.WHITE)
        symbol?.bounds = inner
        symbol?.draw(canvas)
        return picture
    }

    /** Copies or moves [items] into a folder picked first, on the phone or on a server. */
    private fun copyMoveTo(items: List<FileEntry>, isCopy: Boolean) {
        val files = ArrayList(items)
        val first = files.first()
        val source = first.parentPath
        // root reaches what the app itself can't read, servers are out of its reach then
        val isOnRoot = !first.path.isVirtualPath() && activity.needsRoot(first.path)
        val start = lastCopyTarget(allowServers = !isOnRoot) ?: source
        val title = if (isCopy) R.string.copy_to else R.string.move_to
        val confirmLabel = if (isCopy) R.string.copy_here else R.string.move_here
        requestDialog(FileDialog.PickFolder(FolderPick(title, confirmLabel, start, allowServers = !isOnRoot) { destination ->
            config.lastCopyPath = destination
            when {
                destination.isVirtualPath() || first.path.isVirtualPath() -> copyMoveWithServer(files, destination, isCopy)
                activity.needsRoot(destination) || isOnRoot -> copyMoveRootItems(files, destination, isCopy)
                else -> localCopy.copyMove(files, destination, isCopy) { model.clearSelection() }
            }
        }))
    }

    /** The folder copied into last time, if it is still there; a server counts while it is saved. */
    private fun lastCopyTarget(allowServers: Boolean = true): String? {
        val last = config.lastCopyPath
        return when {
            last.isRemotePath() -> last.takeIf { allowServers && RemoteServersStore.getServer(activity, it.getRemoteServerId()) != null }
            else -> activity.copyTargetStart(config.showHidden, "").ifEmpty { null }
        }
    }

    /** Copies or moves where the items or [destination] lie on a server, through the background copy. */
    private fun copyMoveWithServer(items: List<FileEntry>, destination: String, isCopy: Boolean) {
        val isIntoItself = items.any { destination == it.path || destination.startsWith("${it.path}/") }
        if (isIntoItself || items.all { it.parentPath == destination }) {
            activity.toast(R.string.source_and_destination_same)
            return
        }
        transferInto(items, destination, isMove = !isCopy) { model.clearSelection() }
    }

    /** Copies or moves with root rights, where the app itself can't read or write. */
    private fun copyMoveRootItems(items: List<FileEntry>, destination: String, isCopy: Boolean) {
        activity.toast(R.string.copying)
        inBackground {
            val done = RootShell.copyOrMove(items.map { it.path }, destination, move = !isCopy)
            activity.runOnUiThread {
                activity.toast(
                    when (done) {
                        items.size -> R.string.copying_success
                        0 -> R.string.copy_failed
                        else -> R.string.copying_success_partial
                    },
                )
                onChanged()
            }
        }
    }

    // endregion

    // region archives

    private fun compress(items: List<FileEntry>) {
        requestDialog(FileDialog.Compress(items) { request ->
            startArchiveJob(TransferKind.COMPRESS, items.map { it.path }, request.destination.getParentPath(), CompressJob(items.map { it.path }, request))
        })
    }

    private fun decompress(items: List<FileEntry>) {
        // zip goes through 7-Zip as well, which also asks for the password of encrypted zips
        val paths = items.map { it.path }.filter { it.isArchiveFile() }
        if (paths.isNotEmpty()) extract(paths.map { it to null })
    }

    /** Extracts [archives] (path and password, if known); those that need a password are asked for it afterwards. */
    private fun extract(archives: List<Pair<String, String?>>) {
        val job = ExtractJob(activity.cacheDir, archives)
        startArchiveJob(TransferKind.DECOMPRESS, archives.map { it.first }, archives.first().first.getParentPath(), job) {
            askPasswords(job.locked, emptyList())
        }
    }

    /** Asks for the passwords of [locked] one after another, then extracts the archives that got one. */
    private fun askPasswords(locked: List<ExtractJob.Locked>, unlocked: List<Pair<String, String?>>) {
        if (activity.isDestroyed) return
        val next = locked.firstOrNull()
        if (next == null) {
            if (unlocked.isNotEmpty()) extract(unlocked)
            return
        }

        val rest = locked.drop(1)
        requestDialog(FileDialog.ArchivePassword(next.path, next.isWrong, onPassword = { password ->
            askPasswords(rest, unlocked + (next.path to password))
        }, onCancel = { askPasswords(rest, unlocked) }))
    }

    /**
     * Packs or unpacks in the background, with progress and "Cancel" in the paste bar and the notification. The folder
     * [targetPath] reloads once it's done.
     */
    private fun startArchiveJob(kind: TransferKind, sourcePaths: List<String>, targetPath: String, job: TransferJob, onFinished: ((Throwable?) -> Unit)? = null) {
        // without the permission it still runs, just without its progress notification
        activity.withNotificationPermission {
            if (TransferManager.startJob(activity.applicationContext, kind, sourcePaths, targetPath, job, onFinished)) {
                model.clearSelection()
            } else {
                activity.toast(R.string.transfer_already_running)
            }
        }
    }

    // endregion

    // region deleting

    /**
     * Deletes [items] in the background; one that takes a moment shows its progress above the pill and in the
     * notification and can be cancelled, see [DeleteJob]. Root folders go in one command, without progress.
     */
    fun delete(items: List<FileEntry>) {
        val firstPath = items.first().path
        if (!firstPath.isWritableVirtualPath() && activity.needsRoot(firstPath)) {
            if (!config.isRootAvailable) {
                activity.toast(R.string.rooted_device_only)
                return
            }
            inBackground {
                val deleted = RootShell.delete(items.map { it.path })
                activity.runOnUiThread {
                    if (deleted < items.size) activity.toast(R.string.unknown_error_occurred)
                    onChanged()
                }
            }
            return
        }

        val job = DeleteJob(activity.applicationContext, items)
        if (TransferManager.startJob(activity.applicationContext, TransferKind.DELETE, items.map { it.path }, model.currentPath, job) { onChanged() }) {
            model.clearSelection()
        } else {
            deleteBesideTransfer(items)
        }
    }

    /** While a copy runs, deleting goes on beside it without showing its progress, keeping the display on meanwhile. */
    private fun deleteBesideTransfer(items: List<FileEntry>) {
        val work = BusyWork.begin(items.map { it.path })
        val firstPath = items.first().path
        if (firstPath.isWritableVirtualPath()) {
            runRemoteChange(onDone = { BusyWork.end(work) }) {
                TransferManager.getRemoteFileSystem(activity, firstPath).delete(items.map { it.path.getVirtualInnerPath() })
            }
        } else {
            activity.deleteFiles(items) { success ->
                BusyWork.end(work)
                if (!success) activity.toast(R.string.unknown_error_occurred)
                onChanged()
            }
        }
    }

    // endregion

    companion object {
        private const val REMOTE_FILES_CACHE_FOLDER = "remote_files"
        private const val ARCHIVE_PROBE_FOLDER = "archive_probe"

        /** How long fetching a file to open may take before the progress dialog shows. */
        private const val FETCH_DIALOG_DELAY_MS = 300L
    }
}

private fun Int.isLight() = ColorUtils.calculateLuminance(this) > LIGHT_LUMINANCE

private const val LIGHT_LUMINANCE = 0.6
private const val SHORTCUT_DARK_SYMBOL = 0xFF1B1B1F.toInt()

/** The middle square of a preview, so the shortcut shows it like the list does. */
private fun Bitmap.centerSquare(): Bitmap {
    val side = minOf(width, height)
    return if (width == height) this else Bitmap.createBitmap(this, (width - side) / 2, (height - side) / 2, side, side)
}
