package com.ambient.tv.ui.intro

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.data.local.IntroAnimation
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.IntroConnectionStatus
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.workingConnectivity
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.performance.PerformanceMode
import com.ambient.tv.ui.theme.LocalPlaybackAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

/** Strokes of ic_ambient_tv in its 48×48 viewport. */
private class LogoPaths {
    val frame = parse("M10 8H38A4 4 0 0 1 42 12V28A4 4 0 0 1 38 32H10A4 4 0 0 1 6 28V12A4 4 0 0 1 10 8Z")
    val peak = parse("M15 28L24 13L33 28")
    val stand = parse("M24 34.5V37M16 40H32")
}

/**
 * The full brand wordmark (docs/assets/wordmark.svg) in its own units, baseline at y 0: the Λ
 * stroke, the "mbient" fills with their x offsets, and the TV badge with its cut-out label.
 */
private class WordmarkPaths {
    val letters = listOf(
        19.828f to parse("M82 0H207V271C207 377 263 431 346 431C422 431 466 385 466 290V0H591V271C591 377 648 431 730 431C806 431 850 385 850 290V0H975V306C975 467 885 540 756 540C671 540 599 506 556 450C519 511 454 540 376 540C303 540 241 515 201 467V534H82Z"),
        42.994f to parse("M378 -7C534 -7 649 101 649 267C649 433 534 540 378 540C310 540 250 517 207 469V742H82V0H201V70C243 18 305 -7 378 -7ZM364 100C274 100 205 165 205 267C205 369 274 434 364 434C454 434 522 369 522 267C522 165 454 100 364 100Z"),
        58.086f to parse("M82 0H207V534H82ZM145 622C192 622 225 656 225 700C225 741 191 772 145 772C98 772 64 739 64 697C64 655 98 622 145 622Z"),
        64.444f to parse("M333 -7C425 -7 500 23 548 80L481 157C443 118 396 99 336 99C243 99 178 149 163 227H582C583 239 584 254 584 264C584 431 470 540 314 540C155 540 38 426 38 267C38 108 154 -7 333 -7ZM162 310C174 388 233 440 314 440C396 440 455 387 465 310Z"),
        78.128f to parse("M82 0H207V270C207 377 267 431 356 431C436 431 483 385 483 290V0H608V306C608 467 513 540 385 540C307 540 242 514 201 465V534H82Z"),
        93.198f to parse("M283 -7C328 -7 373 5 403 29L368 118C349 103 324 96 298 96C250 96 224 124 224 176V434H367V534H224V652H99V534H11V434H99V173C99 55 166 -7 283 -7Z"),
    )
    val badgeLabel = listOf(
        0f to parse("M236 0H366V590H598V700H4V590H236Z"),
        6.02f to parse("M301 0H429L734 700H604L369 161L136 700H-5Z"),
    )
}

private fun parse(data: String) = PathParser().parsePathString(data).toPath()

/** The Λ as three points (left foot, apex, right foot) and its stroke, in wordmark units. */
private val WORDMARK_PEAK = listOf(Offset(1.914f, -1.375f), Offset(9.914f, -17.375f), Offset(17.914f, -1.375f))
private const val WORDMARK_PEAK_STROKE = 2.75f
/** The same three points of the icon's peak, in its 48-unit viewport. */
private val ICON_PEAK = listOf(Offset(15f, 28f), Offset(24f, 13f), Offset(33f, 28f))
private const val GLYPH_SCALE = 0.022f
private const val LABEL_SCALE = 0.01f
private val BADGE_TOP_LEFT = Offset(110.526f, -16f)
private val BADGE_SIZE = Size(25f, 18f)
private val LABEL_ORIGIN = Offset(115.526f, -3.5f)
/** Ink bounds of the wordmark, Λ stroke and badge included. */
private const val WORDMARK_LEFT = 0.5f
private const val WORDMARK_RIGHT = 135.6f
private const val WORDMARK_TOP = -18.8f
private const val WORDMARK_BOTTOM = 2f
private const val INTRO_ICON_WORD_GAP = 2.5f
private const val INTRO_WORDMARK_PEAK_CENTER_X = 9.914f
private const val INTRO_ICON_CENTER_X = 24f
private const val INTRO_ICON_FRAME_LEFT_X = 6f
private const val INTRO_ICON_FRAME_RIGHT_X = 42f
private const val INTRO_ICON_PEAK_LEFT_X = 15f
private const val INTRO_ICON_PEAK_RIGHT_X = 33f
private const val INTRO_FIRST_LETTER_OFFSET_X = 19.828f
private const val INTRO_FIRST_LETTER_GLYPH_LEFT_X = 82f

/** Final Bloom geometry in wordmark units: the monitor remains the A instead of separating from it. */
internal fun introBloomIconScaleInWordmarkUnits(): Float =
    (17.914f - 1.914f) / (INTRO_ICON_PEAK_RIGHT_X - INTRO_ICON_PEAK_LEFT_X)

internal fun introBloomWordShift(): Float {
    val iconScale = introBloomIconScaleInWordmarkUnits()
    val frameRight = INTRO_WORDMARK_PEAK_CENTER_X +
        (INTRO_ICON_FRAME_RIGHT_X - INTRO_ICON_CENTER_X) * iconScale
    val firstLetterInkLeft = INTRO_FIRST_LETTER_OFFSET_X + INTRO_FIRST_LETTER_GLYPH_LEFT_X * GLYPH_SCALE
    return frameRight + INTRO_ICON_WORD_GAP - firstLetterInkLeft
}

internal fun introBloomWordmarkLeft(): Float =
    INTRO_WORDMARK_PEAK_CENTER_X +
        (INTRO_ICON_FRAME_LEFT_X - INTRO_ICON_CENTER_X) * introBloomIconScaleInWordmarkUnits()

/** Composition center as a fraction of the height: the lower third stays free for the status card. */
private const val STAGE_Y = 0.40f
private const val IDLE_SECONDS = 6.4f
/** Draw-time seconds for the still (Sparsam) frame: every reveal has finished. */
private const val SETTLED_SECONDS = 60f

private class IntroFrame(
    val seconds: Float,
    val breath: Float,
    val accent: Color,
    val second: Color,
    val ink: Color,
    val cutout: Color,
    val logo: LogoPaths,
    val wordmark: WordmarkPaths,
)

private fun phase(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)
/** Sine in-out: slow on both ends, no snap. */
private fun soft(x: Float) = (1f - cos(x * PI.toFloat())) / 2f
/** Breathing curve over one loop: 0 at the loop seam, 1 halfway. */
private fun wave(t: Float) = (1f - cos(t * 2f * PI.toFloat())) / 2f
private fun drift(t: Float, period: Float, offset: Float = 0f) = sin(t * 2f * PI.toFloat() / period + offset)

/**
 * Full-screen intro in the current accent (and playback accent when a pair is set). The app
 * composes underneath. Light is the subject: it gathers, the brand wordmark settles into it, and
 * the light keeps breathing while a wake or connect is still running. The composition sits in the
 * upper part so the connection status below never covers it. Any key skips.
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
    val ink = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f)
    val profile = LocalPerformanceProfile.current
    // Sparsam: the settled frame without motion; reduced effects: motion without grain and extra light.
    val still = profile.mode == PerformanceMode.LOW
    val lite = profile.reducedEffects
    // Seconds since the intro appeared; read only in the draw phase, so the loop never recomposes.
    val clock = remember { mutableFloatStateOf(0f) }
    val exit = remember { Animatable(1f) }
    val statusAlpha = remember { Animatable(0f) }
    val logo = remember { LogoPaths() }
    val wordmark = remember { WordmarkPaths() }
    val grain = remember(lite) { if (lite || still) null else grainBrush() }
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
        // The loop keeps running under the fade, so the light dims instead of freezing.
        exit.animateTo(0f, tween(if (skipped || still) 220 else 700, easing = LinearEasing))
        ticker?.cancel()
        finished()
    }
    LaunchedEffect(holding) {
        if (holding) statusAlpha.animateTo(1f, tween(if (lite) 0 else 600))
    }
    if (style == IntroAnimation.OFF) return
    val openSeconds = IntroAnimation.durationMs(style) / 1000f
    Box(
        modifier.fillMaxSize()
            .graphicsLayer {
                alpha = exit.value
                val grow = 1f + 0.02f * (1f - exit.value)
                scaleX = grow
                scaleY = grow
            }
            .pointerInput(Unit) { detectTapGestures { IntroPresentation.skip() } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            IntroDraw.lite = lite
            val seconds = if (still) SETTLED_SECONDS else clock.floatValue
            // wave(1) and wave(0) both sit at 0, so the idle breath continues the opening without a pop.
            val breath = if (still) 0.5f else wave(
                if (seconds <= openSeconds) seconds / openSeconds
                else ((seconds - openSeconds) % IDLE_SECONDS) / IDLE_SECONDS,
            )
            val frame = IntroFrame(seconds, breath, accent, second, ink, background, logo, wordmark)
            drawRect(background)
            when (style) {
                IntroAnimation.HORIZON -> drawDaybreak(frame)
                else -> drawLightField(frame)
            }
            grain?.let { drawRect(it, alpha = 0.05f, blendMode = BlendMode.Overlay) }
            if (!lite) drawVignette()
            // Calms the light under the status card so its text keeps full contrast.
            val scrim = statusAlpha.value
            if (scrim > 0f) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to background.copy(alpha = 0.85f * scrim),
                        startY = size.height * 0.55f,
                        endY = size.height,
                    ),
                )
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

private const val MIN_VISIBLE_MS = 6_000L
private const val MAX_HOLD_MS = 3 * 60_000L
private const val CONNECT_HOLD_MS = 6_000L

/** Per-frame draw flag; the intro draws on the main thread only. */
private object IntroDraw {
    var lite = false
}

/** Fine monochrome noise, tiled and overlaid: it dithers the gradients so dark screens show no banding. */
private fun grainBrush(): ShaderBrush {
    val side = 128
    val random = java.util.Random(7)
    val pixels = IntArray(side * side) {
        val v = 96 + random.nextInt(64)
        android.graphics.Color.argb(255, v, v, v)
    }
    val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

private fun DrawScope.stageCenter() = Offset(center.x, size.height * STAGE_Y)

/** A soft pool of light, added onto what is below. [squash] flattens it vertically. */
private fun DrawScope.lightPool(color: Color, at: Offset, radius: Float, alpha: Float, squash: Float = 1f) {
    if (alpha <= 0.002f) return
    withTransform({ scale(1f, squash, pivot = at) }) {
        drawCircle(
            Brush.radialGradient(
                0f to color.copy(alpha = alpha),
                0.35f to color.copy(alpha = alpha * 0.55f),
                0.7f to color.copy(alpha = alpha * 0.14f),
                1f to Color.Transparent,
                center = at,
                radius = radius,
            ),
            radius = radius,
            center = at,
            blendMode = BlendMode.Plus,
        )
    }
}

private fun DrawScope.drawVignette() {
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent,
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.35f),
            center = stageCenter(),
            radius = size.maxDimension * 0.75f,
        ),
    )
}


private fun mix(a: Offset, b: Offset, f: Float) = a + (b - a) * f
private fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f

/**
 * The full wordmark centered on [at], [width] wide, starting at [start]. With [fromIcon] the TV
 * icon stands in the light first, then the complete monitor+A mark moves left as one rigid unit.
 * "mbient" follows letter by letter beside it. Otherwise the standalone Λ gathers on its own.
 * The TV badge switches on last: a line of light opens into the badge, then the label appears.
 */
private fun DrawScope.drawWordmark(frame: IntroFrame, at: Offset, width: Float, start: Float, fromIcon: Boolean) {
    val t = frame.seconds - start
    val wordShift = if (fromIcon) introBloomWordShift() else 0f
    val layoutLeft = if (fromIcon) introBloomWordmarkLeft() else WORDMARK_LEFT
    val layoutRight = WORDMARK_RIGHT + wordShift
    // Keep the established wordmark scale; Bloom only grows horizontally enough to retain
    // the monitor around its A instead of shrinking the rest of the animation.
    val unit = width / (WORDMARK_RIGHT - WORDMARK_LEFT)
    val origin = Offset(
        at.x - (layoutLeft + layoutRight) / 2f * unit,
        at.y - (WORDMARK_TOP + WORDMARK_BOTTOM) / 2f * unit,
    )
    fun mark(p: Offset) = origin + p * unit
    val lettersFrom = if (fromIcon) 1.8f else 1.1f
    val badgeFrom = lettersFrom + 1.1f
    val appear = soft(phase(t, 0f, if (fromIcon) 1.1f else 1.6f))
    val move = if (fromIcon) soft(phase(t, 1.1f, 2.3f)) else 1f
    val peak: List<Offset>
    val peakStroke: Float
    if (fromIcon) {
        // Bloom keeps the complete brand mark rigid: frame, stand and A translate/scale together.
        // The old code already morphed the A toward the standalone wordmark Λ while the monitor
        // continued its own trip left, which visibly pulled the frame away from the centered A.
        val bigUnit = width * 0.30f / 48f
        val bigOrigin = at - Offset(INTRO_ICON_CENTER_X, INTRO_ICON_CENTER_X) * bigUnit
        val endUnit = unit * introBloomIconScaleInWordmarkUnits()
        val endOrigin = mark(Offset(INTRO_WORDMARK_PEAK_CENTER_X, WORDMARK_PEAK[1].y)) -
            Offset(INTRO_ICON_CENTER_X, ICON_PEAK[1].y) * endUnit
        val iconUnit = mix(bigUnit, endUnit, move)
        val iconOrigin = mix(bigOrigin, endOrigin, move)
        peak = ICON_PEAK.map { iconOrigin + it * iconUnit }
        peakStroke = 3.6f * iconUnit
        if (appear > 0f) {
            val shellColor = lerp(frame.ink, frame.accent, 0.25f)
            withTransform({
                translate(iconOrigin.x, iconOrigin.y)
                scale(iconUnit, iconUnit, pivot = Offset.Zero)
            }) {
                val style = Stroke(3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(frame.logo.frame, shellColor.copy(alpha = frame.ink.alpha * appear), style = style)
                drawPath(frame.logo.stand, shellColor.copy(alpha = frame.ink.alpha * appear), style = style)
            }
        }
    } else {
        val grow = 1.08f - 0.08f * appear
        val pivot = mark(Offset(9.914f, -9.375f))
        peak = WORDMARK_PEAK.map { pivot + (mark(it) - pivot) * grow }
        peakStroke = WORDMARK_PEAK_STROKE * unit
    }
    if (appear > 0f) {
        if (!IntroDraw.lite) {
            val glowAt = Offset(peak[1].x, (peak[0].y + peak[1].y) / 2f)
            val glowSize = (peak[2].x - peak[0].x) * 1.6f
            lightPool(lerp(frame.accent, frame.second, frame.breath), glowAt, glowSize, (0.10f + 0.08f * frame.breath) * appear)
        }
        val path = Path().apply {
            moveTo(peak[0].x, peak[0].y)
            lineTo(peak[1].x, peak[1].y)
            lineTo(peak[2].x, peak[2].y)
        }
        drawPath(path, frame.accent.copy(alpha = appear), style = Stroke(peakStroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    val line = soft(phase(t, badgeFrom, badgeFrom + 0.5f))
    if (line > 0f && !IntroDraw.lite) {
        val badgeCenter = mark(BADGE_TOP_LEFT + Offset(wordShift + BADGE_SIZE.width / 2f, BADGE_SIZE.height / 2f))
        lightPool(lerp(frame.accent, frame.second, frame.breath), badgeCenter, BADGE_SIZE.width * unit * 1.4f, 0.12f * line, squash = 0.7f)
    }
    withTransform({
        translate(origin.x, origin.y)
        scale(unit, unit, pivot = Offset.Zero)
    }) {
        frame.wordmark.letters.forEachIndexed { i, (x, glyph) ->
            val from = lettersFrom + 0.12f * i
            val letter = soft(phase(t, from, from + 1.2f))
            if (letter <= 0f) return@forEachIndexed
            withTransform({
                translate(x + wordShift, 2.4f * (1f - letter))
                scale(GLYPH_SCALE, -GLYPH_SCALE, pivot = Offset.Zero)
            }) {
                drawPath(glyph, frame.ink.copy(alpha = frame.ink.alpha * letter))
            }
        }
        if (line > 0f) {
            // Switches on like a screen: a bright line, opening to the full badge.
            val open = soft(phase(t, badgeFrom + 0.3f, badgeFrom + 1.1f))
            val height = BADGE_SIZE.height * (0.08f + 0.92f * open)
            val middle = BADGE_TOP_LEFT.y + BADGE_SIZE.height / 2f
            val radius = minOf(4f, height / 2f)
            drawRoundRect(
                lerp(frame.accent, Color.White, 0.5f * (1f - open)).copy(alpha = line),
                topLeft = Offset(BADGE_TOP_LEFT.x + wordShift, middle - height / 2f),
                size = Size(BADGE_SIZE.width, height),
                cornerRadius = CornerRadius(radius, radius),
            )
            val label = soft(phase(t, badgeFrom + 0.8f, badgeFrom + 1.6f))
            if (label > 0f) {
                frame.wordmark.badgeLabel.forEach { (x, glyph) ->
                    withTransform({
                        translate(LABEL_ORIGIN.x + wordShift + x, LABEL_ORIGIN.y)
                        scale(LABEL_SCALE, -LABEL_SCALE, pivot = Offset.Zero)
                    }) {
                        drawPath(glyph, frame.cutout.copy(alpha = label))
                    }
                }
            }
        }
    }
}

/** Two or three wide pools of the accents drift on long, unrelated periods; the icon settles into the wordmark. */
private fun DrawScope.drawLightField(frame: IntroFrame) {
    val t = frame.seconds
    val gather = soft(phase(t, 0f, 2.6f)) * (0.85f + 0.15f * frame.breath)
    val span = size.maxDimension
    val stage = stageCenter()
    lightPool(
        frame.accent,
        Offset(size.width * (0.36f + 0.07f * drift(t, 19f)), stage.y + size.height * 0.05f * drift(t, 23f, 1f)),
        span * 0.55f, 0.22f * gather,
    )
    lightPool(
        frame.second,
        Offset(size.width * (0.64f + 0.06f * drift(t, 21f, 2f)), stage.y + size.height * 0.06f * drift(t, 17f, 4f)),
        span * 0.48f, 0.18f * gather,
    )
    if (!IntroDraw.lite) {
        lightPool(
            lerp(frame.accent, frame.second, 0.5f),
            Offset(size.width * (0.5f + 0.12f * drift(t, 29f, 3f)), size.height * (0.2f + 0.06f * drift(t, 31f))),
            span * 0.4f, 0.10f * gather,
        )
    }
    drawWordmark(frame, stage, size.minDimension * 0.62f, start = 0.5f, fromIcon = true)
}

/** Light rises slowly from below the screen edge like early morning; the wordmark appears in the upper calm. */
private fun DrawScope.drawDaybreak(frame: IntroFrame) {
    val t = frame.seconds
    val rise = soft(phase(t, 0f, 3.6f))
    val glow = 0.85f + 0.15f * frame.breath
    val sun = Offset(center.x, size.height * (1.38f - 0.2f * rise))
    lightPool(lerp(frame.second, frame.accent, 0.35f), sun, size.width * 0.8f, 0.26f * rise * glow, squash = 0.6f)
    lightPool(lerp(frame.second, Color.White, 0.3f), sun, size.width * 0.38f, 0.16f * rise * glow, squash = 0.5f)
    drawRect(
        Brush.verticalGradient(
            0f to frame.accent.copy(alpha = 0.06f * rise),
            0.5f to Color.Transparent,
            startY = 0f,
            endY = size.height,
        ),
        blendMode = BlendMode.Plus,
    )
    drawWordmark(frame, stageCenter(), size.minDimension * 0.62f, start = 0.9f, fromIcon = false)
}
