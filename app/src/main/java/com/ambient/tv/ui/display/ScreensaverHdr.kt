package com.ambient.tv.ui.display

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import com.ambient.tv.ui.player.sdrHdrSizeAllowed
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/** Colour-managed AGSL, not PQ bytes in an SDR Compose layer. Android owns the final transfer. */
internal const val SCREENSAVER_HDR_SHADER = """
    uniform shader content;
    uniform float headroom;
    half4 main(float2 xy) {
        half4 c = content.eval(xy);
        if (c.a <= 0.0) return c;
        float3 rgb = toLinearSrgb(c.rgb / c.a);
        float peak = max(rgb.r, max(rgb.g, rgb.b));
        // Preserve already-HDR colours. Never boost native HDR a second time.
        if (peak > 1.0) return c;
        float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
        float highlight = smoothstep(0.5, 1.0, luma);
        float gain = 1.0 + (headroom - 1.0) * highlight * highlight;
        return half4(fromLinearSrgb(rgb * gain) * c.a, c.a);
    }
"""

/**
 * Background only: the clock/OLED mode must never gain luminance. [shaderActive] reports whether
 * this shader lifts the background, so content inside must not apply its own HDR gain on top.
 */
@Composable
internal fun Modifier.screensaverHdrBackground(eligible: Boolean, shaderActive: MutableState<Boolean>? = null): Modifier {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var request by remember { mutableStateOf<SdrToHdrRequest?>(null) }
    var failed by remember { mutableStateOf(false) }
    val painted = remember(request) { AtomicBoolean(false) }
    val view = LocalView.current
    val headroom = LocalUiHdrState.current?.headroom?.coerceIn(1f, UI_HDR_HEADROOM_CAP) ?: 1f
    val allowed = eligible && !failed && Build.VERSION.SDK_INT >= 34 && view.isHardwareAccelerated &&
        sdrHdrSizeAllowed(size.width, size.height, 60f)
    RegisterAmbientHdr(windowOutput = true, priority = 2, eligible = allowed) { next ->
        request = next.takeIf { allowed }
        when {
            next == null -> SdrToHdrStatus.OFF
            failed -> SdrToHdrStatus.FAILED
            !allowed -> SdrToHdrStatus.UNAVAILABLE
            painted.get() -> SdrToHdrStatus.ACTIVE
            else -> SdrToHdrStatus.WAITING
        }
    }
    val effect = remember(request, allowed, headroom) {
        if (request == null || !allowed || headroom <= 1f || Build.VERSION.SDK_INT < 34) null else runCatching {
            val shader = RuntimeShader(SCREENSAVER_HDR_SHADER)
            shader.setFloatUniform("headroom", headroom)
            RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        }
    }
    LaunchedEffect(effect) {
        effect?.exceptionOrNull()?.let {
            Timber.w(it, "Screensaver HDR disabled; retaining SDR background")
            failed = true
        }
    }
    // Observe sustained frame stalls without reading pixels or changing the saved quality level.
    LaunchedEffect(request, allowed) {
        if (request == null || !allowed) return@LaunchedEffect
        val hz = view.display?.refreshRate?.takeIf { it.isFinite() && it > 0 } ?: return@LaunchedEffect
        val budget = 1_000_000_000.0 / hz
        var previous = 0L
        var slow = 0
        while (true) withFrameNanos { frame ->
            if (previous != 0L) {
                slow = if (frame - previous > budget * 2.5) slow + 1 else 0
                if (slow >= 12) failed = true
            }
            previous = frame
        }
    }
    val renderEffect = effect?.getOrNull()
    val lifting = renderEffect != null && allowed
    SideEffect { if (shaderActive != null && shaderActive.value != lifting) shaderActive.value = lifting }
    val measure = this.onSizeChanged { size = it }
    if (renderEffect == null || !allowed) return measure
    return measure.graphicsLayer { this.renderEffect = renderEffect }
        .drawWithContent { drawContent(); painted.set(true) }
}
