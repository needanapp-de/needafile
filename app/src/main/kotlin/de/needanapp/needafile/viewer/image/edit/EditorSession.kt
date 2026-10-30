package de.needanapp.needafile.viewer.image.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.canhub.cropper.CropImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class EditorTool { CROP, DRAW }

/** Fixed proportions of the crop frame; [ORIGINAL] keeps the picture's own. */
internal enum class CropAspect(val x: Int, val y: Int) { FREE(0, 0), ORIGINAL(0, 0), SQUARE(1, 1), FOUR_THREE(4, 3), SIXTEEN_NINE(16, 9) }

/** A line drawn with the finger; points and width in the picture's own pixels. */
internal class DrawnLine(val color: Color, val width: Float) {
    val points = mutableStateListOf<Offset>()
}

/**
 * One editing session: the picture as it is after the steps done so far, and what the current tool is about to
 * change. Switching tools or saving applies the pending change ([commit]); the steps add up.
 */
@Stable
internal class EditorSession(val loaded: LoadedImage) {
    var working by mutableStateOf(loaded.bitmap)
        private set
    var tool by mutableStateOf(EditorTool.CROP)
    var hasChanges by mutableStateOf(false)
        private set
    var isBusy by mutableStateOf(false)

    // crop and rotate: the view keeps crop frame, rotation and flips until they are applied
    var cropView: CropImageView? = null
    var cropAspect by mutableStateOf(CropAspect.FREE)
    var cropTouched by mutableStateOf(false)

    // drawing
    val strokes = mutableStateListOf<DrawnLine>()
    var drawColor by mutableStateOf(Color(0xFFE53935))
    var brushSize by mutableFloatStateOf(DEFAULT_BRUSH_DP)

    val hasPending get() = cropTouched || strokes.isNotEmpty()
    val isChanged get() = hasChanges || hasPending

    /** Applies what the current tool has pending to [working]. */
    suspend fun commit() {
        if (!hasPending) return
        isBusy = true
        try {
            when {
                cropTouched -> applyCrop()
                strokes.isNotEmpty() -> applyStrokes()
            }
        } finally {
            isBusy = false
        }
    }

    private fun applyCrop() {
        val view = cropView
        cropTouched = false
        if (view == null) return
        val unchanged = view.cropRect == view.wholeImageRect && view.rotatedDegrees % 360 == 0 &&
            !view.isFlippedHorizontally && !view.isFlippedVertically
        if (unchanged) return
        val cropped = view.getCroppedImage() ?: return
        replace(cropped)
    }

    private suspend fun applyStrokes() {
        val drawn = strokes.toList()
        val source = working
        val result = withContext(Dispatchers.Default) {
            val copy = source.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(copy)
            drawn.forEach { stroke -> canvas.drawPath(stroke.toPath(), stroke.toPaint()) }
            copy
        }
        strokes.clear()
        replace(result)
    }

    fun resize(width: Int, height: Int) {
        replace(Bitmap.createScaledBitmap(working, width, height, true))
    }

    /** Back to the picture as it was opened. */
    fun reset() {
        strokes.clear()
        cropTouched = false
        working = loaded.bitmap
        hasChanges = false
    }

    private fun replace(bitmap: Bitmap) {
        working = bitmap
        hasChanges = true
    }

    private fun DrawnLine.toPath() = Path().apply {
        points.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
        // a single tap leaves a dot
        if (points.size == 1) lineTo(points[0].x + 0.1f, points[0].y)
    }

    private fun DrawnLine.toPaint() = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = width
        color = this@toPaint.color.toArgb()
    }

    companion object {
        const val DEFAULT_BRUSH_DP = 8f
    }
}
