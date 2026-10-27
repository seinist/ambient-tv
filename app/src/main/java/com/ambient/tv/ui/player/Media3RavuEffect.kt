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
import com.ambient.tv.ui.player.backend.MPV_IMAGE_SHADERS
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/*
 * RAVU-Lite (bjin, LGPL-3.0) for Media3, built at runtime from the same bundled MPV shader files:
 * the step-1 hook is translated to GLSL ES 1.00 and its float LUT is packed into two RGBA8
 * textures (16 bit per weight), so it needs neither GLES 3 nor float-texture extensions.
 *
 * MPV runs it on the luma plane; Media3 only has RGB. Pass 1 computes the four 2x luma values per
 * source pixel from Rec.709 luma, pass 2 places them on the 2x grid, pass 3 fits the result to the
 * output and adds the luma change to the bilinear RGB picture (chroma stays bilinear, as in MPV).
 */

/** Step-1 GLSL (a `vec4 ravu(vec2 pos)` function) and its LUT, from an MPV RAVU-Lite shader. */
internal class Media3RavuSource(val step1: String, val lutWidth: Int, val lutHeight: Int, val lut: FloatArray)

/** RAVU-Lite only runs from about 1.41x on, like MPV's `//!WHEN`; below that it is plain bilinear. */
internal const val MEDIA3_RAVU_MIN_SCALE = 1.0 / 0.707106

/** The MPV catalog keys Media3 offers as upscalers. */
internal val MEDIA3_RAVU_SCALERS = listOf("ravu_lite_r2", "ravu_lite_r3", "ravu_lite_r4")

// MPV's step-1 lines that pick a float by a bool through mix(); GLSL ES 1.00 has no such overload.
private val RAVU_BOOL_MIX = mapOf(
    "float theta = mix(mod(atan(L1 - a, b) + 3.141592653589793, 3.141592653589793), 0.0, abs(b) < 1.192092896e-7);" to
        "float theta = abs(b) < 1.192092896e-7 ? 0.0 : mod(atan(L1 - a, b) + 3.141592653589793, 3.141592653589793);",
    "float mu = mix((sqrtL1 - sqrtL2) / (sqrtL1 + sqrtL2), 0.0, sqrtL1 + sqrtL2 < 1.192092896e-7);" to
        "float mu = sqrtL1 + sqrtL2 < 1.192092896e-7 ? 0.0 : (sqrtL1 - sqrtL2) / (sqrtL1 + sqrtL2);",
    "float strength = mix(mix(0.0, 1.0, lambda >= 0.004), mix(2.0, 3.0, lambda >= 0.05), lambda >= 0.016);" to
        "float strength = lambda >= 0.016 ? (lambda >= 0.05 ? 3.0 : 2.0) : (lambda >= 0.004 ? 1.0 : 0.0);",
    "float coherence = mix(mix(0.0, 1.0, mu >= 0.25), 2.0, mu >= 0.5);" to
        "float coherence = mu >= 0.5 ? 2.0 : (mu >= 0.25 ? 1.0 : 0.0);",
)

private val RAVU_TAP = Regex("""HOOKED_texOff\((vec2\([^()]*\))\)\.x""")

/** Null when the file does not have the expected RAVU-Lite layout; the caller then stays bilinear. */
internal fun media3RavuSource(mpvShader: String): Media3RavuSource? {
    val start = mpvShader.indexOf("vec4 hook() {")
    val step2 = mpvShader.indexOf("//!DESC RAVU-Lite-AR (step2")
    if (start < 0 || step2 < start) return null
    val lutName = Regex("""//!BIND (ravu_lite_lut\d)""").find(mpvShader)?.groupValues?.get(1) ?: return null
    var body = mpvShader.substring(start, step2).trimEnd()
    for ((mpv, es) in RAVU_BOOL_MIX) {
        if (mpv !in body) return null
        body = body.replace(mpv, es)
    }
    body = body.replace("vec4 hook() {", "vec4 ravu(vec2 pos) {")
        .replace(RAVU_TAP) { "lumaAt(pos + ${it.groupValues[1]})" }
        .replace("texture($lutName, ", "lut(")
    if (listOf("HOOKED", "texture(", lutName).any { it in body }) return null

    val texture = mpvShader.indexOf("//!TEXTURE $lutName")
    if (texture < 0) return null
    val header = mpvShader.substring(texture)
    val (width, height) = Regex("""//!SIZE (\d+) (\d+)""").find(header)?.destructured
        ?.let { (w, h) -> w.toInt() to h.toInt() } ?: return null
    val hex = header.lineSequence().filterNot { it.startsWith("//!") }.joinToString("") { it.trim() }
    // MPV stores the LUT as little-endian float32 and converts it on upload.
    val count = width * height * 4
    if (hex.length != count * 8) return null
    val lut = FloatArray(count) { i ->
        var bits = 0
        for (b in 3 downTo 0) {
            val at = i * 8 + b * 2
            bits = (bits shl 8) or hex.substring(at, at + 2).toInt(16)
        }
        Float.fromBits(bits)
    }
    if (lut.any { !it.isFinite() }) return null
    return Media3RavuSource(body, width, height, lut)
}

/** The LUT as two RGBA8 planes (high and low byte of a 16-bit value) plus its range. */
internal class Media3RavuLutPlanes(val high: ByteArray, val low: ByteArray, val min: Float, val range: Float)

internal fun media3RavuLutPlanes(lut: FloatArray): Media3RavuLutPlanes {
    val min = lut.min()
    val range = (lut.max() - min).takeIf { it > 0f } ?: 1f
    val high = ByteArray(lut.size)
    val low = ByteArray(lut.size)
    lut.forEachIndexed { i, v ->
        val q = (((v - min) / range) * 65535f + 0.5f).toInt().coerceIn(0, 65535)
        high[i] = (q ushr 8).toByte()
        low[i] = (q and 0xff).toByte()
    }
    return Media3RavuLutPlanes(high, low, min, range)
}

/** Decodes what the shader's `lut()` returns for one packed value (tests mirror the GLSL). */
internal fun media3RavuLutDecode(planes: Media3RavuLutPlanes, index: Int): Float {
    val q = (planes.high[index].toInt() and 0xff) * 256 + (planes.low[index].toInt() and 0xff)
    return q / 65535f * planes.range + planes.min
}

private val ravuSources = ConcurrentHashMap<String, Media3RavuSource>()

private fun ravuSource(context: Context, scaler: String): Media3RavuSource? = ravuSources[scaler] ?: run {
    val resource = MPV_IMAGE_SHADERS[scaler]?.resourceId ?: return null
    val text = runCatching { context.resources.openRawResource(resource).bufferedReader().use { it.readText() } }.getOrNull()
    text?.let(::media3RavuSource)?.also { ravuSources[scaler] = it }
}

/** RAVU-Lite 2x, fitted to the output size like the other Media3 upscalers. */
internal class Media3RavuEffect(
    private val scaler: String,
    private val outputWidth: Int,
    private val outputHeight: Int,
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        media3FitSize(inputWidth, inputHeight, outputWidth, outputHeight) == (inputWidth to inputHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val source = ravuSource(context, scaler)
            ?: throw VideoFrameProcessingException("RAVU shader $scaler unavailable")
        return Media3RavuShaderProgram(useHdr, source, outputWidth, outputHeight)
    }
}

private class Media3RavuShaderProgram(
    useHighPrecision: Boolean,
    source: Media3RavuSource,
    private val outputWidth: Int,
    private val outputHeight: Int,
) : BaseGlShaderProgram(useHighPrecision, /* texturePoolCapacity= */ 1) {
    private val useHighPrecision = useHighPrecision
    private val step1 = program(RAVU_HEADER + RAVU_LUT_HEADER + source.step1 + RAVU_STEP1_MAIN)
    private val step2 = program(RAVU_HEADER + RAVU_STEP2)
    private val fit = program(RAVU_HEADER + RAVU_FIT)
    private val planes = media3RavuLutPlanes(source.lut)
    private val lutHigh = lutTexture(planes.high, source.lutWidth, source.lutHeight)
    private val lutLow = lutTexture(planes.low, source.lutWidth, source.lutHeight)
    private var inputWidth = 1
    private var inputHeight = 1
    private var ravuActive = false
    private var intTexture = -1
    private var intFbo = -1
    private var lumaTexture = -1
    private var lumaFbo = -1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        releaseIntermediates()
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        val (width, height) = media3FitSize(inputWidth, inputHeight, outputWidth, outputHeight)
        ravuActive = minOf(width.toDouble() / inputWidth, height.toDouble() / inputHeight) >= MEDIA3_RAVU_MIN_SCALE
        if (ravuActive) {
            try {
                intTexture = GlUtil.createTexture(inputWidth, inputHeight, useHighPrecision)
                intFbo = GlUtil.createFboForTexture(intTexture)
                lumaTexture = GlUtil.createTexture(inputWidth * 2, inputHeight * 2, useHighPrecision)
                lumaFbo = GlUtil.createFboForTexture(lumaTexture)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val outputFbo = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }[0]
            val viewport = IntArray(4).also { GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0) }
            val inputSize = floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat())
            linear(inputTexId)
            if (ravuActive) {
                // Pass 1: four 2x luma values per source pixel.
                GlUtil.focusFramebufferUsingCurrentContext(intFbo, inputWidth, inputHeight)
                step1.use()
                step1.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                step1.setSamplerTexIdUniform("uLutHigh", lutHigh, 1)
                step1.setSamplerTexIdUniform("uLutLow", lutLow, 2)
                step1.setFloatsUniformIfPresent("uInputSize", inputSize)
                step1.setFloatsUniformIfPresent("uLutMin", floatArrayOf(planes.min))
                step1.setFloatsUniformIfPresent("uLutRange", floatArrayOf(planes.range))
                draw(step1)
                // Pass 2: each 2x pixel takes its quarter of the source pixel.
                GlUtil.focusFramebufferUsingCurrentContext(lumaFbo, inputWidth * 2, inputHeight * 2)
                step2.use()
                step2.setSamplerTexIdUniform("uTexSampler", intTexture, 0)
                step2.setFloatsUniformIfPresent("uInputSize", inputSize)
                draw(step2)
                linear(lumaTexture)
            }
            // Pass 3: bilinear RGB fitted to the output, plus the RAVU luma change.
            GlUtil.focusFramebufferUsingCurrentContext(outputFbo, viewport[2], viewport[3])
            fit.use()
            fit.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            fit.setSamplerTexIdUniform("uLuma", if (ravuActive) lumaTexture else inputTexId, 1)
            fit.setFloatsUniformIfPresent("uInputSize", inputSize)
            fit.setFloatsUniformIfPresent("uRavu", floatArrayOf(if (ravuActive) 1f else 0f))
            draw(fit)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            releaseIntermediates()
            GlUtil.deleteTexture(lutHigh)
            GlUtil.deleteTexture(lutLow)
            step1.delete()
            step2.delete()
            fit.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private fun releaseIntermediates() {
        if (intFbo != -1) GlUtil.deleteFbo(intFbo)
        if (intTexture != -1) GlUtil.deleteTexture(intTexture)
        if (lumaFbo != -1) GlUtil.deleteFbo(lumaFbo)
        if (lumaTexture != -1) GlUtil.deleteTexture(lumaTexture)
        intFbo = -1; intTexture = -1; lumaFbo = -1; lumaTexture = -1
    }

    private fun draw(program: GlProgram) {
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    private fun program(fragment: String): GlProgram = try {
        GlProgram(RAVU_VERTEX, fragment).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    private fun linear(texture: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    /** NEAREST RGBA8 texture: the shader reads exact texel centers like MPV's `//!FILTER NEAREST`. */
    private fun lutTexture(bytes: ByteArray, width: Int, height: Int): Int {
        val id = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { position(0) }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        try { GlUtil.checkGlError() } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
        return id
    }
}

private const val RAVU_VERTEX = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

private const val RAVU_HEADER = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uInputSize;
varying vec2 vTexSamplingCoord;
float lumaOf(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
"""

private const val RAVU_LUT_HEADER = """
uniform sampler2D uLutHigh;
uniform sampler2D uLutLow;
uniform float uLutMin;
uniform float uLutRange;
float lumaAt(vec2 texel) { return lumaOf(texture2D(uTexSampler, (texel + 0.5) / uInputSize).rgb); }
vec4 lut(vec2 uv) {
  vec4 q = texture2D(uLutHigh, uv) * (255.0 * 256.0) + texture2D(uLutLow, uv) * 255.0;
  return q / 65535.0 * uLutRange + uLutMin;
}
"""

private const val RAVU_STEP1_MAIN = """
void main() {
  gl_FragColor = clamp(ravu(floor(vTexSamplingCoord * uInputSize)), 0.0, 1.0);
}
"""

// MPV step 2: the quarter of the source pixel this 2x pixel lies in picks one of the four values.
private const val RAVU_STEP2 = """
void main() {
  vec2 src = vTexSamplingCoord * uInputSize;
  vec2 dir = fract(src) - 0.5;
  vec4 q = texture2D(uTexSampler, (floor(src) + 0.5) / uInputSize);
  float y = dir.x > 0.0 ? (dir.y > 0.0 ? q.w : q.z) : (dir.y > 0.0 ? q.y : q.x);
  gl_FragColor = vec4(y, y, y, 1.0);
}
"""

private const val RAVU_FIT = """
uniform sampler2D uLuma;
uniform float uRavu;
void main() {
  vec3 base = texture2D(uTexSampler, vTexSamplingCoord).rgb;
  float y = texture2D(uLuma, vTexSamplingCoord).r;
  vec3 color = base + uRavu * (y - lumaOf(base));
  gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
"""
