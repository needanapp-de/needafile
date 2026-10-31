package de.needanapp.needafile.viewer.image.edit

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import de.needanapp.design.components.ColorPicker
import de.needanapp.design.components.ColorSwatch
import de.needanapp.design.components.ContentDialog
import de.needanapp.design.components.signalSliderColors
import de.needanapp.needafile.R
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** The crop frame over the picture (Android Image Cropper). */
@Composable
internal fun CropArea(session: EditorSession) {
    val colors = MaterialTheme.colorScheme
    val working = session.working
    AndroidView(
        factory = { context ->
            CropImageView(context).apply {
                setImageCropOptions(
                    CropImageOptions().apply {
                        borderLineColor = colors.primary.toArgb()
                        borderCornerColor = colors.primary.toArgb()
                        guidelinesColor = colors.primary.copy(alpha = GUIDELINES_ALPHA).toArgb()
                        backgroundColor = colors.scrim.copy(alpha = OVERLAY_ALPHA).toArgb()
                        guidelines = CropImageView.Guidelines.ON_TOUCH
                    },
                )
                // only what the user does counts as a change, not the frame's first layout
                setOnSetCropOverlayReleasedListener { session.cropTouched = true }
                session.cropView = this
            }
        },
        update = { view ->
            if (view.tag !== working) {
                view.tag = working
                view.setImageBitmap(working)
                applyAspect(view, session.cropAspect, working)
            }
        },
        onRelease = { view -> if (session.cropView === view) session.cropView = null },
        modifier = Modifier.fillMaxSize().padding(16.dp),
    )
}

private fun applyAspect(view: CropImageView, aspect: CropAspect, bitmap: Bitmap) {
    when (aspect) {
        CropAspect.FREE -> {
            view.clearAspectRatio()
            view.setFixedAspectRatio(false)
        }
        CropAspect.ORIGINAL -> view.setAspectRatio(bitmap.width, bitmap.height)
        else -> view.setAspectRatio(aspect.x, aspect.y)
    }
}

/** The picture with the lines drawn so far; drawing with one finger, a tap leaves a dot. */
@Composable
internal fun DrawArea(session: EditorSession) {
    val bitmap = session.working
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        // where the fitted picture lies; lines are kept in the picture's own pixels
        val scale = min(constraints.maxWidth.toFloat() / bitmap.width, constraints.maxHeight.toFloat() / bitmap.height)
        val width = with(LocalDensity.current) { (bitmap.width * scale).toDp() }
        val height = with(LocalDensity.current) { (bitmap.height * scale).toDp() }
        fun newLine(at: Offset) = DrawnLine(session.drawColor, session.brushSize * density / scale).also {
            it.points.add(at / scale)
            session.strokes.add(it)
        }
        Box(
            Modifier
                .size(width, height)
                .pointerInput(bitmap, scale) {
                    detectDragGestures(
                        onDragStart = { newLine(it) },
                        onDrag = { change, _ -> session.strokes.lastOrNull()?.points?.add(change.position / scale) },
                    )
                }
                .pointerInput(bitmap, scale) { detectTapGestures(onTap = { newLine(it) }) },
        ) {
            Image(imageBitmap, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                session.strokes.forEach { line ->
                    val path = Path()
                    line.points.forEachIndexed { index, point ->
                        if (index == 0) path.moveTo(point.x * scale, point.y * scale) else path.lineTo(point.x * scale, point.y * scale)
                    }
                    if (line.points.size == 1) path.lineTo(line.points[0].x * scale + 0.1f, line.points[0].y * scale)
                    drawPath(path, line.color, style = Stroke(width = line.width * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/** The current tool's options below the picture. */
@Composable
internal fun ToolPanel(session: EditorSession) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        when (session.tool) {
            EditorTool.CROP -> CropOptions(session)
            EditorTool.DRAW -> DrawOptions(session)
        }
    }
}

@Composable
private fun CropOptions(session: EditorSession) {
    val scope = rememberCoroutineScope()
    var resizing by remember { mutableStateOf(false) }
    fun onView(action: (CropImageView) -> Unit) {
        session.cropView?.let(action)
        session.cropTouched = true
    }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CropAspect.entries.forEach { aspect ->
                val label = when (aspect) {
                    CropAspect.FREE -> stringResource(R.string.image_editor_aspect_free)
                    CropAspect.ORIGINAL -> stringResource(R.string.image_editor_aspect_original)
                    else -> stringResource(R.string.image_editor_aspect_ratio, aspect.x, aspect.y)
                }
                FilterChip(
                    selected = session.cropAspect == aspect,
                    onClick = {
                        session.cropAspect = aspect
                        onView { applyAspect(it, aspect, session.working) }
                    },
                    label = { Text(label) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { onView { it.rotateImage(-RIGHT_ANGLE) } }) {
                Icon(Icons.AutoMirrored.Outlined.RotateLeft, stringResource(R.string.image_editor_rotate_left))
            }
            IconButton(onClick = { onView { it.rotateImage(RIGHT_ANGLE) } }) {
                Icon(Icons.AutoMirrored.Outlined.RotateRight, stringResource(R.string.image_editor_rotate_right))
            }
            IconButton(onClick = { onView { it.flipImageHorizontally() } }) {
                Icon(Icons.Outlined.Flip, stringResource(R.string.image_editor_flip_horizontal))
            }
            IconButton(onClick = { onView { it.flipImageVertically() } }) {
                Icon(Icons.Outlined.Flip, stringResource(R.string.image_editor_flip_vertical), Modifier.rotate(RIGHT_ANGLE.toFloat()))
            }
            IconButton(onClick = {
                // resizing works on the picture as cropped so far
                scope.launch {
                    session.commit()
                    resizing = true
                }
            }) {
                Icon(Icons.Outlined.PhotoSizeSelectLarge, stringResource(R.string.image_editor_resize))
            }
        }
    }
    if (resizing) {
        ResizeDialog(session.working.width, session.working.height, onDismiss = { resizing = false }) { width, height ->
            resizing = false
            session.resize(width, height)
        }
    }
}

/** Width and height in pixels, linked to keep the picture's proportions. */
@Composable
private fun ResizeDialog(width: Int, height: Int, onDismiss: () -> Unit, onResize: (Int, Int) -> Unit) {
    var widthText by remember { mutableStateOf(width.toString()) }
    var heightText by remember { mutableStateOf(height.toString()) }
    // the pixels must fit into memory: at most about 36 megapixels
    val fits = (widthText.toLongOrNull() ?: 0) * (heightText.toLongOrNull() ?: 0) <= MAX_PIXELS
    val newWidth = widthText.toIntOrNull()?.takeIf { it > 0 && fits }
    val newHeight = heightText.toIntOrNull()?.takeIf { it > 0 && fits }
    ContentDialog(
        title = stringResource(R.string.image_editor_resize),
        confirmLabel = stringResource(R.string.ok),
        onConfirm = { if (newWidth != null && newHeight != null) onResize(newWidth, newHeight) },
        onDismiss = onDismiss,
        confirmEnabled = newWidth != null && newHeight != null,
        icon = Icons.Outlined.PhotoSizeSelectLarge,
    ) {
        OutlinedTextField(
            value = widthText,
            onValueChange = { text ->
                widthText = text.filter(Char::isDigit).take(DIGITS)
                widthText.toIntOrNull()?.let { heightText = (it.toFloat() * height / width).roundToInt().coerceAtLeast(1).toString() }
            },
            label = { Text(stringResource(R.string.image_editor_width)) },
            isError = newWidth == null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = heightText,
            onValueChange = { text ->
                heightText = text.filter(Char::isDigit).take(DIGITS)
                heightText.toIntOrNull()?.let { widthText = (it.toFloat() * width / height).roundToInt().coerceAtLeast(1).toString() }
            },
            label = { Text(stringResource(R.string.image_editor_height)) },
            isError = newHeight == null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun DrawOptions(session: EditorSession) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DRAW_COLORS.forEachIndexed { index, color ->
                ColorSwatch(
                    color = color,
                    description = stringResource(R.string.image_editor_color, index + 1),
                    selected = session.drawColor == color,
                    onClick = { session.drawColor = color },
                    size = SWATCH_SIZE.dp,
                )
            }
            IconButton(onClick = { picking = true }) { Icon(Icons.Outlined.Colorize, stringResource(R.string.image_editor_custom_color)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Brush, stringResource(R.string.image_editor_brush_size), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = session.brushSize,
                onValueChange = { session.brushSize = it },
                valueRange = MIN_BRUSH..MAX_BRUSH,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                colors = signalSliderColors(),
            )
            IconButton(onClick = { session.strokes.removeAt(session.strokes.lastIndex) }, enabled = session.strokes.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.image_editor_undo_line))
            }
        }
    }
    if (picking) {
        var picked by remember { mutableStateOf(session.drawColor) }
        ContentDialog(
            title = stringResource(R.string.image_editor_custom_color),
            confirmLabel = stringResource(R.string.ok),
            icon = Icons.Outlined.Palette,
            onConfirm = {
                session.drawColor = picked
                picking = false
            },
            onDismiss = { picking = false },
        ) {
            ColorPicker(initial = session.drawColor, onPick = { picked = it })
        }
    }
}

private val DRAW_COLORS = listOf(
    Color.White, Color.Black, Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
)
private const val RIGHT_ANGLE = 90
private const val GUIDELINES_ALPHA = 0.6f
private const val OVERLAY_ALPHA = 0.55f
private const val MAX_PIXELS = 36_000_000L
private const val DIGITS = 5
private const val SWATCH_SIZE = 36
private const val MIN_BRUSH = 2f
private const val MAX_BRUSH = 40f
