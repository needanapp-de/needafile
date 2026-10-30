package de.needanapp.needafile.security

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.needanapp.needafile.R

/** Digits in a 3 × 4 grid with "delete last digit" and "confirm"; [pin] above as dots. */
@Composable
fun PinPad(pin: String, onDigit: (Char) -> Unit, onBackspace: () -> Unit, onConfirm: () -> Unit, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    fun press(action: () -> Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        action()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
            Text("●".repeat(pin.length), style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp))
        }
        listOf("123", "456", "789").forEach { digits ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                digits.forEach { digit -> PadKey(enabled, onClick = { press { onDigit(digit) } }) { Text(digit.toString(), style = MaterialTheme.typography.headlineSmall) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PadKey(enabled && pin.isNotEmpty(), onClick = { press(onBackspace) }) {
                Icon(Icons.AutoMirrored.Outlined.Backspace, stringResource(R.string.pin_backspace))
            }
            PadKey(enabled, onClick = { press { onDigit('0') } }) { Text("0", style = MaterialTheme.typography.headlineSmall) }
            PadKey(enabled && pin.isNotEmpty(), onClick = { press(onConfirm) }, isPrimary = true) {
                Icon(Icons.Outlined.Check, stringResource(R.string.ok))
            }
        }
    }
}

@Composable
private fun PadKey(enabled: Boolean, onClick: () -> Unit, isPrimary: Boolean = false, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (isPrimary) colors.primaryContainer else colors.surfaceContainerHighest,
        contentColor = if (isPrimary) colors.onPrimaryContainer else colors.onSurface,
        modifier = Modifier.size(KEY_SIZE.dp).alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * Nine dots to connect with one stroke; [onComplete] gets the dots in order, numbered row by row from 0. Passing over a
 * dot between two others takes it along, like the system's pattern lock. [isWrong] paints the last pattern red;
 * changing [resetKey] clears it.
 */
@Composable
fun PatternPad(onComplete: (List<Int>) -> Unit, resetKey: Int, isWrong: Boolean = false, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val selected = remember(resetKey) { mutableStateListOf<Int>() }
    var pointer by remember(resetKey) { mutableStateOf<Offset?>(null) }
    val complete by rememberUpdatedState(onComplete)
    val description = stringResource(R.string.pattern_area)

    Canvas(
        Modifier
            .widthIn(max = PATTERN_MAX_SIZE.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = description }
            .pointerInput(enabled, resetKey) {
                if (!enabled) return@pointerInput
                val cell = size.width / GRID.toFloat()
                fun center(dot: Int) = Offset(cell * (dot % GRID + 0.5f), cell * (dot / GRID + 0.5f))
                fun select(dot: Int) {
                    if (dot in selected) return
                    selected.lastOrNull()?.let { last ->
                        // the dot in the middle of a straight jump is part of the pattern
                        val rowSum = last / GRID + dot / GRID
                        val columnSum = last % GRID + dot % GRID
                        val middle = (rowSum / 2) * GRID + columnSum / 2
                        if (rowSum % 2 == 0 && columnSum % 2 == 0 && middle !in selected) selected.add(middle)
                    }
                    selected.add(dot)
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                fun hit(position: Offset) {
                    (0 until GRID * GRID).firstOrNull { (center(it) - position).getDistance() < cell * HIT_RADIUS }?.let(::select)
                }

                awaitEachGesture {
                    val down = awaitFirstDown()
                    selected.clear()
                    hit(down.position)
                    pointer = down.position
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        pointer = change.position
                        hit(change.position)
                        change.consume()
                    }
                    pointer = null
                    if (selected.isNotEmpty()) complete(selected.toList())
                }
            },
    ) {
        val cell = size.width / GRID
        fun center(dot: Int) = Offset(cell * (dot % GRID + 0.5f), cell * (dot / GRID + 0.5f))
        val active = if (isWrong) colors.error else colors.primary
        val stroke = LINE_WIDTH.dp.toPx()
        selected.zipWithNext().forEach { (from, to) ->
            drawLine(active.copy(alpha = LINE_ALPHA), center(from), center(to), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        val last = selected.lastOrNull()
        val current = pointer
        if (last != null && current != null) {
            drawLine(active.copy(alpha = LINE_ALPHA), center(last), current, strokeWidth = stroke, cap = StrokeCap.Round)
        }
        for (dot in 0 until GRID * GRID) {
            val isSelected = dot in selected
            drawCircle(
                if (isSelected) active else colors.onSurfaceVariant,
                radius = (if (isSelected) SELECTED_DOT else DOT).dp.toPx(),
                center = center(dot),
            )
        }
    }
}

private const val KEY_SIZE = 72
private const val DISABLED_ALPHA = 0.38f
private const val GRID = 3
private const val HIT_RADIUS = 0.3f
private const val PATTERN_MAX_SIZE = 280
private const val LINE_WIDTH = 6
private const val LINE_ALPHA = 0.6f
private const val DOT = 6
private const val SELECTED_DOT = 11
