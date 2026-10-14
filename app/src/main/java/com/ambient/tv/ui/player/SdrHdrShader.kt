package com.ambient.tv.ui.player

/** Only BT.709 primaries with an explicit SDR transfer. Unknown/BT.601/HDR never guessed. */
internal fun sdrHdrTransfer(dataSpace: Int): Int? {
    val standard = dataSpace and (63 shl 16)
    val transfer = dataSpace and (31 shl 22)
    val range = dataSpace and (7 shl 27)
    if (standard != 1 shl 16 || range !in setOf(1 shl 27, 2 shl 27)) return null
    return when (transfer) {
        2 shl 22 -> 1 // sRGB
        3 shl 22 -> 2 // SMPTE 170M / BT.709
        4 shl 22 -> 3 // gamma 2.2
        5 shl 22 -> 4 // gamma 2.6
        6 shl 22 -> 5 // gamma 2.8
        else -> null
    }
}

/**
 * GPU budget for the extra 10-bit PQ conversion pass. Video and screensavers always use
 * [FULL_HD]. [UHD] is an explicit opt-in for projectM only; the per-frame overload checks in
 * [SdrHdrGlSession] still revoke HDR for the session when the device cannot keep up.
 */
internal data class SdrHdrSizeBudget(val maxSide: Int, val maxPixels: Long, val maxFps: Float) {
    fun allows(width: Int, height: Int, fps: Float): Boolean =
        width in 1..maxSide && height in 1..maxSide && width.toLong() * height <= maxPixels &&
            fps.isFinite() && fps > 0f && fps <= maxFps

    companion object {
        val FULL_HD = SdrHdrSizeBudget(1920, 1920L * 1080, 60f)
        val UHD = SdrHdrSizeBudget(3840, 3840L * 2160, 60f)
    }
}

internal fun sdrHdrSizeAllowed(width: Int, height: Int, fps: Float, budget: SdrHdrSizeBudget = SdrHdrSizeBudget.FULL_HD): Boolean =
    budget.allows(width, height, fps)

/**
 * Present time to request for a converted frame. The producer's buffer timestamp is its intended
 * display time (MediaCodec render time) in the `System.nanoTime` base; honouring it keeps the
 * extra GL pass from moving the picture against the audio. A timestamp outside a plausible
 * window (media time instead of a clock, a stale frame) is ignored and the frame shows at once.
 */
internal fun sdrHdrPresentationTimeNs(bufferTimestampNs: Long, nowNs: Long): Long? =
    bufferTimestampNs.takeIf { it in (nowNs - 100_000_000L)..(nowNs + 500_000_000L) }

/**
 * EGL surface attributes for SMPTE ST 2086 / CTA-861.3 static metadata, scaled by
 * EGL_METADATA_SCALING_EXT (50000). The converted picture is BT.709 content inside the BT.2020 PQ
 * container, so the mastering primaries are BT.709 with D65 and its peak is [peakNits].
 */
internal fun sdrHdrStaticMetadata(peakNits: Float, extensions: Set<String>): List<Pair<Int, Int>> {
    if (!peakNits.isFinite() || peakNits <= 0f) return emptyList()
    fun scaled(value: Double) = (value * 50_000).toInt()
    val peak = peakNits.toDouble()
    return buildList {
        if ("EGL_EXT_surface_SMPTE2086_metadata" in extensions) {
            add(0x3341 to scaled(0.640)); add(0x3342 to scaled(0.330)) // red x, y
            add(0x3343 to scaled(0.300)); add(0x3344 to scaled(0.600)) // green
            add(0x3345 to scaled(0.150)); add(0x3346 to scaled(0.060)) // blue
            add(0x3347 to scaled(0.3127)); add(0x3348 to scaled(0.3290)) // D65 white
            add(0x3349 to scaled(peak)) // max mastering luminance
            add(0x334A to scaled(0.005)) // min mastering luminance
        }
        if ("EGL_EXT_surface_CTA861_3_metadata" in extensions) {
            add(0x3360 to scaled(peak)) // MaxCLL
            add(0x3361 to scaled(peak)) // Conservative MaxFALL bound: a full-white frame reaches peak.
        }
    }
}

/** GLSL operates on the external sampler's range-expanded RGB, NOT limited-range YUV. */
internal object SdrHdrShader {
    const val vertex = """
        attribute vec2 position;
        uniform mat4 textureMatrix;
        varying vec2 uv;
        void main() {
            gl_Position = vec4(position, 0.0, 1.0);
            uv = (textureMatrix * vec4(position * 0.5 + 0.5, 0.0, 1.0)).xy;
        }
    """
    const val fragment = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        uniform samplerExternalOES video;
        uniform int transfer;
        uniform float peakNits;
        varying vec2 uv;
        float linearize(float v) {
            if (transfer == 1) return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4);
            // BT.709/170M content is graded for a BT.1886 display (gamma 2.4), not the camera
            // OETF inverse, which would lift shadows and midtones above their SDR look.
            if (transfer == 2) return pow(v, 2.4);
            return pow(v, transfer == 3 ? 2.2 : (transfer == 4 ? 2.6 : 2.8));
        }
        vec3 pq(vec3 nits) {
            vec3 p = pow(max(nits, vec3(0.0)) / 10000.0, vec3(2610.0 / 16384.0));
            return pow((vec3(3424.0 / 4096.0) + (2413.0 / 128.0) * p) /
                       (vec3(1.0) + (2392.0 / 128.0) * p), vec3(2523.0 / 32.0));
        }
        void main() {
            vec3 s = clamp(texture2D(video, uv).rgb, 0.0, 1.0);
            vec3 linear = vec3(linearize(s.r), linearize(s.g), linearize(s.b));
            // BT.709 -> BT.2020 in linear light (GLSL matrices are column-major).
            vec3 wide = mat3(0.627404, 0.069097, 0.016391,
                             0.329283, 0.919540, 0.088013,
                             0.043313, 0.011362, 0.895595) * linear;
            // Keep darks/midtones at 203-nit reference; expand only highlights, preserving hue.
            float luma = dot(linear, vec3(0.2126, 0.7152, 0.0722));
            float highlight = smoothstep(0.5, 1.0, luma);
            float scale = 203.0 + (peakNits - 203.0) * highlight * highlight;
            gl_FragColor = vec4(pq(wide * scale), 1.0);
        }
    """
}
