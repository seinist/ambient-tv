package com.ambient.tv.ui.intro

import androidx.compose.animation.core.Animatable
import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.geometry.CornerRadius
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
import kotlin.random.Random

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
 * composes underneath. After the animation it holds on a running wake/connect and shows that
 * card in place of the regular one; any key skips, and the app continues exactly as without it.
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
    // Sparsam: the finished mark without motion; reduced effects: motion without glow/noise layers.
    val still = profile.mode == PerformanceMode.LOW
    val lite = profile.reducedEffects
    val progress = remember { Animatable(0f) }
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
        val run = launch {
            if (still) progress.snapTo(1f)
            else progress.animateTo(1f, tween(IntroAnimation.durationMs(style), easing = LinearEasing))
            holdWhileConnecting { holding = it }
        }
        val skipWatch = launch {
            snapshotFlow { IntroPresentation.skipRequests }.first { it != skipBaseline }
            skipped = true
            run.cancel()
        }
        run.join()
        skipWatch.cancel()
        exit.animateTo(0f, tween(if (skipped || still) 180 else 320, easing = FastOutSlowInEasing))
        finished()
    }
    LaunchedEffect(holding) {
        if (holding) statusAlpha.animateTo(1f, tween(if (lite) 0 else 280))
    }
    if (style == IntroAnimation.OFF) return
    // Read only in the draw phase, so the pulse redraws the canvas without recomposing the card.
    val breath = if (holding && !lite) {
        rememberInfiniteTransition(label = "introHold").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath",
        )
    } else null
    Box(
        modifier.fillMaxSize()
            .graphicsLayer {
                alpha = exit.value
                val grow = if (still) 1f else 1f + (1f - exit.value) * 0.06f
                scaleX = grow
                scaleY = grow
            }
            .pointerInput(Unit) { detectTapGestures { IntroPresentation.skip() } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            IntroDraw.lite = lite
            drawRect(background)
            if (breath != null) {
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.08f + 0.12f * breath.value), Color.Transparent), center, size.minDimension * 0.42f),
                    size.minDimension * 0.42f, center,
                )
            }
            val t = progress.value
            when (style) {
                IntroAnimation.POWER_ON -> drawPowerOn(t, logo, accent, second)
                IntroAnimation.SIGNAL -> drawSignal(t, logo, accent, second)
                IntroAnimation.SHIMMER -> drawShimmer(t, logo, accent, second)
                IntroAnimation.RIPPLE -> drawRipple(t, logo, accent, second)
                else -> drawBloom(t, logo, accent, second)
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

/** Tube TV switching on: a bright line, opening into the frame, then the mark and a flash. */
private fun DrawScope.drawPowerOn(t: Float, logo: LogoPaths, accent: Color, second: Color) {
    val line = ease(phase(t, 0f, 0.25f))
    val open = ease(phase(t, 0.25f, 0.55f))
    val flash = phase(t, 0.78f, 1f)
    if (flash > 0f) {
        drawRect(Brush.radialGradient(
            listOf(accent.copy(alpha = 0.32f * (1f - flash)), Color.Transparent),
            center = center,
            radius = size.maxDimension * (0.3f + 0.5f * flash),
        ))
    }
    inLogo {
        val hot = lerp(Color.White, accent, open)
        if (open <= 0f) {
            val half = 18f * line
            val path = Path().apply { moveTo(24f - half, 20f); lineTo(24f + half, 20f) }
            stroke(path, hot, FRAME_STROKE, glow = true)
        } else {
            val height = 24f * open
            val top = 20f - height / 2f
            drawRoundRect(
                color = second.copy(alpha = 0.18f * (1f - phase(t, 0.55f, 0.8f))),
                topLeft = Offset(6f, top),
                size = Size(36f, height),
                cornerRadius = CornerRadius(4f * open),
            )
            if (open < 1f) {
                drawRoundRect(hot.copy(alpha = 0.28f), Offset(6f, top), Size(36f, height), CornerRadius(4f * open), style = Stroke(FRAME_STROKE * 2.4f))
                drawRoundRect(hot, Offset(6f, top), Size(36f, height), CornerRadius(4f * open), style = Stroke(FRAME_STROKE))
            } else {
                stroke(logo.frame, accent, FRAME_STROKE, glow = true)
            }
        }
        stroke(logo.trimmed(logo.peak, ease(phase(t, 0.55f, 0.75f))), accent, PEAK_STROKE, glow = true)
        stroke(logo.trimmed(logo.neck, phase(t, 0.68f, 0.74f)), accent, FRAME_STROKE, glow = true)
        stroke(logo.trimmed(logo.foot, ease(phase(t, 0.72f, 0.85f))), accent, FRAME_STROKE, glow = true)
    }
}

/** Ambilight: both accents bloom and orbit while the mark draws itself on top. */
private fun DrawScope.drawBloom(t: Float, logo: LogoPaths, accent: Color, second: Color) {
    val grow = ease(phase(t, 0f, 0.55f))
    val angle = (t * 0.9f + 0.1f) * PI.toFloat()
    val orbit = size.minDimension * 0.16f
    val radius = size.maxDimension * 0.5f * grow
    if (radius > 1f) {
        listOf(accent to angle, second to angle + PI.toFloat()).forEach { (color, a) ->
            val c = Offset(center.x + cos(a) * orbit, center.y + sin(a) * orbit * 0.6f)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.34f), Color.Transparent), c, radius), radius, c)
        }
    }
    inLogo {
        drawLogo(
            logo,
            frame = ease(phase(t, 0.12f, 0.6f)),
            peak = ease(phase(t, 0.42f, 0.78f)),
            stand = ease(phase(t, 0.62f, 0.9f)),
            color = lerp(accent, Color.White, 0.12f * (1f - phase(t, 0.85f, 1f))),
        )
    }
}

/** Searching for a signal: noisy, jittering mark that snaps sharp with a spring. */
private fun DrawScope.drawSignal(t: Float, logo: LogoPaths, accent: Color, second: Color) {
    val lock = phase(t, 0.55f, 1f)
    val random = Random((t * 30f).toInt())
    val noise = 1f - phase(t, 0.45f, 0.6f)
    val side = size.minDimension * 0.28f
    if (noise > 0f) {
        repeat(if (IntroDraw.lite) 6 else 16) {
            val y = center.y + (random.nextFloat() - 0.5f) * side * 1.3f
            val w = side * (0.3f + random.nextFloat() * 1.1f)
            val x = center.x + (random.nextFloat() - 0.5f) * side - w / 2f
            val color = if (random.nextBoolean()) accent else second
            drawRect(color.copy(alpha = noise * (0.12f + random.nextFloat() * 0.3f)), Offset(x, y), Size(w, 1f + random.nextFloat() * side * 0.02f))
        }
    }
    val jitter = if (lock > 0f) 0f else (random.nextFloat() - 0.5f) * 6f
    val flicker = if (lock > 0f) 1f else 0.35f + random.nextFloat() * 0.5f
    val spring = 1f + 0.14f * sin(lock * PI.toFloat()) * (1f - lock)
    if (jitter != 0f) inLogo(dx = jitter + 2f) { drawLogo(logo, 1f, 1f, 1f, second.copy(alpha = 0.4f * flicker), glow = false) }
    inLogo(scale = spring, dx = jitter) { drawLogo(logo, 1f, 1f, 1f, accent.copy(alpha = flicker), glow = lock > 0f) }
    if (lock > 0f && lock < 1f) {
        // Scanlines settling over the locked image.
        val lines = 24
        repeat(lines) { i ->
            val y = center.y - side / 2f + side * i / lines
            drawRect(Color.Black.copy(alpha = 0.35f * (1f - lock)), Offset(center.x - side / 2f, y), Size(side, side / lines / 2f))
        }
    }
}

/** A light streak sweeps the faint mark, leaving it lit in the accent behind it. */
private fun DrawScope.drawShimmer(t: Float, logo: LogoPaths, accent: Color, second: Color) {
    val appear = phase(t, 0f, 0.2f)
    val pos = -0.25f + 1.5f * ease(phase(t, 0.1f, 0.8f))
    val lit = accent
    val dim = accent.copy(alpha = 0.16f * appear)
    val highlight = lerp(second, Color.White, 0.55f)
    val stops = arrayOf(
        0f to lit,
        (pos - 0.22f).coerceIn(0f, 1f) to lit,
        (pos - 0.06f).coerceIn(0f, 1f) to second,
        pos.coerceIn(0f, 1f) to highlight,
        (pos + 0.06f).coerceIn(0f, 1f) to dim,
        1f to dim,
    )
    val brush = Brush.linearGradient(*stops, start = Offset(4f, 4f), end = Offset(44f, 44f))
    // Soft halo travelling with the streak across the screen.
    val sweepX = size.width * (pos.coerceIn(-0.2f, 1.2f))
    if (!IntroDraw.lite) drawRect(Brush.linearGradient(
        listOf(Color.Transparent, second.copy(alpha = 0.10f), Color.Transparent),
        start = Offset(sweepX - size.width * 0.25f, 0f),
        end = Offset(sweepX + size.width * 0.25f, size.height),
    ))
    inLogo {
        if (pos > 0f) {
            drawPath(logo.frame, accent.copy(alpha = 0.14f * phase(pos, 0f, 1f)), style = Stroke(FRAME_STROKE * 3f, cap = StrokeCap.Round))
        }
        strokeBrush(logo.frame, brush, FRAME_STROKE)
        strokeBrush(logo.peak, brush, PEAK_STROKE)
        strokeBrush(logo.neck, brush, FRAME_STROKE)
        strokeBrush(logo.foot, brush, FRAME_STROKE)
    }
}

/** Broadcast waves: rings in both accents ripple out as the mark pops in. */
private fun DrawScope.drawRipple(t: Float, logo: LogoPaths, accent: Color, second: Color) {
    val maxRadius = size.maxDimension * 0.62f
    repeat(3) { i ->
        val wave = phase(t, i * 0.14f, i * 0.14f + 0.6f)
        if (wave > 0f && wave < 1f) {
            val color = if (i % 2 == 0) accent else second
            drawCircle(
                color = color.copy(alpha = 0.5f * (1f - wave)),
                radius = maxRadius * ease(wave),
                center = center,
                style = Stroke(size.minDimension * 0.012f * (1f + 2f * (1f - wave))),
            )
        }
    }
    val pop = phase(t, 0.28f, 0.7f)
    if (pop <= 0f) return
    val overshoot = 1f + 0.18f * sin(pop * PI.toFloat()) * (1f - pop)
    inLogo(scale = (0.55f + 0.45f * ease(pop)) * overshoot) {
        drawLogo(logo, 1f, 1f, 1f, accent.copy(alpha = ease(phase(pop, 0f, 0.5f))))
    }
}
