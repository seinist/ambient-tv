package com.ambient.tv.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ambient.tv.data.browse.BrowseWorkBudget
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.performance.PerformanceProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.sin

/** Optional decoration; ordinary focus feedback has its own, independent policy. */
enum class AccentMotionMode(val key: String) {
    OFF("off"), FOCUS("focus"), AMBIENT("ambient");

    companion object {
        const val DEFAULT = "off"
        fun fromKey(key: String?): AccentMotionMode = entries.firstOrNull { it.key == key } ?: OFF
    }
}

val LocalAccentMotionMode = staticCompositionLocalOf { AccentMotionMode.OFF }

/** The shell disables decoration while an intro or screensaver covers the content. */
val LocalAccentMotionAllowed = staticCompositionLocalOf { true }

/** Only draw callbacks read this state. Unfocused cards never subscribe to a clock. */
internal val LocalAccentMotionState = staticCompositionLocalOf<State<AccentMotionFrame>?> { null }

@Immutable
internal data class AccentMotionFrame(val phase: Float, val strength: Float) {
    companion object { val Rest = AccentMotionFrame(0f, 0f) }
}

internal fun accentMotionAllowed(mode: AccentMotionMode, profile: PerformanceProfile): Boolean =
    mode != AccentMotionMode.OFF && profile.allowAccentMaterialMotion

internal fun accentMotionDurationMs(style: AccentStyle): Long = when (style) {
    AccentStyle.SATIN -> 1_700L
    AccentStyle.GLASS, AccentStyle.CHROME -> 1_450L
    AccentStyle.GLOW -> 2_400L
    AccentStyle.PEARL -> 2_200L
    AccentStyle.CERAMIC -> 1_100L
    AccentStyle.FACET -> 1_400L
    AccentStyle.LIGHT -> 850L
}

internal fun accentMotionPeriodMs(style: AccentStyle): Long = when (style) {
    AccentStyle.GLOW -> 7_000L
    AccentStyle.SATIN, AccentStyle.CERAMIC -> 8_000L
    else -> 9_000L
}

/** A zero-slope envelope starts and ends at the unchanged static material. */
internal fun accentMotionFrame(
    style: AccentStyle,
    elapsedMs: Long,
    ambientRepeat: Boolean = false,
): AccentMotionFrame {
    val duration = accentMotionDurationMs(style)
    if (elapsedMs <= 0L || elapsedMs >= duration) return AccentMotionFrame.Rest
    val phase = elapsedMs.toFloat() / duration
    val wave = sin(PI * phase).toFloat()
    return AccentMotionFrame(phase, wave * wave * if (ambientRepeat) .8f else 1f)
}

private const val ACCENT_MOTION_FOCUS_DELAY_MS = 350L
private const val ACCENT_MOTION_POLL_MS = 100L

/**
 * One finite light gesture, or gestures separated by long, frame-free ambient rests.
 * Input restarts the quiet delay; hidden windows await an observable gate without polling.
 * Keep the gates injectable so tests verify actual frame-clock requests, not just colors.
 */
internal suspend fun runAccentMotion(
    mode: AccentMotionMode,
    style: AccentStyle,
    setFrame: (AccentMotionFrame) -> Unit,
    nowMs: () -> Long,
    interacting: () -> Boolean,
    active: () -> Boolean,
    awaitActive: suspend () -> Unit,
    durationScale: () -> Float = { 1f },
) {
    setFrame(AccentMotionFrame.Rest)
    if (mode == AccentMotionMode.OFF) return
    var repeat = false
    var waitUntil = nowMs() + ACCENT_MOTION_FOCUS_DELAY_MS
    var elapsedMs = 0.0
    var lastFrameNs = -1L
    try {
        while (true) {
            if (!active()) {
                setFrame(AccentMotionFrame.Rest)
                awaitActive()
                elapsedMs = 0.0
                lastFrameNs = -1L
                waitUntil = nowMs() + ACCENT_MOTION_FOCUS_DELAY_MS
                continue
            }
            val scale = durationScale()
            if (!scale.isFinite() || scale <= 0f || interacting()) {
                setFrame(AccentMotionFrame.Rest)
                elapsedMs = 0.0
                lastFrameNs = -1L
                waitUntil = nowMs() + ACCENT_MOTION_FOCUS_DELAY_MS
                delay(ACCENT_MOTION_POLL_MS)
                continue
            }
            val remainingWait = waitUntil - nowMs()
            if (remainingWait > 0L) {
                delay(remainingWait.coerceAtMost(ACCENT_MOTION_POLL_MS))
                continue
            }
            var interrupted = false
            withFrameNanos { frameNs ->
                // Input or a lifecycle event can arrive while the frame is queued.
                val currentScale = durationScale()
                if (!active() || interacting() || !currentScale.isFinite() || currentScale <= 0f) {
                    interrupted = true
                    setFrame(AccentMotionFrame.Rest)
                } else {
                    if (lastFrameNs >= 0L) {
                        elapsedMs += ((frameNs - lastFrameNs) / 1_000_000.0).coerceIn(0.0, 100.0) / currentScale
                    }
                    lastFrameNs = frameNs
                    setFrame(accentMotionFrame(style, elapsedMs.toLong(), repeat))
                }
            }
            if (interrupted) {
                elapsedMs = 0.0
                lastFrameNs = -1L
                waitUntil = nowMs() + ACCENT_MOTION_FOCUS_DELAY_MS
                continue
            }
            if (elapsedMs >= accentMotionDurationMs(style)) {
                setFrame(AccentMotionFrame.Rest)
                if (mode == AccentMotionMode.FOCUS) return
                repeat = true
                elapsedMs = 0.0
                lastFrameNs = -1L
                waitUntil = nowMs() + accentMotionPeriodMs(style) - accentMotionDurationMs(style)
            }
        }
    } finally {
        setFrame(AccentMotionFrame.Rest)
    }
}

/** A focused card and all its material-bearing children share this one finite clock. */
@Composable
internal fun AccentMotionScope(active: Boolean, content: @Composable () -> Unit) {
    val motion = rememberOwnedAccentMotion(active)
    CompositionLocalProvider(LocalAccentMotionState provides motion, content = content)
}

/** Standalone controls own a clock only while focused; card children reuse their parent. */
@Composable
internal fun rememberAccentMotionState(active: Boolean): State<AccentMotionFrame>? =
    LocalAccentMotionState.current ?: rememberOwnedAccentMotion(active)

@Composable
private fun rememberOwnedAccentMotion(active: Boolean): State<AccentMotionFrame>? {
    val mode = LocalAccentMotionMode.current
    if (!active || !LocalAccentMotionAllowed.current ||
        !accentMotionAllowed(mode, LocalPerformanceProfile.current)) return null
    val style = LocalAccentStyle.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState = lifecycle.currentStateFlow.collectAsState()
    val window = LocalWindowInfo.current
    val scale = rememberAccentMotionDurationScale()
    val state = remember(style, mode) { mutableStateOf(AccentMotionFrame.Rest) }
    LaunchedEffect(style, mode, lifecycle, window, state) {
        fun foreground() = lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED) &&
            window.isWindowFocused && scale.value > 0f
        runAccentMotion(
            mode = mode,
            style = style,
            setFrame = { state.value = it },
            nowMs = android.os.SystemClock::uptimeMillis,
            interacting = { BrowseWorkBudget.recentlyInteracting(quietMs = 100L) },
            active = ::foreground,
            awaitActive = { snapshotFlow { foreground() }.first { it } },
            durationScale = { scale.value },
        )
    }
    return state
}

/** Custom frame clocks must honor Android's animation setting just like animate*AsState. */
@Composable
private fun rememberAccentMotionDurationScale(): State<Float> {
    val resolver = LocalContext.current.contentResolver
    fun readScale(): Float = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        .takeIf { it.isFinite() && it >= 0f } ?: 1f
    val scale = remember(resolver) { mutableFloatStateOf(readScale()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale.floatValue = readScale() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        // Close the read/register race when settings changed as this target gained focus.
        scale.floatValue = readScale()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}
