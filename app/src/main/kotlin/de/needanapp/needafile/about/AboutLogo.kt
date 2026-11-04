package de.needanapp.needafile.about

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.needanapp.needafile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * The logo on the About page has a secret, as in Myzel and Hypha, with their timings and numbers: 26 taps, without
 * any sign that they count, let rainbows rain for 8 s (every 20th a unicorn) while a plaque turns where the logo was;
 * then the logo comes back, a banner floats in from the top, stays 4 s and floats out while the page fades and closes.
 * Leaving the page counts anew. The pictures are named meaninglessly on purpose: s4 the plaque, s2 the banner.
 */

private const val TAPS = 26

private const val SWAP_MS = 300
private const val RAIN_MS = 8_000L

/** From the end of the rain until its surface goes; long enough for the last drops to fall out. */
private const val RAIN_GONE_MS = 5_000L
private const val FLOAT_MS = 1_400
private const val STAY_MS = 4_000L
private const val TURN_MS = 2_400

/** Every [BATCH_MS] ms [BATCH] new drops; after a longer pause (screen off) the beat doesn't catch up. */
private const val BATCH_MS = 120L
private const val BATCH = 4
private const val CATCH_UP_MS = 500L
private const val RARE = 20

/** ease-in-out as in CSS, and the arc the banner floats on. */
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val Arc = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private val Rainbow = String(Character.toChars(0x1F308))
private val Unicorn = String(Character.toChars(0x1F984))

/** A drop's size as a share of [Rem], 0.7 to 2 (small ones more often); its picture has room around it. */
private val Rem = 16.dp
private const val MIN_SIZE = 0.7f
private const val SIZE_RANGE = 1.3f
private const val SPRITE_ROOM = 1.4f

/** How far drops swing sideways and how far they tilt doing so. */
private val Swing = 10.dp
private const val TILT = 8f

/** The plaque turns as seen from 600 dp away; the camera counts in inches of 72 pixels. */
private val Perspective = 600.dp
private const val PIXELS_PER_INCH = 72f

private val BannerHeight = 260.dp
private const val BANNER_WIDTH = 0.9f

/** Where the banner waits above its place. */
private val BannerFrom = 300.dp

/**
 * What the logo sets going; lives as long as the page and runs in its [scope], even when the logo scrolls out of
 * view. [onClose] closes the page at the end; without it the page fades back in.
 */
@Stable
internal class AboutLogoState(private val onClose: State<(() -> Unit)?>, private val scope: CoroutineScope) {
    private var taps = 0
    private var running = false

    /** The logo's opacity while it gives way to the plaque and comes back. */
    val shown = Animatable(1f)
    var plaque by mutableStateOf(false)
        private set

    var raining by mutableStateOf(false)
        private set

    /** Whether new drops come; read once per frame. */
    var pouring = false
        private set

    var bannerShown by mutableStateOf(false)
        private set

    /** 0 above its place, 1 in it. */
    val banner = Animatable(0f)
    val fade = Animatable(1f)

    /** Counts a tap and sets it going at the 26th, never while it runs. */
    fun tap() {
        if (running || ++taps < TAPS) return
        taps = 0
        running = true
        scope.launch { play() }
    }

    private suspend fun play() = coroutineScope {
        raining = true
        pouring = true
        launch { swap(toPlaque = true) }
        delay(RAIN_MS)
        pouring = false
        launch { swap(toPlaque = false) }
        launch {
            delay(RAIN_GONE_MS)
            raining = false
        }
        bannerShown = true
        banner.animateTo(1f, tween(FLOAT_MS, easing = Arc))
        delay(STAY_MS)
        launch { banner.animateTo(0f, tween(FLOAT_MS, easing = Arc)) }
        fade.animateTo(0f, tween(FLOAT_MS, easing = EaseInOut))
        val close = onClose.value
        if (close != null) {
            close()
        } else {
            bannerShown = false
            fade.animateTo(1f, tween(FLOAT_MS, easing = EaseInOut))
            running = false
        }
    }

    private suspend fun swap(toPlaque: Boolean) {
        shown.animateTo(0f, tween(SWAP_MS, easing = EaseInOut))
        plaque = toPlaque
        shown.animateTo(1f, tween(SWAP_MS, easing = EaseInOut))
    }
}

/** The About page with rain and banner over it; all of it fades with [AboutLogoState.fade]. [content] gets the state for [AboutLogo]. */
@Composable
internal fun AboutLogoFrame(onClose: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable (AboutLogoState) -> Unit) {
    val close = rememberUpdatedState(onClose)
    val scope = rememberCoroutineScope()
    val state = remember { AboutLogoState(close, scope) }
    Box(modifier.graphicsLayer { alpha = state.fade.value }) {
        content(state)
        if (state.raining) Rain(state, Modifier.matchParentSize())
        if (state.bannerShown) Banner(state)
    }
}

/** The logo in [size]; taps on it count without any trace, no ripple, nothing to read out. [logo] is the logo as the page shows it anyway. */
@Composable
internal fun AboutLogo(state: AboutLogoState, size: Dp, modifier: Modifier = Modifier, logo: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .size(size)
            .graphicsLayer { alpha = state.shown.value }
            .pointerInput(state) { detectTapGestures { state.tap() } },
        contentAlignment = Alignment.Center,
    ) {
        if (state.plaque) Plaque() else logo()
    }
}

/** The plaque where the logo was, turning around its vertical axis, with a soft shadow. */
@Composable
private fun Plaque() {
    val turn = rememberInfiniteTransition(label = "plaque").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(TURN_MS, easing = LinearEasing)),
        label = "turn",
    )
    val picture = painterResource(R.drawable.s4)
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            rotationY = turn.value
            cameraDistance = Perspective.toPx() / PIXELS_PER_INCH
        },
    ) {
        Image(
            picture,
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.Black.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxSize().offset(y = 6.dp).blur(8.dp, BlurredEdgeTreatment.Unbounded),
        )
        Image(picture, contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/** A drop: where across (share of the width), how large, when it began, how long it falls, how it swings. */
private class Drop(
    val x: Float,
    val size: Float,
    val start: Long,
    val duration: Float,
    val swing: Float,
    val phase: Float,
    val sprite: ImageBitmap,
)

/**
 * Falling rainbows on a surface, each drawn once as a picture and then only copied: about 150 fall at once. The frame
 * loop adds and removes them, drawing only reads.
 */
@Composable
private fun Rain(state: AboutLogoState, modifier: Modifier) {
    val rem = with(LocalDensity.current) { Rem.toPx() }
    val rainbow = remember(rem) { sprite(Rainbow, rem) }
    val unicorn = remember(rem) { sprite(Unicorn, rem) }
    val drops = remember { ArrayList<Drop>() }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(rem) {
        var next = -1L
        var count = 0
        while (true) {
            val time = withFrameMillis { it }
            if (next < 0 || time - next > CATCH_UP_MS) next = time
            while (state.pouring && time >= next) {
                repeat(BATCH) {
                    count++
                    drops += Drop(
                        x = Random.nextFloat(),
                        size = (MIN_SIZE + Random.nextFloat() * Random.nextFloat() * SIZE_RANGE) * rem,
                        start = next,
                        duration = 2_000f + Random.nextFloat() * 2_500f,
                        swing = 800f + Random.nextFloat() * 800f,
                        phase = Random.nextFloat() * 2_000f,
                        // exactly every 20th, not just on average
                        sprite = if (count % RARE == 0) unicorn else rainbow,
                    )
                }
                next += BATCH_MS
            }
            drops.removeAll { time - it.start >= it.duration }
            now = time
        }
    }
    Canvas(modifier) {
        val time = now
        val swing = Swing.toPx()
        for (drop in drops) {
            val part = ((time - drop.start) / drop.duration).coerceAtLeast(0f)
            val wave = -cos(2 * PI * ((time - drop.start + drop.phase) / drop.swing)).toFloat()
            val x = drop.x * size.width + drop.size / 2 + wave * swing
            val y = (-0.1f + 1.2f * part) * size.height + drop.size / 2
            val side = drop.size * SPRITE_ROOM
            rotate(wave * TILT, pivot = Offset(x, y)) {
                translate(x - side / 2, y - side / 2) {
                    drawImage(drop.sprite, dstSize = IntSize(side.roundToInt(), side.roundToInt()))
                }
            }
        }
    }
}

/** [text] once in the largest drop size, with room for emoji that reach beyond the font size. */
private fun sprite(text: String, rem: Float): ImageBitmap {
    val side = ceil((MIN_SIZE + SIZE_RANGE) * rem * SPRITE_ROOM).toInt()
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = side / SPRITE_ROOM
        textAlign = Paint.Align.CENTER
    }
    val metrics = paint.fontMetrics
    android.graphics.Canvas(bitmap).drawText(text, side / 2f, side / 2f - (metrics.ascent + metrics.descent) / 2, paint)
    return bitmap.asImageBitmap()
}

/** The banner at the top below the status bar, 260 dp high and at most 90 % wide, floats in and out. */
@Composable
private fun BoxScope.Banner(state: AboutLogoState) {
    val picture = painterResource(R.drawable.s2)
    val ratio = picture.intrinsicSize.width / picture.intrinsicSize.height
    BoxWithConstraints(Modifier.matchParentSize().windowInsetsPadding(WindowInsets.statusBars)) {
        val width = minOf(BannerHeight * ratio, maxWidth * BANNER_WIDTH)
        Image(
            picture,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, ((state.banner.value - 1f) * BannerFrom.toPx()).roundToInt()) }
                .size(width, width / ratio),
        )
    }
}
