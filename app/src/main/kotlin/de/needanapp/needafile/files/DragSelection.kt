package de.needanapp.needafile.files

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import de.needanapp.needafile.core.FileEntry
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

/**
 * Selecting by dragging, as in a photo gallery: a long press selects the entry under the finger (its own long click
 * does that, then calls [begin]); while the finger stays down, every entry from there to the one under the finger joins
 * the selection. Going back drops what was added on the way, what was selected before stays.
 */
@Stable
internal class DragSelection {
    private var anchor: String? = null
    private var current: String? = null
    private var before: Map<String, FileEntry> = emptyMap()

    /** Where the finger is, in the list's coordinates. */
    var position = Offset.Zero
        private set

    /** How fast the list scrolls along near its top or bottom, in pixels per second; negative is upwards. */
    var scrollSpeed by mutableFloatStateOf(0f)
        private set

    val isDragging get() = anchor != null

    /** A finger is on the list, e.g. still holding after a long press. */
    var isHolding by mutableStateOf(false)
        private set

    /** The finger moved on after the long press, so more entries may join; until it lifts, no card opens. */
    var hasMoved by mutableStateOf(false)
        private set

    /** [item] was long-pressed and is selected now, as are the entries of [selection]. */
    fun begin(item: FileEntry, selection: Map<String, FileEntry>) {
        // a long click without a finger on the list (TalkBack) selects, but there is nothing to drag
        if (!isHolding) return
        anchor = item.path
        current = item.path
        before = selection.toMap()
    }

    /**
     * The finger is over the entry at [path]: selects those of [order] from the long-pressed one up to it through
     * [select], along with what was selected before. Returns whether the selection changed.
     */
    fun moveTo(path: String, order: List<FileEntry>, select: (before: Map<String, FileEntry>, range: List<FileEntry>) -> Unit): Boolean {
        val anchor = anchor ?: return false
        if (path == current) return false
        val from = order.indexOfFirst { it.path == anchor }
        val to = order.indexOfFirst { it.path == path }
        if (from < 0 || to < 0) return false
        current = path
        select(before, order.subList(min(from, to), max(from, to) + 1))
        return true
    }

    internal fun onPointerDown() {
        isHolding = true
    }

    internal fun onMoved() {
        hasMoved = true
    }

    internal fun onMove(position: Offset, speed: Float) {
        this.position = position
        scrollSpeed = speed
    }

    internal fun onPointerUp() {
        isHolding = false
        hasMoved = false
        anchor = null
        current = null
        before = emptyMap()
        scrollSpeed = 0f
    }
}

/**
 * Lets [drag] select over the lazy list or grid this is set on: [order] are its entries as shown, [keyAt] gives the
 * key of the entry at a point of it, [state] scrolls it. [top] and [bottom] are covered by the header and the pill; near
 * them the list scrolls along. It watches before the list does, so once a long press started a drag, the list neither
 * scrolls nor pages, and lifting the finger taps nothing.
 */
@Composable
internal fun Modifier.dragSelection(
    drag: DragSelection,
    model: FileBrowserModel,
    order: List<FileEntry>,
    state: ScrollableState,
    keyAt: (Offset) -> Any?,
    top: Dp,
    bottom: Dp,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentOrder by rememberUpdatedState(order)
    val currentKeyAt by rememberUpdatedState(keyAt)
    val edges by rememberUpdatedState(with(density) { ScrollEdges(top.toPx(), bottom.toPx(), EDGE_ZONE.toPx(), MAX_SCROLL_SPEED.toPx()) })
    var height by remember { mutableIntStateOf(0) }

    fun follow(position: Offset) {
        drag.onMove(position, edges.speedAt(position.y, height))
        val path = currentKeyAt(position) as? String ?: return
        if (drag.moveTo(path, currentOrder, model::setSelection)) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }

    // near the header or the pill the list moves on by itself, and the entries passing below the finger join in
    LaunchedEffect(drag, state) {
        snapshotFlow { drag.scrollSpeed != 0f }.collectLatest { isScrolling ->
            if (!isScrolling) return@collectLatest
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                state.scrollBy(drag.scrollSpeed * (now - last) / NANOS_PER_SECOND)
                last = now
                follow(drag.position)
            }
        }
    }

    return onSizeChanged { height = it.height }.pointerInput(drag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            drag.onPointerDown()
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (drag.isDragging) {
                        event.changes.firstOrNull { it.id == down.id }?.let { change ->
                            if (!drag.hasMoved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop * MOVE_SLOPS) {
                                drag.onMoved()
                            }
                            if (change.positionChanged()) follow(change.position)
                        }
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            } finally {
                drag.onPointerUp()
            }
        }
    }
}

/** The key of the list entry at [position], a point of the list. */
internal fun LazyListState.keyAt(position: Offset): Any? {
    // item offsets count from where the content starts, below the top padding
    val y = position.y.roundToInt() + layoutInfo.viewportStartOffset
    return layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key
}

/** The key of the grid cell at [position], a point of the grid, which has [startPadding] pixels on its left. */
internal fun LazyGridState.keyAt(position: Offset, startPadding: Int): Any? {
    // cell offsets count from inside the paddings on both axes
    val point = IntOffset(position.x.roundToInt() - startPadding, position.y.roundToInt() + layoutInfo.viewportStartOffset)
    return layoutInfo.visibleItemsInfo.firstOrNull { IntRect(it.offset, it.size).contains(point) }?.key
}

/** Where scrolling along starts: a little before the parts covered at [top] and [bottom], full speed [zone] further. */
private class ScrollEdges(val top: Float, val bottom: Float, val zone: Float, val maxSpeed: Float) {
    fun speedAt(y: Float, height: Int): Float {
        val upFrom = top + zone / 3
        val downFrom = height - bottom - zone / 3
        return when {
            y < upFrom -> -maxSpeed * ((upFrom - y) / zone).coerceAtMost(1f)
            y > downFrom -> maxSpeed * ((y - downFrom) / zone).coerceAtMost(1f)
            else -> 0f
        }
    }
}

/** How many touch slops the finger goes after a long press before it counts as dragging on. */
private const val MOVE_SLOPS = 2

private val EDGE_ZONE = 72.dp
private val MAX_SCROLL_SPEED = 1200.dp
private const val NANOS_PER_SECOND = 1_000_000_000f
