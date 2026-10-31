package de.needanapp.needafile.editor

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.AppScaffold
import de.needanapp.design.components.CardAction
import de.needanapp.design.components.CardActionGroup
import de.needanapp.design.components.CardMenu
import de.needanapp.design.components.CardTileGroups
import de.needanapp.design.components.ContentDialog
import de.needanapp.design.components.Detail
import de.needanapp.design.components.EmptyState
import de.needanapp.design.components.FloatingItemCard
import de.needanapp.design.components.FloatingItemCardDefaults
import de.needanapp.design.components.SignalButton
import de.needanapp.design.components.SignalCircularProgressIndicator
import de.needanapp.design.components.detailLines
import de.needanapp.design.components.rememberPillScrollBehavior
import de.needanapp.design.components.rememberSearchState
import de.needanapp.design.components.verticalScrollWithBar
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.EXTRA_FILE_PATH
import de.needanapp.needafile.core.displayPath
import de.needanapp.needafile.core.filePathOf
import de.needanapp.needafile.core.formatSize
import de.needanapp.needafile.core.getMimeType
import de.needanapp.needafile.core.internalStorage
import de.needanapp.needafile.core.openFile
import de.needanapp.needafile.core.openFileForWriting
import de.needanapp.needafile.core.rescanPaths
import de.needanapp.needafile.core.showError
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.permissions.withStorageAccess
import de.needanapp.needafile.theme.setThemedContent
import de.needanapp.needafile.vault.closeWhenVaultLocks
import de.needanapp.needafile.viewer.FileFacts
import de.needanapp.needafile.viewer.ViewerActions
import de.needanapp.needafile.viewer.fileFacts
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the text comes from and is saved to: a file, or only a content address another app handed over. */
private data class TextSource(val path: String?, val uri: Uri, val name: String)

/** Plain text files (notes, configuration, logs, code) opened from needafile or another app, to read and change. */
class TextEditorActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        val uri = path?.let { Uri.fromFile(File(it)) } ?: intent.data
        if (uri == null) {
            toast(R.string.unknown_error_occurred)
            finish()
            return
        }
        closeWhenVaultLocks(path, intent.data)
        if (isFinishing) return
        setThemedContent { TextEditorScreen(this, uri, intent.type) }
    }
}

@Composable
private fun TextEditorScreen(activity: BaseActivity, uri: Uri, mimeType: String?) {
    var source by remember { mutableStateOf<TextSource?>(null) }
    var original by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var error by remember { mutableStateOf<String?>(null) }
    var tooLarge by remember { mutableStateOf<Long?>(null) }
    var showSaveAs by remember { mutableStateOf(false) }
    // "more" unfolds the card's further actions, "info" what is known about the text
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    // the card steps aside while reading on and comes back when scrolling back
    val cardScroll = rememberPillScrollBehavior()
    var askBeforeClosing by remember { mutableStateOf(false) }
    // what happens once the text is saved: nothing, or closing the editor
    var closeAfterSaving by remember { mutableStateOf(false) }
    val search = rememberSearchState()
    var currentMatch by remember { mutableIntStateOf(0) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scrollState = rememberScrollState()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val isChanged = original != null && text.text != original

    // texts with very many lines get too tall for a text field; they are shown read-only, line by line
    val density = LocalDensity.current
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val bodyStyle = MaterialTheme.typography.bodyLarge
    val readOnlyLines = remember(original, windowWidth, density, bodyStyle) {
        val content = original ?: return@remember null
        val tooTall = with(density) {
            val fontSize = bodyStyle.fontSize.toPx()
            val lineHeight = if (bodyStyle.lineHeight.isSp) bodyStyle.lineHeight.toPx() else fontSize * FALLBACK_LINE_HEIGHT
            isTooTallToEdit(content, windowWidth - EDITOR_SIDE_PADDING.dp.toPx() * 2, fontSize, lineHeight)
        }
        if (tooTall) lineStarts(content) else null
    }
    val isReadOnly = readOnlyLines != null

    LaunchedEffect(uri) {
        try {
            val opened = withContext(Dispatchers.IO) { resolve(activity, uri) }
            source = opened
            val size = withContext(Dispatchers.IO) { sizeOf(activity, opened) }
            if (size != null && size > MAX_SIZE) {
                tooLarge = size
                return@LaunchedEffect
            }
            val content = withContext(Dispatchers.IO) { read(activity, opened) }
            original = content
            text = TextFieldValue(content)
            // an empty file is there to be written in
            if (content.isEmpty()) focusRequester.requestFocus()
        } catch (e: Exception) {
            error = activity.describeError(e)
        } catch (e: OutOfMemoryError) {
            tooLarge = -1
        }
    }

    val matches = remember(text.text, search.query) { if (search.isActive) findMatches(text.text, search.query) else emptyList() }
    LaunchedEffect(matches) { currentMatch = currentMatch.coerceIn(0, (matches.size - 1).coerceAtLeast(0)) }

    /** Selects the match with [index] and scrolls it into the upper third of the screen. */
    fun showMatch(index: Int) {
        if (matches.isEmpty()) return
        currentMatch = (index + matches.size) % matches.size
        val start = matches[currentMatch]
        if (readOnlyLines != null) {
            // the first row is the note that the text is read-only
            scope.launch { listState.scrollToItem(lineOf(readOnlyLines, start) + 1, -listState.layoutInfo.viewportSize.height / 3) }
            return
        }
        text = text.copy(selection = TextRange(start, start + search.query.length))
        val top = layout?.getBoundingBox(start)?.top ?: return
        scope.launch { scrollState.animateScrollTo((top - scrollState.viewportSize / 3f).toInt().coerceAtLeast(0)) }
    }

    fun save(target: TextSource) {
        // content from another app needs no access to all files
        val write = if (target.path == null) { action: () -> Unit -> action() } else activity::withStorageAccess
        write {
            val content = text.text
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { write(activity, target, content) } }
                result.onSuccess {
                    source = target
                    original = content
                    activity.toast(R.string.file_saved)
                    if (closeAfterSaving) activity.finish()
                }.onFailure { e ->
                    closeAfterSaving = false
                    if (e is SecurityException) {
                        activity.toast(R.string.editor_read_only)
                        showSaveAs = true
                    } else {
                        activity.showError(activity.describeError(e))
                    }
                }
            }
        }
    }

    fun close() {
        if (isChanged) askBeforeClosing = true else activity.finish()
    }

    BackHandler(enabled = isChanged && !search.isActive) { close() }

    val shownSource = source
    // read again after saving, for the new size and date
    val facts by produceState<FileFacts?>(null, shownSource, original) {
        value = shownSource?.let { withContext(Dispatchers.IO) { fileFacts(activity, it.path, it.uri) } }
    }
    AppScaffold(
        title = shownSource?.name.orEmpty(),
        subtitle = facts?.shownPath?.let { File(it).parent }?.let(activity::displayPath),
        navigationIcon = {
            IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.go_back)) }
        },
        search = search.takeIf { original != null },
        searchActions = {
            if (search.query.isNotEmpty()) {
                Text(
                    stringResource(R.string.editor_match_position, if (matches.isEmpty()) 0 else currentMatch + 1, matches.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { showMatch(currentMatch - 1) }, enabled = matches.isNotEmpty()) {
                    Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.editor_previous_match))
                }
                IconButton(onClick = { showMatch(currentMatch + 1) }, enabled = matches.isNotEmpty()) {
                    Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.editor_next_match))
                }
            }
        },
        onSearchSubmit = { showMatch(currentMatch + 1) },
        actions = {
            if (original != null && !isReadOnly) {
                IconButton(onClick = { source?.let(::save) }, enabled = isChanged) {
                    Icon(Icons.Outlined.Save, stringResource(R.string.save))
                }
            }
        },
        bottomBar = {
            if (shownSource != null && original != null) {
                EditorCard(
                    activity = activity,
                    source = shownSource,
                    mimeType = mimeType,
                    text = text.text,
                    facts = facts,
                    expanded = expanded,
                    onExpand = {
                        showDetails = false
                        expanded = it
                    },
                    showDetails = showDetails,
                    onShowDetails = {
                        expanded = false
                        showDetails = it
                    },
                    onSaveAs = { showSaveAs = true },
                    visible = !search.isActive && cardScroll.isVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
    ) { padding ->
        val colors = MaterialTheme.colorScheme
        when {
            error != null -> EmptyState(Icons.Outlined.ErrorOutline, stringResource(R.string.editor_open_failed), Modifier.padding(padding), message = error)
            tooLarge != null -> {
                val size = tooLarge!!
                EmptyState(
                    Icons.Outlined.ErrorOutline,
                    stringResource(R.string.editor_too_large_title),
                    modifier = Modifier.padding(padding),
                    message = if (size > 0) stringResource(R.string.editor_too_large, size.formatSize()) else null,
                    action = {
                        SignalButton(onClick = { shownSource?.let { openWith(activity, it, mimeType) } }) { Text(stringResource(R.string.open_with)) }
                    },
                )
            }

            original == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { SignalCircularProgressIndicator() }
            readOnlyLines != null -> Box(Modifier.nestedScroll(cardScroll.nestedScrollConnection)) {
                LongTextView(
                    original!!,
                    readOnlyLines,
                    matches,
                    search.query.length,
                    currentMatch,
                    listState,
                    PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + EDITOR_BOTTOM_SPACE.dp + FloatingItemCardDefaults.SpaceBelowContent),
                )
            }

            else -> {
                // the matches' outlines, drawn behind the text
                val shapes = remember(layout, matches, search.query) {
                    val result = layout ?: return@remember emptyList()
                    val length = result.layoutInput.text.length
                    matches.filter { it < length }.map { result.getPathForRange(it, (it + search.query.length).coerceAtMost(length)) }
                }
                Column(Modifier.fillMaxSize().imePadding().nestedScroll(cardScroll.nestedScrollConnection).verticalScrollWithBar(scrollState)) {
                    Spacer(Modifier.height(padding.calculateTopPadding()))
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground),
                        cursorBrush = SolidColor(colors.primary),
                        onTextLayout = { layout = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = EDITOR_SIDE_PADDING.dp, vertical = 8.dp)
                            .drawBehind {
                                shapes.forEachIndexed { index, shape ->
                                    drawPath(shape, if (index == currentMatch) colors.tertiaryContainer else colors.primaryContainer)
                                }
                            }
                            .focusRequester(focusRequester),
                    )
                    // room to read the end above the card
                    Spacer(Modifier.height(padding.calculateBottomPadding() + EDITOR_BOTTOM_SPACE.dp + FloatingItemCardDefaults.SpaceBelowContent))
                }
            }
        }
    }

    if (showSaveAs && shownSource != null) {
        SaveAsSheet(
            name = shownSource.name,
            folder = shownSource.path?.let { File(it).parent } ?: defaultFolder(activity),
            onDismiss = {
                showSaveAs = false
                closeAfterSaving = false
            },
            onSave = { path ->
                showSaveAs = false
                save(TextSource(path, Uri.fromFile(File(path)), File(path).name))
            },
        )
    }
    if (askBeforeClosing) {
        ContentDialog(
            title = stringResource(R.string.editor_unsaved_title),
            confirmLabel = stringResource(R.string.save),
            onConfirm = {
                askBeforeClosing = false
                closeAfterSaving = true
                source?.let(::save)
            },
            onDismiss = { askBeforeClosing = false },
            extraLabel = stringResource(R.string.discard),
            onExtra = {
                askBeforeClosing = false
                activity.finish()
            },
            icon = Icons.Outlined.EditNote,
            confirmIcon = Icons.Outlined.Save,
            extraIcon = Icons.Outlined.Delete,
        ) {
            Text(stringResource(R.string.editor_unsaved_message))
        }
    }
}

/** Where every match of [query] starts, ignoring case; at most [MAX_MATCHES]. */
private fun findMatches(text: String, query: String): List<Int> {
    if (query.isEmpty()) return emptyList()
    val matches = ArrayList<Int>()
    var index = text.indexOf(query, ignoreCase = true)
    while (index >= 0 && matches.size < MAX_MATCHES) {
        matches.add(index)
        index = text.indexOf(query, index + query.length, ignoreCase = true)
    }
    return matches
}

/** The file behind [uri] when there is one that can be read, so saving writes it directly. */
private fun resolve(context: Context, uri: Uri): TextSource {
    val path = if (uri.scheme == ContentResolver.SCHEME_FILE) uri.path else runCatching { context.filePathOf(uri) }.getOrNull()
    val file = path?.let(::File)?.takeIf { it.canRead() }
    if (file != null) return TextSource(file.path, uri, file.name)
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.editor_untitled)
    return TextSource(null, uri, name)
}

private fun sizeOf(context: Context, source: TextSource): Long? {
    source.path?.let { return File(it).length() }
    return runCatching {
        context.contentResolver.query(source.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()
}

private fun read(context: Context, source: TextSource): String =
    if (source.path != null) {
        File(source.path).readText()
    } else {
        (context.contentResolver.openInputStream(source.uri) ?: throw IOException(source.name)).bufferedReader().use { it.readText() }
    }

private fun write(context: Context, source: TextSource, text: String) {
    val output: OutputStream? = if (source.path != null) {
        openFileForWriting(source.path)
    } else {
        // "wt" empties the file first; not every app supports it
        runCatching { context.contentResolver.openOutputStream(source.uri, "wt") }.getOrNull() ?: context.contentResolver.openOutputStream(source.uri, "w")
    }
    (output ?: throw IOException(source.name)).bufferedWriter().use { it.write(text) }
    source.path?.let { context.rescanPaths(arrayListOf(it)) }
}

private fun openWith(activity: BaseActivity, source: TextSource, mimeType: String?) {
    if (source.path != null) {
        activity.openFile(source.path, true)
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(source.uri, mimeType ?: "text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.open_with))) }
        .onFailure { activity.toast(R.string.no_app_found) }
}

/** Prints the text through the system's print dialog (also "save as PDF"). */
private fun print(activity: BaseActivity, name: String, text: String) {
    val webView = WebView(activity)
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            val adapter = view.createPrintDocumentAdapter(name)
            activity.getSystemService(PrintManager::class.java)?.print(name, adapter, PrintAttributes.Builder().build())
        }
    }
    webView.loadData(Base64.encodeToString(text.toByteArray(), Base64.DEFAULT), "text/plain; charset=utf-8", "base64")
}

private fun defaultFolder(context: Context) = "${context.internalStorage}/${Environment.DIRECTORY_DOCUMENTS}"

// larger files make typing slow; they are better opened with another app
private const val MAX_SIZE = 4L * 1024 * 1024
private const val MAX_MATCHES = 2000
/**
 * The text's actions as a card floating at the bottom, as the viewers have them: save as, share, print, info and
 * more – open with, show in folder. Info unfolds what is known about the text and its file. It steps aside for the
 * keyboard; saving stays in the header, where it is at hand while typing.
 */
@Composable
private fun EditorCard(
    activity: BaseActivity,
    source: TextSource,
    mimeType: String?,
    text: String,
    facts: FileFacts?,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    showDetails: Boolean,
    onShowDetails: (Boolean) -> Unit,
    onSaveAs: () -> Unit,
    visible: Boolean,
    modifier: Modifier,
) {
    fun folding(action: () -> Unit): () -> Unit = {
        onExpand(false)
        action()
    }
    val actions = listOf(
        CardAction(Icons.Outlined.SaveAs, stringResource(R.string.save_as), onClick = onSaveAs),
        CardAction(Icons.Outlined.Share, stringResource(R.string.share)) {
            ViewerActions.share(activity, source.path, source.uri, mimeType?.takeIf { it.isNotEmpty() } ?: "text/plain")
        },
        CardAction(Icons.Outlined.Print, stringResource(R.string.print)) { print(activity, source.name, text) },
        CardAction(Icons.Outlined.Info, stringResource(R.string.info_short), isActive = showDetails) { onShowDetails(!showDetails) },
        CardAction(
            icon = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.MoreHoriz,
            label = stringResource(if (expanded) R.string.less_actions else R.string.more_actions),
            isActive = expanded,
        ) { onExpand(!expanded) },
    )
    val groups = listOfNotNull(
        CardActionGroup(
            stringResource(R.string.card_group_open),
            listOf(CardAction(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.open_with), onClick = folding { openWith(activity, source, mimeType) })),
        ),
        facts?.localPath?.let { path ->
            CardActionGroup(
                stringResource(R.string.card_group_place),
                listOf(CardAction(Icons.Outlined.FolderOpen, stringResource(R.string.show_in_folder), onClick = folding { ViewerActions.showInFolder(activity, path) })),
            )
        },
    )
    FloatingItemCard(
        title = source.name,
        subtitle = facts?.summary,
        menu = CardMenu(actions, groups),
        expanded = expanded,
        onFold = {
            onExpand(false)
            onShowDetails(false)
        },
        visible = visible,
        modifier = modifier,
        details = if (showDetails) {
            { EditorDetails(source, mimeType, text, facts) }
        } else {
            null
        },
    )
}

/** Lines and characters of the text as it is now, then its file's type and place, as tiles in the card. */
@Composable
private fun EditorDetails(source: TextSource, mimeType: String?, text: String, facts: FileFacts?) {
    // the line break that ends the last line starts no line of its own
    val lines = remember(text) { if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1 }
    val counts = listOf(
        Detail(stringResource(R.string.editor_lines), NumberFormat.getIntegerInstance().format(lines)),
        Detail(stringResource(R.string.editor_characters), NumberFormat.getIntegerInstance().format(text.length)),
    )
    val file = listOfNotNull(
        (mimeType?.takeIf { it.isNotEmpty() } ?: source.name.getMimeType()).takeIf { it.isNotEmpty() }?.let { Detail(stringResource(R.string.file_type), it) },
        facts?.shownPath?.let { Detail(stringResource(R.string.path), it) },
    )
    CardTileGroups {
        label(stringResource(R.string.editor_text_card))
        detailLines(counts)
        if (file.isNotEmpty()) {
            label(stringResource(R.string.file))
            detailLines(file)
        }
    }
}

private const val EDITOR_BOTTOM_SPACE = 24

internal const val EDITOR_SIDE_PADDING = 20
private const val FALLBACK_LINE_HEIGHT = 1.5f
