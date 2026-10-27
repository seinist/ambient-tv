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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
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

private class IntroFrame(
    val open: Float,
    val breath: Float,
    val enter: Float,
    val logo: LogoPaths,
    val word: TextLayoutResult,
    val accent: Color,
    val second: Color,
)

private const val VIEWPORT = 48f
private const val FRAME_STROKE = 3.2f
private const val PEAK_STROKE = 3.6f
private const val LOGO_PORTION = 0.40f
private const val LOGO_LIFT = 0.16f
private const val IDLE_SECONDS = 4.8f

private fun phase(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)
private fun ease(x: Float) = FastOutSlowInEasing.transform(x)

/**
 * Full-screen intro in the current accent (and playback accent when a pair is set). The app
 * composes underneath. The opening plays once; the light then breathes while a wake or connect
 * is still running. Any key skips, and it always leaves with a plain fade.
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
    val measurer = rememberTextMeasurer()
    val word = remember(measurer) {
        measurer.measure(
            AnnotatedString("ambient"),
            style = TextStyle(fontSize = 42.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Light),
        )
    }
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
    val openSeconds = IntroAnimation.durationMs(style) / 1000f
    Box(
        modifier.fillMaxSize()
            .graphicsLayer { alpha = exit.value }
            .pointerInput(Unit) { detectTapGestures { IntroPresentation.skip() } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            IntroDraw.lite = lite
            if (still) {
                drawRect(background)
                inLogo { drawLogo(logo, 1f, 1f, 1f, accent, glow = false) }
                drawAmbientWord(word, 1f, 1f, accent)
                return@Canvas
            }
            val seconds = clock.floatValue
            val open = ease((seconds / openSeconds).coerceIn(0f, 1f))
            // wave(1) and wave(0) both sit at 0, so the idle breath continues the opening without a pop.
            val breath = wave(
                if (seconds <= openSeconds) seconds / openSeconds
                else ((seconds - openSeconds) % IDLE_SECONDS) / IDLE_SECONDS,
            )
            val enter = ease(phase(seconds, 0f, 0.5f))
            val presence = when (style) {
                IntroAnimation.POWER_ON -> phase(open, 0.25f, 0.75f)
                IntroAnimation.SIGNAL -> phase(open, 0.2f, 0.6f)
                IntroAnimation.RIPPLE -> phase(open, 0.12f, 0.55f)
                IntroAnimation.WORD -> phase(open, 0.25f, 0.9f)
                IntroAnimation.HORIZON -> phase(open, 0.45f, 1f)
                IntroAnimation.SHIMMER -> 0.8f
                else -> 1f
            }
            drawStage(background, accent, second, breath, enter, presence)
            val frame = IntroFrame(open, breath, enter, logo, word, accent, second)
            when (style) {
                IntroAnimation.POWER_ON -> drawPowerOn(frame)
                IntroAnimation.SIGNAL -> drawSignal(frame)
                IntroAnimation.SHIMMER -> drawShimmer(frame)
                IntroAnimation.RIPPLE -> drawRipple(frame)
                IntroAnimation.WORD -> drawWordIntro(frame)
                IntroAnimation.HORIZON -> drawHorizon(frame)
                else -> drawBloom(frame)
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

private fun DrawScope.logoSide(scale: Float = 1f) = size.minDimension * LOGO_PORTION * scale

/** Mark center in screen space. The wordmark sits under the mark, so the pair is lifted. */
private fun DrawScope.logoCenter(): Offset {
    val side = logoSide()
    return Offset(center.x, center.y - side * LOGO_LIFT)
}

/** Runs [block] in logo viewport units, centered, about 40% of the short screen side. */
private fun DrawScope.inLogo(scale: Float = 1f, block: DrawScope.() -> Unit) {
    val side = logoSide(scale)
    val unit = side / VIEWPORT
    withTransform({
        translate(center.x - side / 2f, center.y - side / 2f - side * LOGO_LIFT)
        scale(unit, unit, pivot = Offset.Zero)
    }, block)
}

private fun DrawScope.drawStage(
    background: Color,
    accent: Color,
    second: Color,
    breath: Float,
    enter: Float,
    presence: Float,
) {
    drawRect(background)
    if (IntroDraw.lite) return
    val amount = enter * presence
    if (amount <= 0f) return
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent,
            0.5f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.42f * amount),
            center = center,
            radius = size.maxDimension * 0.68f,
        ),
    )
    val glow = lerp(accent, second, breath)
    drawRect(
        Brush.radialGradient(
            0f to glow.copy(alpha = (0.16f + 0.08f * breath) * amount),
            0.28f to accent.copy(alpha = 0.09f * amount),
            0.62f to accent.copy(alpha = 0.03f * amount),
            1f to Color.Transparent,
            center = logoCenter(),
            radius = size.minDimension * (0.30f + 0.05f * breath),
        ),
    )
}

private fun DrawScope.drawAmbientWord(layout: TextLayoutResult, alpha: Float, reveal: Float, color: Color) {
    if (alpha <= 0f || reveal <= 0f) return
    val side = logoSide()
    val top = center.y + side / 2f - side * LOGO_LIFT + side * 0.05f
    val targetW = side * 1.08f
    val scale = targetW / layout.size.width.toFloat().coerceAtLeast(1f)
    val tint = color.copy(alpha = alpha)
    withTransform({
        translate(center.x - targetW / 2f, top)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        if (reveal >= 1f) drawText(layout, tint)
        else clipRect(right = layout.size.width * reveal, bottom = layout.size.height.toFloat() + 4f) {
            drawText(layout, tint)
        }
    }
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

private fun DrawScope.screenGlass(color: Color) {
    drawRoundRect(color, Offset(11f, 11f), Size(26f, 16f), CornerRadius(3f, 3f))
}

/** A line of light opens into the frame, the peak draws, then the screen interior keeps a soft glow. */
private fun DrawScope.drawPowerOn(frame: IntroFrame) {
    val show = frame.enter
    val aperture = ease(phase(frame.open, 0.02f, 0.48f))
    val peak = ease(phase(frame.open, 0.40f, 0.74f))
    val stand = ease(phase(frame.open, 0.55f, 0.86f))
    inLogo {
        drawLogo(frame.logo, aperture, peak, stand, frame.accent.copy(alpha = show), glow = false)
        val lineHalf = 0.7f + 9f * aperture
        val lineAlpha = (1f - aperture) * show
        if (lineAlpha > 0.01f) {
            drawRect(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        lerp(frame.second, Color.White, 0.65f).copy(alpha = 0.85f * lineAlpha),
                        Color.Transparent,
                    ),
                    startY = 20f - lineHalf, endY = 20f + lineHalf,
                ),
                topLeft = Offset(10f, 20f - lineHalf),
                size = Size(28f, lineHalf * 2f),
            )
        }
        val inner = phase(frame.open, 0.58f, 0.94f) * (0.55f + 0.45f * frame.breath) * show
        if (inner > 0.01f) {
            drawRoundRect(
                Brush.verticalGradient(
                    listOf(frame.second.copy(alpha = 0.18f * inner), frame.accent.copy(alpha = 0.05f * inner)),
                    startY = 11f, endY = 28f,
                ),
                topLeft = Offset(11f, 11f),
                size = Size(26f, 16f),
                cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }
    drawAmbientWord(frame.word, show * phase(frame.open, 0.45f, 0.8f), 1f, frame.accent)
}

/** The mark stands like glass while the backlight swells between the two accents. */
private fun DrawScope.drawBloom(frame: IntroFrame) {
    val show = ease(phase(frame.open, 0f, 0.42f)) * frame.enter
    val halo = (0.35f + 0.65f * frame.breath) * show
    val glowColor = lerp(frame.accent, frame.second, frame.breath)
    inLogo {
        screenGlass(glowColor.copy(alpha = 0.07f * halo))
        if (!IntroDraw.lite) {
            listOf(
                frame.logo.frame to FRAME_STROKE,
                frame.logo.peak to PEAK_STROKE,
                frame.logo.neck to FRAME_STROKE,
                frame.logo.foot to FRAME_STROKE,
            ).forEach { (path, width) ->
                drawPath(
                    path,
                    glowColor.copy(alpha = 0.22f * halo),
                    style = Stroke(width * (2.4f + 1.8f * frame.breath), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        drawLogo(frame.logo, 1f, 1f, 1f, frame.accent.copy(alpha = show), glow = false)
    }
    drawAmbientWord(frame.word, show, 1f, frame.accent)
}

/** Two arcs search, then the peak locks with a short flash and holds. */
private fun DrawScope.drawSignal(frame: IntroFrame) {
    val show = frame.enter
    val shell = ease(phase(frame.open, 0.02f, 0.32f))
    val peak = ease(phase(frame.open, 0.58f, 0.84f))
    val search = 1f - phase(frame.open, 0.62f, 0.88f)
    val lock = phase(frame.open, 0.74f, 0.96f)
    val flash = sin(lock * PI.toFloat()) * phase(frame.open, 0.74f, 0.82f)
    inLogo {
        stroke(frame.logo.trimmed(frame.logo.frame, shell), frame.accent.copy(alpha = show), FRAME_STROKE)
        stroke(frame.logo.trimmed(frame.logo.neck, shell), frame.accent.copy(alpha = show), FRAME_STROKE)
        stroke(frame.logo.trimmed(frame.logo.foot, shell), frame.accent.copy(alpha = show), FRAME_STROKE)
        if (search > 0.01f) {
            val sweep = 64f + 18f * frame.breath
            drawArc(
                frame.second.copy(alpha = 0.55f * search * show),
                startAngle = 206f + frame.open * 70f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(6f, 0f),
                size = Size(36f, 26f),
                style = Stroke(2.1f, cap = StrokeCap.Round),
            )
            drawArc(
                frame.accent.copy(alpha = 0.4f * search * show),
                startAngle = 248f + frame.open * 90f,
                sweepAngle = sweep * 0.72f,
                useCenter = false,
                topLeft = Offset(9f, 3f),
                size = Size(30f, 22f),
                style = Stroke(1.7f, cap = StrokeCap.Round),
            )
        }
        stroke(frame.logo.trimmed(frame.logo.peak, peak), frame.accent.copy(alpha = show), PEAK_STROKE, glow = flash > 0.2f)
        if (flash > 0.02f) {
            stroke(frame.logo.peak, lerp(frame.second, Color.White, 0.7f).copy(alpha = flash * show), PEAK_STROKE)
        }
    }
    drawAmbientWord(frame.word, show * phase(frame.open, 0.4f, 0.75f), 1f, frame.accent)
}

/** A wide sheen, then a dimmer second pass, cross the mark and the word. */
private fun DrawScope.drawShimmer(frame: IntroFrame) {
    val show = frame.enter
    inLogo { drawLogo(frame.logo, 1f, 1f, 1f, frame.accent.copy(alpha = show), glow = false) }
    drawAmbientWord(frame.word, show, 1f, frame.accent)
    fun sheen(start: Float, end: Float, alpha: Float, widthFactor: Float) {
        val p = phase(frame.open, start, end)
        if (p <= 0f || p >= 1f) return
        val fade = sin(p * PI.toFloat())
        val x = size.width * (-0.35f + 1.7f * ease(p))
        val band = size.minDimension * widthFactor
        val tint = lerp(frame.second, Color.White, 0.65f).copy(alpha = alpha * fade * show)
        withTransform({ translate(x, size.height * 0.45f); rotate(18f, Offset.Zero) }) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, tint, Color.Transparent), startX = -band, endX = band),
                topLeft = Offset(-band, -size.height),
                size = Size(band * 2f, size.height * 2f),
            )
        }
    }
    sheen(0.02f, 0.68f, 0.20f, 0.16f)
    sheen(0.30f, 0.98f, 0.10f, 0.26f)
}

/** Three staggered rings leave the screen center and fade before they outgrow the mark. */
private fun DrawScope.drawRipple(frame: IntroFrame) {
    val show = frame.enter
    inLogo {
        fun ring(start: Float) {
            val p = phase(frame.open, start, (start + 0.4f).coerceAtMost(1f))
            if (p <= 0f || p >= 1f) return
            val grow = 1f + 0.42f * ease(p)
            val alpha = (1f - p) * 0.5f * show
            withTransform({ scale(grow, grow, pivot = Offset(24f, 20f)) }) {
                drawPath(
                    frame.logo.frame,
                    frame.second.copy(alpha = alpha),
                    style = Stroke(FRAME_STROKE / grow, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        ring(0.04f)
        ring(0.26f)
        ring(0.48f)
        drawLogo(frame.logo, 1f, 1f, 1f, frame.accent.copy(alpha = show * phase(frame.open, 0.08f, 0.4f)), glow = false)
    }
    drawAmbientWord(frame.word, show * phase(frame.open, 0.35f, 0.7f), 1f, frame.accent)
}

/** Frame, peak, stand and the word draw themselves, then the shared light holds. */
private fun DrawScope.drawWordIntro(frame: IntroFrame) {
    val show = frame.enter
    val shell = ease(phase(frame.open, 0.02f, 0.28f))
    val peak = ease(phase(frame.open, 0.18f, 0.44f))
    val stand = ease(phase(frame.open, 0.36f, 0.54f))
    val reveal = ease(phase(frame.open, 0.48f, 0.92f))
    inLogo { drawLogo(frame.logo, shell, peak, stand, frame.accent.copy(alpha = show), glow = false) }
    drawAmbientWord(frame.word, show, reveal, frame.accent)
}

/** A horizon of light rises, the mark appears above it, and the light settles into the upper screen. */
private fun DrawScope.drawHorizon(frame: IntroFrame) {
    val show = frame.enter
    val rise = ease(frame.open)
    val y = size.height * (0.82f - 0.52f * rise)
    val band = size.minDimension * (0.018f + 0.045f * (1f - rise))
    val bandAlpha = (0.28f + 0.14f * frame.breath) * show
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, lerp(frame.accent, frame.second, 0.4f).copy(alpha = bandAlpha), Color.Transparent),
            startY = y - band, endY = y + band,
        ),
        topLeft = Offset(0f, (y - band).coerceAtLeast(0f)),
        size = Size(size.width, band * 2f),
    )
    val settled = phase(frame.open, 0.62f, 1f) * (0.75f + 0.25f * frame.breath) * show
    if (settled > 0.01f) {
        drawRect(
            Brush.verticalGradient(
                listOf(frame.accent.copy(alpha = 0.14f * settled), Color.Transparent),
                startY = 0f,
                endY = size.height * 0.42f,
            ),
        )
    }
    val mark = ease(phase(frame.open, 0.32f, 0.72f)) * show
    inLogo { drawLogo(frame.logo, 1f, 1f, 1f, frame.accent.copy(alpha = mark), glow = false) }
    drawAmbientWord(frame.word, mark, 1f, frame.accent)
}
