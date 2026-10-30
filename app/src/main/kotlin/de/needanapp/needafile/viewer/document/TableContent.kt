package de.needanapp.needafile.viewer.document

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.EmptyState
import de.needanapp.design.components.scrollbar
import de.needanapp.needafile.R

/** Rows that contain [query] (all when empty); the header row always stays. */
internal fun Sheet.matching(query: String): List<List<String>> {
    val body = rows.drop(1)
    if (query.isBlank()) return body
    return body.filter { row -> row.any { it.contains(query, ignoreCase = true) } }
}

/**
 * A table (CSV, spreadsheet): the first row stays on top as header, wide tables scroll sideways, values can be
 * selected and copied. Only rows containing [query] are shown.
 */
@Composable
internal fun TableContent(sheet: Sheet, query: String, padding: PaddingValues) {
    if (sheet.rows.isEmpty() || sheet.columnCount == 0) {
        Box(Modifier.fillMaxSize().padding(padding)) { EmptyState(Icons.Outlined.TableChart, stringResource(R.string.document_empty)) }
        return
    }
    val widths = remember(sheet) { columnWidths(sheet) }
    val totalWidth = widths.fold(0.dp) { sum, width -> sum + width }
    val shown = remember(sheet, query) { sheet.matching(query) }
    val listState = rememberLazyListState()
    // the bar lies outside the sideways scrolling, so it stays at the screen's edge
    Box(Modifier.fillMaxSize().scrollbar(listState, padding).horizontalScroll(rememberScrollState())) {
        // below the header, so the table's own header row stays readable on top
        LazyColumn(
            Modifier.width(totalWidth).fillMaxHeight().padding(top = padding.calculateTopPadding()),
            state = listState,
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
        ) {
            stickyHeader { TableRow(sheet.rows.first(), widths, isHeader = true) }
            itemsIndexed(shown) { index, row -> TableRow(row, widths, isShaded = index % 2 == 1) }
            if (shown.isEmpty() && query.isNotBlank()) {
                item { EmptyState(Icons.Outlined.SearchOff, stringResource(R.string.no_items_found)) }
            }
        }
    }
}

@Composable
private fun TableRow(values: List<String>, widths: List<Dp>, isHeader: Boolean = false, isShaded: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        isHeader -> colors.primaryContainer
        isShaded -> colors.surfaceContainer
        else -> colors.background
    }
    Row(Modifier.height(IntrinsicSize.Min).background(background)) {
        widths.forEachIndexed { index, width ->
            Box(Modifier.width(width).fillMaxHeight().heightIn(min = CELL_HEIGHT.dp).border(0.5.dp, colors.outlineVariant)) {
                SelectionContainer {
                    Text(
                        values.getOrElse(index) { "" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (isHeader) colors.onPrimaryContainer else colors.onBackground,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

// each column as wide as its longest value, within limits so short columns stay readable and long ones don't take over
private fun columnWidths(sheet: Sheet): List<Dp> = List(sheet.columnCount) { column ->
    val longest = sheet.rows.maxOfOrNull { it.getOrElse(column) { "" }.length } ?: 0
    (longest.coerceIn(MIN_CHARS, MAX_CHARS) * CHAR_WIDTH + CELL_PADDING).dp
}

private const val CELL_HEIGHT = 44
private const val MIN_CHARS = 6
private const val MAX_CHARS = 30
private const val CHAR_WIDTH = 8
private const val CELL_PADDING = 24
