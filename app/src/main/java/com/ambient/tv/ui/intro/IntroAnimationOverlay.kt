package com.ambient.tv.ui.intro

import androidx.compose.animation.core.Animatable
import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.IntroConnectionStatus
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.workingConnectivity
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.performance.PerformanceMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import com.ambient.tv.data.local.IntroAnimation
import com.ambient.tv.ui.theme.LocalPlaybackAccent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Settings preview: replays the given style over the whole app. */
object IntroPreviewRequests {
    val requests = MutableSharedFlow<String>(extraBufferCapacity = 1)
}

/**
 * While the intro is up it owns the screen: notification cards and the unreachable-server dialog
 * wait, and the activity routes keys here (any key skips) instead of to the hidden UI.
 */
object IntroPresentation {
    var active by mutableStateOf(false)
        private set
    internal var skipRequests by mutableIntStateOf(0)
        private set

    fun skip() { skipRequests++ }
    internal fun setActive(value: Boolean) { active = value }
}

/** Strokes of ic_ambient_tv in its 48×48 viewport; the stand is split so each piece is one contour. */
private class LogoPaths {
    val frame = parse("M10 8H38A4 4 0 0 1 42 12V28A4 4 0 0 1 38 32H10A4 4 0 0 1 6 28V12A4 4 0 0 1 10 8Z")
    val peak = parse("M15 28L24 13L33 28")
    val neck = parse("M24 34.5V37")
    val foot = parse("M16 40H32")
    private val measure = PathMeasure()

    fun trimmed(path: Path, fraction: Float): Path? = when {
        fraction <= 0f -> null
        fraction >= 1f -> path
        else -> Path().also { out ->
            measure.setPath(path, false)
            measure.getSegment(0f, measure.length * fraction, out, true)
        }
    }

    private fun parse(data: String) = PathParser().parsePathString(data).toPath()
}

private const val VIEWPORT = 48f
private const val FRAME_STROKE = 3.2f
private const val PEAK_STROKE = 3.6f

private fun phase(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)
private fun ease(x: Float) = FastOutSlowInEasing.transform(x)

/**
 * Full-screen intro in the current accent (and playback accent when a pair is set). The app
 * composes underneath. The background stays flat; only the mark moves, in one looping motion for
 * as long as the intro is up, holding on a running wake/connect with that card in place of the
 * regular one. Any key skips, and it always leaves with a plain fade.
 */
@Composable
fun IntroAnimationOverlay(styleKey: String, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val style = IntroAnimation.normalize(styleKey)
    val finished by rememberUpdatedState(onFinished)
    val accent = MaterialTheme.colorScheme.primary
    val playback = LocalPlaybackAccent.current.indicator
    // Single-accent themes still get a second tone, a lighter cut of the same color.
    val second = if (playback == accent) lerp(accent, Color.White, 0.45f) else playback
    val background = MaterialTheme.colorScheme.background
    val profile = LocalPerformanceProfile.current
    // Sparsam: the finished mark without motion; reduced effects: motion without glow layers.
    val still = profile.mode == PerformanceMode.LOW
    val lite = profile.reducedEffects
    // Seconds since the intro appeared; read only in the draw phase, so the loop never recomposes.
    val clock = remember { mutableFloatStateOf(0f) }
    val exit = remember { Animatable(1f) }
    val statusAlpha = remember { Animatable(0f) }
    val logo = remember { LogoPaths() }
    var holding by remember { mutableStateOf(false) }
    val snapshot by AppNotifications.store.state.collectAsState()
    val status = workingConnectivity(snapshot)
    DisposableEffect(Unit) {
        IntroPresentation.setActive(true)
        onDispose { IntroPresentation.setActive(false) }
    }
    // Taken at composition: a key pressed before the effect starts still skips.
    val skipBaseline = remember { IntroPresentation.skipRequests }
    LaunchedEffect(style) {
        if (style == IntroAnimation.OFF) { finished(); return@LaunchedEffect }
        var skipped = false
        val ticker = if (still) null else launch {
            val startNanos = withFrameNanos { it }
            while (true) withFrameNanos { clock.floatValue = (it - startNanos) / 1_000_000_000f }
        }
        val run = launch {
            delay(MIN_VISIBLE_MS)
            holdWhileConnecting { holding = it }
        }
        val skipWatch = launch {
            snapshotFlow { IntroPresentation.skipRequests }.first { it != skipBaseline }
            skipped = true
            run.cancel()
        }
        run.join()
        skipWatch.cancel()
        // The loop keeps running under the fade.
        exit.animateTo(0f, tween(if (skipped || still) 220 else 480, easing = LinearEasing))
        ticker?.cancel()
        finished()
    }
    LaunchedEffect(holding) {
        if (holding) statusAlpha.animateTo(1f, tween(if (lite) 0 else 280))
    }
    if (style == IntroAnimation.OFF) return
    val cycle = IntroAnimation.durationMs(style) / 1000f
    Box(
        modifier.fillMaxSize()
            .graphicsLayer { alpha = exit.value }
            .pointerInput(Unit) { detectTapGestures { IntroPresentation.skip() } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            IntroDraw.lite = lite
            drawRect(background)
            val seconds = clock.floatValue
            if (still) {
                inLogo { drawLogo(logo, 1f, 1f, 1f, accent) }
                return@Canvas
            }
            // A short fade-in so the loop never pops in mid-motion.
            val enter = ease(phase(seconds, 0f, 0.5f))
            val t = (seconds % cycle) / cycle
            when (style) {
                IntroAnimation.POWER_ON -> drawPowerOn(t, enter, logo, accent, second)
                IntroAnimation.SIGNAL -> drawSignal(t, enter, logo, accent, second)
                IntroAnimation.SHIMMER -> drawShimmer(t, enter, logo, accent, second)
                IntroAnimation.RIPPLE -> drawRipple(t, enter, logo, accent, second)
                else -> drawBloom(t, enter, logo, accent, second)
            }
        }
        if (holding) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 56.dp)
                    .graphicsLayer { alpha = statusAlpha.value },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IntroConnectionStatus(status, Modifier.widthIn(max = 440.dp))
                Text(
                    stringResource(R.string.intro_skip_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Waits while connectivity work is running. A wake holds for its own budget (the user can always
 * skip); a plain connect only briefly, so a slow server never keeps the app behind the intro.
 */
private suspend fun holdWhileConnecting(onHolding: (Boolean) -> Unit) {
    val started = SystemClock.uptimeMillis()
    withTimeoutOrNull(MAX_HOLD_MS) {
        while (true) {
            val working = workingConnectivity(AppNotifications.store.state.value)
            if (working.isEmpty()) break
            val waking = working.any { it.notice.kind == NoticeKind.WAKE }
            if (!waking && SystemClock.uptimeMillis() - started > CONNECT_HOLD_MS) break
            onHolding(true)
            delay(100)
        }
    }
}

private const val MIN_VISIBLE_MS = 5_000L
private const val MAX_HOLD_MS = 3 * 60_000L
private const val CONNECT_HOLD_MS = 6_000L

/** Per-frame draw flag; the intro draws on the main thread only. */
private object IntroDraw {
    var lite = false
}

/** Runs [block] in logo viewport units, centered, about a quarter of the short screen side. */
private fun DrawScope.inLogo(scale: Float = 1f, dx: Float = 0f, block: DrawScope.() -> Unit) {
    val side = size.minDimension * 0.28f * scale
    val unit = side / VIEWPORT
    withTransform({
        translate(center.x - side / 2f + dx * unit, center.y - side / 2f)
        scale(unit, unit, pivot = Offset.Zero)
    }, block)
}

private fun DrawScope.stroke(path: Path?, color: Color, width: Float, glow: Boolean = false) {
    path ?: return
    if (glow && !IntroDraw.lite) {
        drawPath(path, color.copy(alpha = color.alpha * 0.16f), style = Stroke(width * 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, color.copy(alpha = color.alpha * 0.28f), style = Stroke(width * 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.strokeBrush(path: Path, brush: Brush, width: Float) =
    drawPath(path, brush, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))

private fun DrawScope.drawLogo(logo: LogoPaths, frame: Float, peak: Float, stand: Float, color: Color, glow: Boolean = true) {
    stroke(logo.trimmed(logo.frame, frame), color, FRAME_STROKE, glow)
    stroke(logo.trimmed(logo.peak, peak), color, PEAK_STROKE, glow)
    stroke(logo.trimmed(logo.neck, phase(stand, 0f, 0.35f)), color, FRAME_STROKE, glow)
    stroke(logo.trimmed(logo.foot, phase(stand, 0.25f, 1f)), color, FRAME_STROKE, glow)
}

/** Breathing curve over one loop: 0 at the loop seam, 1 halfway. */
private fun wave(t: Float) = (1f - cos(t * 2f * PI.toFloat())) / 2f

/** Tube TV: the mark stands lit while a bright scanline runs down inside the screen. */
private fun DrawScope.drawPowerOn(t: Float, enter: Float, logo: LogoPaths, accent: Color, second: Color) {
    inLogo {
        drawLogo(logo, 1f, 1f, 1f, accent.copy(alpha = enter))
        // Inside the frame (10..30 in viewport units), clipped to the screen area.
        val y = 10f + 20f * ease(t)
        val fade = sin(t * PI.toFloat())
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, lerp(second, Color.White, 0.5f).copy(alpha = 0.55f * fade * enter), Color.Transparent),
                startY = y - 2.5f, endY = y + 2.5f,
            ),
            topLeft = Offset(8f, y - 2.5f), size = Size(32f, 5f),
        )
    }
}

/** Ambient glow: the mark's own glow swells and settles, like a backlight breathing. */
private fun DrawScope.drawBloom(t: Float, enter: Float, logo: LogoPaths, accent: Color, second: Color) {
    val b = wave(t)
    inLogo {
        if (!IntroDraw.lite) listOf(logo.frame to FRAME_STROKE, logo.peak to PEAK_STROKE, logo.neck to FRAME_STROKE, logo.foot to FRAME_STROKE).forEach { (path, width) ->
            drawPath(path, lerp(accent, second, b).copy(alpha = (0.10f + 0.22f * b) * enter), style = Stroke(width * (2.2f + 2.4f * b), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawLogo(logo, 1f, 1f, 1f, accent.copy(alpha = enter), glow = false)
    }
}

/** Searching: the frame stands, the peak traces itself up and over again and again. */
private fun DrawScope.drawSignal(t: Float, enter: Float, logo: LogoPaths, accent: Color, second: Color) {
    inLogo {
        stroke(logo.frame, accent.copy(alpha = enter), FRAME_STROKE)
        stroke(logo.neck, accent.copy(alpha = enter), FRAME_STROKE)
        stroke(logo.foot, accent.copy(alpha = enter), FRAME_STROKE)
        stroke(logo.peak, accent.copy(alpha = 0.18f * enter), PEAK_STROKE)
        val draw = ease(phase(t, 0f, 0.6f))
        val fade = 1f - phase(t, 0.75f, 1f)
        stroke(logo.trimmed(logo.peak, draw), lerp(accent, second, 0.35f).copy(alpha = fade * enter), PEAK_STROKE, glow = true)
    }
}

/** A light streak sweeps across the mark, again and again. */
private fun DrawScope.drawShimmer(t: Float, enter: Float, logo: LogoPaths, accent: Color, second: Color) {
    val pos = -0.3f + 1.6f * ease(phase(t, 0f, 0.7f))
    val base = accent.copy(alpha = enter)
    val highlight = lerp(second, Color.White, 0.6f).copy(alpha = enter)
    val brush = Brush.linearGradient(
        0f to base,
        (pos - 0.12f).coerceIn(0f, 1f) to base,
        pos.coerceIn(0f, 1f) to highlight,
        (pos + 0.12f).coerceIn(0f, 1f) to base,
        1f to base,
        start = Offset(4f, 4f), end = Offset(44f, 44f),
    )
    inLogo {
        strokeBrush(logo.frame, brush, FRAME_STROKE)
        strokeBrush(logo.peak, brush, PEAK_STROKE)
        strokeBrush(logo.neck, brush, FRAME_STROKE)
        strokeBrush(logo.foot, brush, FRAME_STROKE)
    }
}

/** Broadcast: an echo of the frame keeps leaving the mark and fading just around it. */
private fun DrawScope.drawRipple(t: Float, enter: Float, logo: LogoPaths, accent: Color, second: Color) {
    inLogo {
        val grow = 1f + 0.35f * ease(t)
        withTransform({ scale(grow, grow, pivot = Offset(24f, 20f)) }) {
            drawPath(logo.frame, second.copy(alpha = 0.5f * (1f - t) * enter), style = Stroke(FRAME_STROKE / grow, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawLogo(logo, 1f, 1f, 1f, accent.copy(alpha = enter))
    }
}
