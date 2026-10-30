package de.needanapp.needafile.viewer.document

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.AppScaffold
import de.needanapp.design.components.CardAction
import de.needanapp.design.components.CardActionGroup
import de.needanapp.design.components.CardMenu
import de.needanapp.design.components.CardTileGroups
import de.needanapp.design.components.ChipRow
import de.needanapp.design.components.Detail
import de.needanapp.design.components.EmptyState
import de.needanapp.design.components.FloatingItemCard
import de.needanapp.design.components.FloatingItemCardDefaults
import de.needanapp.design.components.detailLines
import de.needanapp.design.components.rememberPillScrollBehavior
import de.needanapp.design.components.rememberSearchState
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.EXTRA_FILE_PATH
import de.needanapp.needafile.core.displayPath
import de.needanapp.needafile.core.getMimeType
import de.needanapp.needafile.core.toast
import de.needanapp.needafile.editor.TextEditorActivity
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.theme.setThemedContent
import de.needanapp.needafile.vault.closeWhenVaultLocks
import de.needanapp.needafile.viewer.FileFacts
import de.needanapp.needafile.viewer.ViewerActions
import de.needanapp.needafile.viewer.fileFacts
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Documents from needafile or another app: PDF, Word (DOCX), OpenDocument, Excel, web pages, Markdown and CSV
 * tables. Plain text goes to the text editor; everything can also be handed to another app.
 */
class DocumentViewerActivity : BaseActivity() {
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
        setThemedContent { DocumentViewerScreen(this, uri, intent.type) }
    }
}

@Composable
private fun DocumentViewerScreen(activity: BaseActivity, uri: Uri, mimeType: String?) {
    var document by remember { mutableStateOf<ViewerDocument?>(null) }
    var content by remember { mutableStateOf<DocumentContent?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isFullScreen by rememberSaveable { mutableStateOf(false) }
    var pageInfo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // "more" unfolds the card's further actions, "info" what is known about the document
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    // the card steps aside while reading on and comes back when scrolling back
    val cardScroll = rememberPillScrollBehavior()
    var sheetIndex by rememberSaveable { mutableIntStateOf(0) }
    var findResult by remember { mutableStateOf(0 to 0) }
    val webSearch = remember { WebSearch() }
    val search = rememberSearchState()

    LaunchedEffect(uri) {
        val resolved = withContext(Dispatchers.IO) { Documents.resolve(activity, uri, mimeType) }
        if (resolved == null) {
            error = activity.getString(R.string.document_unsupported, activity.getString(R.string.brand_name))
            return@LaunchedEffect
        }
        if (resolved.kind == ViewerDocumentKind.TEXT) {
            activity.startActivity(editAsTextIntent(activity, resolved))
            activity.finish()
            return@LaunchedEffect
        }
        document = resolved
        try {
            content = withContext(Dispatchers.IO) { Documents.load(activity, resolved) }
        } catch (e: DocumentUnreadableException) {
            error = activity.getString(R.string.document_too_large)
        } catch (e: DocumentDamagedException) {
            error = activity.getString(R.string.document_damaged)
        } catch (e: Exception) {
            error = activity.describeError(e)
        } catch (e: OutOfMemoryError) {
            error = activity.getString(R.string.document_too_large)
        }
    }

    val facts by produceState<FileFacts?>(null, document) {
        value = document?.let { withContext(Dispatchers.IO) { fileFacts(activity, it.path, it.uri) } }
    }
    val shown = content
    val sheets = (shown as? DocumentContent.Table)?.sheets.orEmpty()
    val sheet = sheets.getOrNull(sheetIndex.coerceIn(0, (sheets.size - 1).coerceAtLeast(0)))
    val canSearch = shown is DocumentContent.Web || shown is DocumentContent.Table
    LaunchedEffect(isFullScreen) { ViewerActions.setFullScreen(activity, isFullScreen) }
    LaunchedEffect(search.query, search.isActive) { if (shown is DocumentContent.Web) webSearch.find(if (search.isActive) search.query else "") }

    AppScaffold(
        title = document?.name.orEmpty(),
        subtitle = pageInfo?.let { (page, count) -> stringResource(R.string.document_page, page + 1, count) }
            ?: facts?.shownPath?.let { File(it).parent }?.let(activity::displayPath),
        navigationIcon = {
            IconButton(onClick = { activity.finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.go_back)) }
        },
        search = search.takeIf { canSearch },
        searchActions = {
            if (search.query.isNotEmpty()) {
                when {
                    shown is DocumentContent.Web -> {
                        val (active, count) = findResult
                        Text(
                            stringResource(R.string.editor_match_position, if (count == 0) 0 else active + 1, count),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { webSearch.next(false) }, enabled = count > 0) {
                            Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.editor_previous_match))
                        }
                        IconButton(onClick = { webSearch.next(true) }, enabled = count > 0) {
                            Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.editor_next_match))
                        }
                    }
                    sheet != null -> {
                        val rows = remember(sheet, search.query) { sheet.matching(search.query).size }
                        Text(
                            pluralStringResource(R.plurals.document_matching_rows, rows, rows),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                }
            }
        },
        onSearchSubmit = { if (shown is DocumentContent.Web) webSearch.next(true) },
        headerBottom = if (sheets.size > 1) {
            {
                ChipRow(sheets.indices.toList(), Modifier.padding(vertical = 4.dp), initialIndex = sheetIndex) { index ->
                    FilterChip(selected = index == sheetIndex, onClick = { sheetIndex = index }, label = { Text(sheets[index].name) })
                }
            }
        } else {
            null
        },
        headerVisible = !isFullScreen,
        bottomBar = {
            val current = document
            if (current != null && shown != null && error == null) {
                DocumentCard(
                    activity = activity,
                    document = current,
                    content = shown,
                    facts = facts,
                    pages = pageInfo?.second,
                    sheetCount = sheets.size,
                    webSearch = webSearch,
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
                    visible = !isFullScreen && !search.isActive && cardScroll.isVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
    ) { padding ->
        // room to read the end above the card
        val bottom = padding.calculateBottomPadding() + FloatingItemCardDefaults.SpaceBelowContent
        val current = document
        when {
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Outlined.ErrorOutline,
                    stringResource(R.string.document_open_failed),
                    message = error,
                    action = current?.let { doc ->
                        { Button(onClick = { ViewerActions.openWith(activity, doc.path, doc.uri, doc.mimeType.ifEmpty { "*/*" }) }) { Text(stringResource(R.string.open_with)) } }
                    },
                )
            }

            shown == null || current == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            shown is DocumentContent.Pdf -> PdfContent(
                document = current,
                modifier = Modifier.padding(top = if (isFullScreen) 0.dp else padding.calculateTopPadding()),
                onTap = {
                    isFullScreen = !isFullScreen
                    cardScroll.show()
                },
                onPageChange = { page, count -> pageInfo = page to count },
                onScroll = cardScroll::onScroll,
                onError = { error = activity.getString(R.string.document_open_failed) },
                onCancelPassword = { activity.finish() },
            )

            shown is DocumentContent.Web -> WebContent(shown.html, padding.calculateTopPadding(), bottom, webSearch, cardScroll::onScroll) { active, count ->
                findResult = active to count
            }

            shown is DocumentContent.Table && sheet != null -> Box(Modifier.nestedScroll(cardScroll.nestedScrollConnection)) {
                TableContent(sheet, if (search.isActive) search.query else "", PaddingValues(top = padding.calculateTopPadding(), bottom = bottom))
            }
        }
    }
}

/**
 * The document's actions as a card floating at the bottom, as the image viewer has them: edit (documents that are text
 * underneath), share, print (PDFs and pages), info and more – open with, show in folder. Info unfolds what is known.
 */
@Composable
private fun DocumentCard(
    activity: BaseActivity,
    document: ViewerDocument,
    content: DocumentContent,
    facts: FileFacts?,
    pages: Int?,
    sheetCount: Int,
    webSearch: WebSearch,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    showDetails: Boolean,
    onShowDetails: (Boolean) -> Unit,
    visible: Boolean,
    modifier: Modifier,
) {
    val type = document.mimeType.ifEmpty { "*/*" }
    fun folding(action: () -> Unit): () -> Unit = {
        onExpand(false)
        action()
    }
    val actions = buildList {
        if (document.kind in textKinds) {
            add(CardAction(Icons.Outlined.Edit, stringResource(R.string.edit)) { activity.startActivity(editAsTextIntent(activity, document)) })
        }
        add(CardAction(Icons.Outlined.Share, stringResource(R.string.share)) { ViewerActions.share(activity, document.path, document.uri, type) })
        if (content is DocumentContent.Pdf || content is DocumentContent.Web) {
            add(CardAction(Icons.Outlined.Print, stringResource(R.string.print)) { print(activity, document, content, webSearch) })
        }
        add(CardAction(Icons.Outlined.Info, stringResource(R.string.info_short), enabled = facts != null, isActive = showDetails) { onShowDetails(!showDetails) })
        add(
            CardAction(
                icon = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.MoreHoriz,
                label = stringResource(if (expanded) R.string.less_actions else R.string.more_actions),
                isActive = expanded,
            ) { onExpand(!expanded) },
        )
    }
    val groups = listOfNotNull(
        CardActionGroup(
            stringResource(R.string.card_group_open),
            listOf(CardAction(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.open_with), onClick = folding {
                ViewerActions.openWith(activity, document.path, document.uri, type)
            })),
        ),
        facts?.localPath?.let { path ->
            CardActionGroup(
                stringResource(R.string.card_group_place),
                listOf(CardAction(Icons.Outlined.FolderOpen, stringResource(R.string.show_in_folder), onClick = folding {
                    ViewerActions.showInFolder(activity, path)
                })),
            )
        },
    )
    FloatingItemCard(
        title = document.name,
        subtitle = facts?.summary,
        menu = CardMenu(actions, groups),
        expanded = expanded,
        onFold = {
            onExpand(false)
            onShowDetails(false)
        },
        visible = visible,
        modifier = modifier,
        details = facts?.takeIf { showDetails }?.let { known -> { DocumentDetails(document, known, pages, sheetCount) } },
    )
}

/** What is known about [document] as tiles in its card: its pages or sheets, then its type and where it lies. */
@Composable
private fun DocumentDetails(document: ViewerDocument, facts: FileFacts, pages: Int?, sheetCount: Int) {
    val extent = buildList {
        pages?.let { add(Detail(stringResource(R.string.document_pages), it.toString())) }
        if (sheetCount > 1) add(Detail(stringResource(R.string.document_sheets), sheetCount.toString()))
    }
    val file = listOfNotNull(
        document.mimeType.ifEmpty { document.name.getMimeType() }.takeIf { it.isNotEmpty() }?.let { Detail(stringResource(R.string.file_type), it) },
        facts.shownPath?.let { Detail(stringResource(R.string.path), it) },
    )
    CardTileGroups {
        if (extent.isNotEmpty()) {
            label(stringResource(R.string.document_card))
            detailLines(extent)
        }
        if (file.isNotEmpty()) {
            label(stringResource(R.string.file))
            detailLines(file)
        }
    }
}

// documents that are plain text underneath and can be changed in the text editor
private val textKinds = setOf(ViewerDocumentKind.MARKDOWN, ViewerDocumentKind.CSV, ViewerDocumentKind.HTML, ViewerDocumentKind.TEXT)

private fun editAsTextIntent(activity: BaseActivity, document: ViewerDocument): Intent {
    val intent = Intent(activity, TextEditorActivity::class.java)
    return if (document.path != null) {
        intent.putExtra(EXTRA_FILE_PATH, document.path)
    } else {
        intent.setDataAndType(document.uri, document.mimeType.ifEmpty { "text/plain" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}

/** Through the system's print dialog (also "save as PDF"): PDFs as they are, other documents as shown. */
private fun print(activity: BaseActivity, document: ViewerDocument, content: DocumentContent, webSearch: WebSearch) {
    val printManager = activity.getSystemService(PrintManager::class.java) ?: return
    val adapter = when (content) {
        is DocumentContent.Pdf -> PdfPrintAdapter(activity, document.uri, document.name)
        else -> webSearch.view?.createPrintDocumentAdapter(document.name) ?: return
    }
    runCatching { printManager.print(document.name, adapter, null) }.onFailure { activity.toast(R.string.unknown_error_occurred) }
}

