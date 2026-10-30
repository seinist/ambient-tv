@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.ambient.tv.data.local.Nnedi3QualityCatalog
import com.ambient.tv.data.local.validateNnedi3ShaderSource
import com.ambient.tv.ui.player.backend.MPV_IMAGE_SHADERS

/**
 * NNEDI3 (bjin/mpv-prescalers, LGPL-3.0-or-later) for Media3 using the *original* MPV
 * inference weights. Two interpolation passes generate missing rows and columns. The shader
 * uses intBitsToFloat for exact packed NN weights, so GLES 3.0 is mandatory.
 */
internal val MEDIA3_NNEDI3_SCALERS: Set<String> =
    Nnedi3QualityCatalog.presets.mapTo(linkedSetOf()) { it.key }

internal data class Media3Nnedi3Stage(val name: String, val source: String)

/** Four upstream hooks, each with a distinct axis and original packed NN weights. */
internal fun media3Nnedi3Stages(source: String): List<Media3Nnedi3Stage>? {
    val positions = Regex("(?m)^//!DESC NNEDI3 \\(").findAll(source).map { it.range.first }.toList()
    if (positions.size != 4) return null
    val expected = listOf("double_y", "combine_y", "double_x", "combine_x")
    val stages = positions.mapIndexed { i, pos ->
        val block = source.substring(pos, positions.getOrNull(i + 1) ?: source.length)
        val lines = block.lines()
        val desc = lines.first()
        if (!desc.contains("(" + expected[i] + ",")) return null
        val bindings = lines.filter { it.startsWith("//!BIND ") }
            .map { it.removePrefix("//!BIND ").trim() }
        val save = lines.filter { it.startsWith("//!SAVE ") }
        val expectedBindings = if (i % 2 == 0) listOf("HOOKED") else listOf("HOOKED", "nnedi3_int")
        if (bindings != expectedBindings || save !=
            (if (i % 2 == 0) listOf("//!SAVE nnedi3_int") else emptyList<String>())) return null
        val body = lines.filterNot { it.startsWith("//!") }.joinToString("\n").trim()
        if (!body.contains("vec4 hook()") || (i % 2 == 0) != body.contains("intBitsToFloat") ||
            "gl_FragColor" in body || "#version" in body) return null
        Media3Nnedi3Stage(expected[i], body)
    }
    return stages
}

internal const val NNEDI3_VERTEX_ES3 = """#version 300 es
precision highp float;
in vec4 aFramePosition;
out vec2 vTexSamplingCoord;
void main() {
    gl_Position = aFramePosition;
    vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

/** The original NNEDI3 inference body is unchanged; only MPV hook sampling is adapted. */
internal fun media3Nnedi3Fragment(stage: Media3Nnedi3Stage): String = buildString {
    append(
        """#version 300 es
precision highp float;
in vec2 vTexSamplingCoord;
uniform sampler2D uHooked;
uniform sampler2D uPredict;
uniform vec2 uHookedSize;
uniform float uEncodedRgb;
out vec4 nnColor;
vec4 nnSample(vec2 uv) {
    vec3 rgb = texture(uHooked, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
    if (uEncodedRgb > 0.5) {
        rgb = pow(clamp(rgb, 0.0, 1.0), vec3(1.0 / 2.2));
        return vec4(dot(rgb, vec3(0.2126, 0.7152, 0.0722)), 0.0, 0.0, 1.0);
    }
    return vec4(rgb.r, 0.0, 0.0, 1.0);
}
#define HOOKED_pos vTexSamplingCoord
#define HOOKED_size uHookedSize
#define HOOKED_pt (1.0 / uHookedSize)
#define HOOKED_texOff(offset) nnSample(vTexSamplingCoord + (offset) / uHookedSize)
#define nnedi3_int_texOff(offset) texture(uPredict, clamp(vTexSamplingCoord + (offset) / uHookedSize, vec2(0.0), vec2(1.0)))
"""
    )
    append(stage.source.replace("vec4 hook()", "vec4 nnHook()")).append('\n')
    append("void main() { nnColor = nnHook(); }\n")
}

/** Compact source-per-second gate; high-neuron GLSL inference must never target large frames. */
internal fun media3Nnedi3BudgetAllows(
    scaler: String, width: Int, height: Int, outputWidth: Int, outputHeight: Int, fps: Float,
): Boolean {
    val preset = Nnedi3QualityCatalog.byKey[scaler] ?: return false
    if (width <= 0 || height <= 0 || outputWidth <= 0 || outputHeight <= 0) return false
    val (w, h) = media3FitSize(width, height, outputWidth, outputHeight)
    val scale = minOf(w.toDouble() / width, h.toDouble() / height)
    // MPV's HOOKED / OUTPUT < 0.707106 condition, on both width and height.
    if (scale <= 1.0 / 0.707106 || scale > 2.0) return false
    val rate = fps.takeIf { it.isFinite() && it > 0f } ?: 30f
    val maxPixels = when {
        preset.window == "win8x6" -> 128L * 72L
        preset.neurons >= 256 -> 160L * 90L
        preset.neurons >= 128 -> 256L * 144L
        preset.neurons >= 64 -> 360L * 202L
        preset.neurons >= 32 -> 480L * 270L
        else -> 640L * 360L
    }
    return width.toLong() * height * rate <= maxPixels * 24L
}

/** Exact packed NNEDI3 weights loaded from MPV; no learned approximation or faux sharpener. */
internal class Media3Nnedi3Effect(
    private val key: String,
    private val outputWidth: Int,
    private val outputHeight: Int,
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        media3FitSize(inputWidth, inputHeight, outputWidth, outputHeight) ==
            (inputWidth to inputHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("NNEDI3 is SDR-only")
        if (!GLES20.glGetString(GLES20.GL_VERSION).orEmpty().contains("OpenGL ES 3"))
            throw VideoFrameProcessingException("NNEDI3 needs OpenGL ES 3.0 for intBitsToFloat")
        media3RequireFragmentHighp("NNEDI3")
        val preset = Nnedi3QualityCatalog.byKey[key]
            ?: throw VideoFrameProcessingException("Unknown NNEDI3 preset " + key)
        val resource = MPV_IMAGE_SHADERS[key]?.resourceId
            ?: throw VideoFrameProcessingException("No weights for " + key)
        val text = context.resources.openRawResource(resource)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (!validateNnedi3ShaderSource(text, preset))
            throw VideoFrameProcessingException("Invalid NNEDI3 model " + key)
        val stages = media3Nnedi3Stages(text)
            ?: throw VideoFrameProcessingException("Unrecognized NNEDI3 hooks " + key)
        return Media3Nnedi3Program(stages, outputWidth, outputHeight)
    }
}

private class Media3Nnedi3Program(
    stages: List<Media3Nnedi3Stage>,
    private val targetWidth: Int,
    private val targetHeight: Int,
) : BaseGlShaderProgram(false, /* texturePoolCapacity= */ 1) {
    private val programs: List<GlProgram> = stages.map { stage ->
        try {
            GlProgram(NNEDI3_VERTEX_ES3, media3Nnedi3Fragment(stage)).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }
    private val composition = try {
        GlProgram(NNEDI3_VERTEX_ES3, """#version 300 es
precision highp float;
in vec2 vTexSamplingCoord;
uniform sampler2D uSource;
uniform sampler2D uNnedi;
out vec4 nnColor;
void main() {
    vec3 original = texture(uSource, vTexSamplingCoord).rgb;
    vec3 encoded = pow(clamp(original, 0.0, 1.0), vec3(1.0 / 2.2));
    float base = dot(encoded, vec3(0.2126, 0.7152, 0.0722));
    float prediction = texture(uNnedi, vTexSamplingCoord).r;
    nnColor = vec4(pow(clamp(encoded + vec3(prediction - base), 0.0, 1.0), vec3(2.2)), 1.0);
}
""").apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }

    private val targets = mutableListOf<Media3Intermediate>()
    private var width = 1
    private var height = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        clearTargets()
        width = inputWidth
        height = inputHeight
        val max = media3MaxTextureSize()
        if (inputWidth.toLong() * 2L > max || inputHeight.toLong() * 2L > max)
            throw VideoFrameProcessingException("NNEDI3 2x target exceeds GPU texture limit")
        try {
            targets += media3Intermediate(width, height, false)       // predict missing Y rows
            targets += media3Intermediate(width, height * 2, false)   // interleaved Y
            targets += media3Intermediate(width, height * 2, false)   // predict missing X columns
            targets += media3Intermediate(width * 2, height * 2, false) // interleaved X
        } catch (t: Throwable) {
            clearTargets()
            throw t
        }
        val (outW, outH) = media3FitSize(width, height, targetWidth, targetHeight)
        return Size(outW, outH)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val originalFbo = IntArray(1).also {
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0)
        }[0]
        val viewport = IntArray(4).also {
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0)
        }
        try {
            media3LinearFilter(inputTexId)
            targets.forEachIndexed { i, out ->
                val src = if (i < 2) inputTexId else targets[1].texture
                val h = if (i < 2) height else height * 2
                val outWidth = if (i == 3) width * 2 else width
                val outHeight = if (i == 0) height else height * 2
                GlUtil.focusFramebufferUsingCurrentContext(out.fbo, outWidth, outHeight)
                val p = programs[i]
                p.use()
                p.setSamplerTexIdUniform("uHooked", src, 0)
                if (i == 1 || i == 3) {
                    p.setSamplerTexIdUniform("uPredict", targets[if (i == 1) 0 else 2].texture, 1)
                }
                p.setFloatsUniformIfPresent("uHookedSize", floatArrayOf(width.toFloat(), h.toFloat()))
                p.setFloatsUniformIfPresent("uEncodedRgb", floatArrayOf(if (i < 2) 1f else 0f))
                media3DrawQuad(p)
            }
            GlUtil.focusFramebufferUsingCurrentContext(originalFbo, viewport[2], viewport[3])
            media3LinearFilter(targets[3].texture)
            composition.use()
            composition.setSamplerTexIdUniform("uSource", inputTexId, 0)
            composition.setSamplerTexIdUniform("uNnedi", targets[3].texture, 1)
            media3DrawQuad(composition)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        } finally {
            GlUtil.focusFramebufferUsingCurrentContext(originalFbo, viewport[2], viewport[3])
        }
    }

    private fun clearTargets() {
        targets.forEach(Media3Intermediate::release)
        targets.clear()
    }

    override fun release() {
        super.release()
        try {
            clearTargets()
            programs.forEach(GlProgram::delete)
            composition.delete()
        } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }
}
