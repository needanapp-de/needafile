package de.needanapp.needafile.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.FastScrollbar
import de.needanapp.needafile.R

/**
 * Shows a text too tall for an editable text field read-only, one lazily laid out row per line of [lineStarts]. The
 * [matches] of a search (start offsets in [text], [queryLength] long) are highlighted, [currentMatch] in another color.
 * The text can be selected and copied.
 */
@Composable
internal fun LongTextView(
    text: String,
    lineStarts: IntArray,
    matches: List<Int>,
    queryLength: Int,
    currentMatch: Int,
    listState: LazyListState,
    contentPadding: PaddingValues,
) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground)
    // the matches' numbers and offsets, by the line they start in
    val matchesByLine = remember(lineStarts, matches) { matches.withIndex().groupBy { lineOf(lineStarts, it.value) } }

    // a long text gets the handle to move through it fast
    Box(Modifier.fillMaxSize().imePadding()) {
        SelectionContainer {
            LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        stringResource(R.string.editor_read_only_long),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = EDITOR_SIDE_PADDING.dp, vertical = 8.dp),
                    )
                }
                items(lineStarts.size) { index ->
                    val start = lineStarts[index]
                    val end = if (index + 1 < lineStarts.size) lineStarts[index + 1] - 1 else text.length
                    val line = text.substring(start, end).removeSuffix("\r")
                    val lineMatches = matchesByLine[index]
                    val shown = if (lineMatches == null) {
                        AnnotatedString(line)
                    } else {
                        buildAnnotatedString {
                            append(line)
                            lineMatches.forEach { (number, offset) ->
                                val from = (offset - start).coerceAtMost(line.length)
                                val background = if (number == currentMatch) colors.tertiaryContainer else colors.primaryContainer
                                addStyle(SpanStyle(background = background), from, (from + queryLength).coerceAtMost(line.length))
                            }
                        }
                    }
                    Text(shown, style = style, modifier = Modifier.fillMaxWidth().padding(horizontal = EDITOR_SIDE_PADDING.dp))
                }
            }
        }
        FastScrollbar(listState, Modifier.matchParentSize(), contentPadding)
    }
}

/** The offsets where the lines of [text] start; the first line starts at 0. */
internal fun lineStarts(text: String): IntArray {
    val starts = ArrayList<Int>()
    starts.add(0)
    var index = text.indexOf('\n')
    while (index >= 0) {
        starts.add(index + 1)
        index = text.indexOf('\n', index + 1)
    }
    return starts.toIntArray()
}

/** The number of the line that contains [offset]. */
internal fun lineOf(lineStarts: IntArray, offset: Int): Int {
    val found = lineStarts.binarySearch(offset)
    return if (found >= 0) found else -found - 2
}

/**
 * Whether [text] would lay out taller than an editable text field can be: Compose allows at most 2^24 - 1 pixels per
 * side, and a text field lays out all of its text at once. Rows are counted generously, every line takes at least one
 * and long ones wrap at an average character width of [AVERAGE_CHAR_WIDTH_EM], wider than most letters. Texts longer
 * than [MAX_EDITABLE_CHARS] count as too tall as well: laying them out at once would block the app for seconds.
 */
internal fun isTooTallToEdit(text: String, widthPx: Float, fontSizePx: Float, lineHeightPx: Float): Boolean {
    if (text.length > MAX_EDITABLE_CHARS) return true
    val charsPerRow = (widthPx / (fontSizePx * AVERAGE_CHAR_WIDTH_EM)).toInt().coerceAtLeast(1)
    val maxRows = (MAX_EDITABLE_HEIGHT_PX / lineHeightPx).toLong()
    var rows = 0L
    var start = 0
    while (start <= text.length) {
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        rows += ((end - start + charsPerRow - 1) / charsPerRow).coerceAtLeast(1)
        if (rows > maxRows) return true
        start = end + 1
    }
    return false
}

// well below the 16,777,215 pixels Compose allows, the row count is only an estimate
private const val MAX_EDITABLE_HEIGHT_PX = 12_000_000f
private const val AVERAGE_CHAR_WIDTH_EM = 0.75f
private const val MAX_EDITABLE_CHARS = 150_000
