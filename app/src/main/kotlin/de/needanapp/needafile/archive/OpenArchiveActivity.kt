package de.needanapp.needafile.archive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.CardAction
import de.needanapp.design.components.CardMenu
import de.needanapp.design.components.CardTileGroups
import de.needanapp.design.components.Detail
import de.needanapp.design.components.EmptyState
import de.needanapp.design.components.FastScrollbar
import de.needanapp.design.components.FloatingItemCard
import de.needanapp.design.components.FloatingItemCardDefaults
import de.needanapp.design.components.SubScreen
import de.needanapp.design.components.detailLines
import de.needanapp.design.components.rememberPillScrollBehavior
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.BusyWork
import de.needanapp.needafile.core.FileEntry
import de.needanapp.needafile.core.Sorting
import de.needanapp.needafile.core.displayNameOf
import de.needanapp.needafile.core.displayPath
import de.needanapp.needafile.core.filePathOf
import de.needanapp.needafile.core.internalStorage
import de.needanapp.needafile.core.showError
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.files.FetchDialog
import de.needanapp.needafile.files.FetchProgress
import de.needanapp.needafile.files.FileRow
import de.needanapp.needafile.files.FolderPick
import de.needanapp.needafile.files.FolderPickerSheet
import de.needanapp.needafile.files.scrollLabel
import de.needanapp.needafile.home.HomeActivity
import de.needanapp.needafile.permissions.withStorageAccess
import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.settings.config
import de.needanapp.needafile.theme.setThemedContent
import de.needanapp.needafile.transfer.TransferManager.toBrowserFile
import de.needanapp.needafile.viewer.Viewers
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The archive to work on: its own path, or a copy in the cache when another app only hands over its content. */
private data class ReceivedArchive(val path: String, val name: String, val isCopy: Boolean)

/** What the archive shows of its content: its [Entries] in a folder, nothing while the names are [Hidden], or why it [Failed]. */
private sealed interface ArchiveContent {
    class Entries(val items: List<FileEntry>) : ArchiveContent
    class Hidden(val isWrong: Boolean) : ArchiveContent
    class Failed(val message: String) : ArchiveContent
}

/** What a password is asked for: to show the hidden names, to extract everything or to open one file. */
private sealed interface PasswordUse {
    data object List : PasswordUse
    data object Extract : PasswordUse
    class Open(val item: FileEntry) : PasswordUse
}

private class PasswordRequest(val use: PasswordUse, val isWrong: Boolean)

/** An extraction going on: how far it got, and whether it was stopped. */
private class Extraction {
    var percent by mutableIntStateOf(0)

    @Volatile
    var isCancelled = false
}

/**
 * An archive opened from another app (e.g. a download or an attachment): shows its content, folders open and files
 * open in the viewers; the card floating below extracts it into a folder of choice, asking for the password when
 * needed, and unfolds format and encryption.
 */
class OpenArchiveActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            toast(R.string.unknown_error_occurred)
            finish()
            return
        }
        setThemedContent { OpenArchiveScreen(this, uri, intent.type, onBack = ::finish) }
    }
}

@Composable
private fun OpenArchiveScreen(activity: BaseActivity, uri: Uri, mimeType: String?, onBack: () -> Unit) {
    var archive by remember { mutableStateOf<ReceivedArchive?>(null) }
    var info by remember { mutableStateOf<ArchiveInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var innerPath by rememberSaveable { mutableStateOf("/") }
    var content by remember { mutableStateOf<ArchiveContent?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var extraction by remember { mutableStateOf<Extraction?>(null) }
    var fetching by remember { mutableStateOf<FetchProgress?>(null) }
    var askPassword by remember { mutableStateOf<PasswordRequest?>(null) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val cardScroll = rememberPillScrollBehavior()
    val scope = rememberCoroutineScope()
    // the whole archive, a split one by its first part
    val location = remember(archive) { archive?.let { archiveRootPath(it.path).getArchiveLocation() } }

    LaunchedEffect(uri) {
        try {
            val opened = withContext(Dispatchers.IO) { open(activity, uri, mimeType) }
            archive = opened
            if (folder == null) folder = defaultFolder(activity, opened)
            info = withContext(Dispatchers.IO) { ArchiveInspector.inspect(opened.path) }
        } catch (e: Exception) {
            error = activity.describeError(e)
        }
    }
    LaunchedEffect(location, innerPath, reload) {
        val opened = location ?: return@LaunchedEffect
        val listed = withContext(Dispatchers.IO) {
            try {
                val entries = ArchiveSessions.get(activity, opened).listFiles(innerPath)
                ArchiveContent.Entries(entries.map { FileEntry(buildArchivePath(opened, it.path), it.name, it.isDirectory, 0, it.size, it.lastModified) })
            } catch (e: ArchivePasswordException) {
                ArchiveContent.Hidden(e.isWrong)
            } catch (e: Exception) {
                ArchiveContent.Failed(activity.describeError(e))
            }
        }
        content = listed
        // a wrong password is asked for again right away
        if (listed is ArchiveContent.Hidden && listed.isWrong) askPassword = PasswordRequest(PasswordUse.List, isWrong = true)
    }
    // the archive is only needed while the screen is open, copies in the cache not at all afterwards
    DisposableEffect(Unit) {
        onDispose {
            if (activity.isFinishing) {
                archive?.let { opened ->
                    ArchiveSessions.close(archiveRootPath(opened.path).getArchiveLocation())
                    if (opened.isCopy) File(opened.path).delete()
                }
            }
        }
    }

    fun extract(password: String?) {
        val opened = archive ?: return
        val target = folder ?: return
        activity.withStorageAccess {
            val running = Extraction()
            extraction = running
            // the display stays on until it's done
            val work = BusyWork.begin(listOf(opened.path, target))
            scope.launch {
                val result = try {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ArchiveExtractor(activity.cacheDir).extract(opened.path, password, File(target), { running.isCancelled }) { progress ->
                                val percent = if (progress.bytesTotal > 0) (progress.bytesDone * 100 / progress.bytesTotal).toInt() else 0
                                activity.runOnUiThread { running.percent = percent }
                            }
                        }
                    }
                } finally {
                    // also when the screen closes meanwhile
                    BusyWork.end(work)
                }
                extraction = null
                result.onSuccess { extracted ->
                    activity.toast(R.string.decompression_successful)
                    showFolder(activity, if (extracted.isDirectory) extracted.path else target)
                    activity.finish()
                }.onFailure { e ->
                    when {
                        e is CancelledExtractionException || running.isCancelled -> Unit
                        e is ArchivePasswordException -> askPassword = PasswordRequest(PasswordUse.Extract, e.isWrong)
                        else -> activity.showError(activity.getString(R.string.archive_extract_failed, activity.describeError(e)))
                    }
                }
            }
        }
    }

    /** Extracts [item] into the cache, with a dialog once it takes a moment, and opens it. */
    fun fetch(item: FileEntry) {
        val progress = FetchProgress(item.name, R.string.decompressing)
        scope.launch {
            val dialog = launch {
                delay(FETCH_DIALOG_DELAY_MS)
                fetching = progress
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cacheFolder = File(activity.externalCacheDir ?: activity.cacheDir, OPENED_FILES_FOLDER).apply { mkdirs() }
                    ArchiveSessions.get(activity, item.path.getArchiveLocation())
                        .download(listOf(item.toBrowserFile()), cacheFolder, { progress.isCancelled }) { done -> activity.runOnUiThread { progress.update(done) } }
                        .first()
                }
            }
            dialog.cancel()
            progress.finish()
            if (progress.isCancelled) return@launch
            result.onSuccess { Viewers.openOrHandOver(activity, it.path, single = true) }
                .onFailure { e -> if (e !is TransferCancelledException) activity.showError(activity.describeError(e)) }
        }
    }

    /** Opens [item], once the content can be read: archives with readable names may still encrypt it. */
    fun openFile(item: FileEntry) {
        scope.launch {
            val needsPassword = withContext(Dispatchers.IO) {
                runCatching { ArchiveSessions.get(activity, item.path.getArchiveLocation()).needsPassword }.getOrDefault(false)
            }
            if (needsPassword) askPassword = PasswordRequest(PasswordUse.Open(item), isWrong = false) else fetch(item)
        }
    }

    fun usePassword(use: PasswordUse, password: String) {
        val opened = location ?: return
        when (use) {
            PasswordUse.Extract -> extract(password)
            PasswordUse.List -> {
                ArchiveSessions.setPassword(opened, password)
                reload++
            }
            is PasswordUse.Open -> scope.launch {
                val isRight = withContext(Dispatchers.IO) {
                    runCatching {
                        val scratch = File(activity.cacheDir, PASSWORD_PROBE_FOLDER).apply { mkdirs() }
                        ArchiveSessions.get(activity, opened).isPasswordRight(password, scratch)
                    }.getOrDefault(false)
                }
                if (isRight) {
                    ArchiveSessions.setPassword(opened, password)
                    fetch(use.item)
                } else {
                    askPassword = PasswordRequest(use, isWrong = true)
                }
            }
        }
    }

    // back goes up a folder first, while no info is unfolded
    BackHandler(enabled = innerPath != "/" && !showDetails) { innerPath = innerPath.substringBeforeLast('/').ifEmpty { "/" } }

    SubScreen(
        title = archive?.name ?: stringResource(R.string.decompress),
        onBack = onBack,
        subtitle = innerPath.takeIf { it != "/" }?.removePrefix("/"),
        bottomBar = {
            val opened = archive
            if (opened != null && error == null) {
                ArchiveCard(
                    archive = opened,
                    info = info,
                    folder = folder,
                    extraction = extraction,
                    showDetails = showDetails,
                    onShowDetails = { showDetails = it },
                    onExtract = { extract(location?.let(ArchiveSessions::passwordFor)) },
                    onPickFolder = { picking = true },
                    visible = cardScroll.isVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
    ) { padding ->
        val shown = content
        val failed = error
        when {
            failed != null -> CenteredState(padding) {
                EmptyState(Icons.Outlined.ErrorOutline, stringResource(R.string.archive_open_failed), message = failed)
            }

            shown == null -> CenteredState(padding) { CircularProgressIndicator() }

            shown is ArchiveContent.Hidden -> CenteredState(padding) {
                EmptyState(
                    Icons.Outlined.Lock,
                    stringResource(R.string.archive_names_hidden),
                    message = stringResource(R.string.archive_names_hidden_hint),
                    action = {
                        Button(onClick = { askPassword = PasswordRequest(PasswordUse.List, isWrong = false) }) {
                            Text(stringResource(R.string.archive_enter_password))
                        }
                    },
                )
            }

            shown is ArchiveContent.Failed -> CenteredState(padding) {
                EmptyState(Icons.Outlined.ErrorOutline, stringResource(R.string.archive_content_failed), message = shown.message)
            }

            shown is ArchiveContent.Entries && shown.items.isEmpty() -> CenteredState(padding) {
                EmptyState(Icons.Outlined.FolderOff, stringResource(R.string.folder_empty))
            }

            shown is ArchiveContent.Entries -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val isWide = maxWidth >= WIDE_LAYOUT
                val asCards = remember { activity.config.listAsCards }
                // each folder starts at its top
                key(shown) {
                    val listState = rememberLazyListState()
                    // room to read the end above the card
                    val listPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + FloatingItemCardDefaults.SpaceBelowContent)
                    val byPath = remember { shown.items.associateBy { it.path } }
                    LazyColumn(
                        state = listState,
                        contentPadding = listPadding,
                        modifier = Modifier.fillMaxSize().nestedScroll(cardScroll.nestedScrollConnection),
                    ) {
                        items(shown.items, key = { it.path }) { item ->
                            val onClick = { if (item.isDirectory) innerPath = item.path.getArchiveInnerPath() else openFile(item) }
                            FileRow(
                                item = item,
                                selected = false,
                                onClick = onClick,
                                onLongClick = onClick,
                                onIconClick = onClick,
                                asCard = asCards,
                                isDetails = true,
                                isWide = isWide,
                            )
                        }
                    }
                    FastScrollbar(listState, Modifier.matchParentSize(), listPadding) { key -> byPath[key]?.let { scrollLabel(it, Sorting.BY_NAME) } }
                }
            }
        }
    }

    if (picking) {
        FolderPickerSheet(
            FolderPick(R.string.select_folder, R.string.use_this_folder, folder.orEmpty()) { folder = it },
            onDismiss = { picking = false },
        )
    }
    askPassword?.let { request ->
        ArchivePasswordPrompt(
            archive?.path.orEmpty(),
            request.isWrong,
            onPassword = {
                askPassword = null
                usePassword(request.use, it)
            },
            onCancel = { askPassword = null },
        )
    }
    fetching?.let { FetchDialog(it) { fetching = null } }
}

/**
 * The card floating below the content: the archive's name and where it extracts to (or how far it got), the actions
 * to extract (and stop), choose the folder and show its info, which unfolds format and encryption.
 */
@Composable
private fun ArchiveCard(
    archive: ReceivedArchive,
    info: ArchiveInfo?,
    folder: String?,
    extraction: Extraction?,
    showDetails: Boolean,
    onShowDetails: (Boolean) -> Unit,
    onExtract: () -> Unit,
    onPickFolder: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val actions = listOf(
        if (extraction != null) {
            CardAction(Icons.Outlined.Close, stringResource(R.string.cancel)) { extraction.isCancelled = true }
        } else {
            CardAction(Icons.Outlined.Unarchive, stringResource(R.string.decompress), enabled = folder != null, onClick = onExtract)
        },
        CardAction(Icons.Outlined.FolderOpen, stringResource(R.string.archive_target_folder), enabled = extraction == null, onClick = onPickFolder),
        CardAction(Icons.Outlined.Info, stringResource(R.string.info_short), enabled = info != null, isActive = showDetails) { onShowDetails(!showDetails) },
    )
    val subtitle = when {
        extraction != null -> stringResource(R.string.archive_extracting_percent, extraction.percent)
        folder != null -> stringResource(R.string.archive_extract_target, context.displayPath(folder))
        else -> null
    }
    FloatingItemCard(
        title = archive.name,
        subtitle = subtitle,
        menu = CardMenu(actions, emptyList()),
        expanded = false,
        onFold = { onShowDetails(false) },
        visible = visible,
        modifier = modifier,
        details = info?.takeIf { showDetails }?.let { known -> { ArchiveCardDetails(archive, known) } },
    )
}

/** Format, content and encryption of the archive as tiles in its card, then where it lies when it is a file of its own. */
@Composable
private fun ArchiveCardDetails(archive: ReceivedArchive, info: ArchiveInfo) {
    val context = LocalContext.current
    val details = remember(info) { archiveDetails(context, info) }
    CardTileGroups {
        archiveDetailLines(details)
        if (!archive.isCopy) {
            label(stringResource(R.string.file))
            detailLines(listOf(Detail(stringResource(R.string.path), archive.path)))
        }
    }
}

@Composable
private fun CenteredState(padding: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { content() }
}

/** The archive's own file when it can be read directly, otherwise a copy of its content in the cache. */
private fun open(context: Context, uri: Uri, mimeType: String?): ReceivedArchive {
    context.filePathOf(uri)?.takeIf { File(it).canRead() && ArchiveKind.of(it) != null }?.let { path ->
        return ReceivedArchive(path, File(path).name, isCopy = false)
    }

    val reportedName = context.displayNameOf(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: ARCHIVE_NAME
    // the kind comes from the extension, so a name without one gets it from the type
    val name = if (ArchiveKind.of(reportedName) != null) reportedName else reportedName + (EXTENSIONS_BY_TYPE[mimeType] ?: ".zip")
    val folder = File(context.cacheDir, OPENED_FOLDER).apply { mkdirs() }
    val copy = File(folder, name.replace('/', '_'))
    val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException(name)
    input.use { source -> copy.outputStream().use { source.copyTo(it) } }
    return ReceivedArchive(copy.path, name, isCopy = true)
}

/** Next to the archive when it is a file of its own, otherwise the download folder. */
private fun defaultFolder(context: Context, archive: ReceivedArchive): String =
    File(archive.path).parent?.takeIf { !archive.isCopy } ?: "${context.internalStorage}/${Environment.DIRECTORY_DOWNLOADS}"

/** The file manager itself, in its own task, showing [path]. */
private fun showFolder(context: Context, path: String) {
    context.startActivity(
        Intent(context, HomeActivity::class.java)
            .putExtra(HomeActivity.EXTRA_OPEN_FOLDER, path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    )
}

private val EXTENSIONS_BY_TYPE = mapOf(
    "application/zip" to ".zip",
    "application/x-zip-compressed" to ".zip",
    "application/x-7z-compressed" to ".7z",
    "application/x-tar" to ".tar",
    "application/gzip" to ".gz",
    "application/x-gzip" to ".gz",
    "application/x-bzip2" to ".bz2",
    "application/x-xz" to ".xz",
    "application/vnd.rar" to ".rar",
    "application/x-rar-compressed" to ".rar",
    "application/x-rar" to ".rar",
    "application/x-iso9660-image" to ".iso",
    "application/vnd.ms-cab-compressed" to ".cab",
    "application/x-arj" to ".arj",
    "application/x-lzh-compressed" to ".lzh",
    "application/x-lha" to ".lha",
    "application/x-cpio" to ".cpio",
    "application/x-rpm" to ".rpm",
    "application/vnd.debian.binary-package" to ".deb",
    "application/x-debian-package" to ".deb",
    "application/vnd.ms-htmlhelp" to ".chm",
    "application/x-xar" to ".xar",
    "application/x-compress" to ".z",
    "application/x-lzma" to ".lzma",
    "application/x-ms-wim" to ".wim",
)
private const val OPENED_FOLDER = "opened_archives"
private const val ARCHIVE_NAME = "archive"
private const val OPENED_FILES_FOLDER = "archive_files"
private const val PASSWORD_PROBE_FOLDER = "archive_probe"
private const val FETCH_DIALOG_DELAY_MS = 300L

private val WIDE_LAYOUT = 600.dp
