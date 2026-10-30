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

/**
 * FSRCNNX (igv, GPL-3.0) uses learned coefficients from the existing MPV shader resources.
 * Unlike the MPV LUMA hook, Media3 receives linear RGB, so the model operates on encoded
 * Rec.709 luma and its 2x prediction is applied as a luminance correction to scaled RGB.
 * The 14/26 signed feature passes use separate FP16 textures, never normalized RGBA8.
 */
internal val MEDIA3_FSRCNNX_SCALERS: Set<String> = setOf("fsrcnnx8", "fsrcnnx16")

internal data class Media3FsrcnnxPass(
    val description: String,
    val bindings: List<String>,
    val save: String?,
    val body: String,
)

/** Exact MPV layout: 14 or 26 //!HOOK LUMA passes; named feature maps are reusable. */
internal fun media3FsrcnnxPasses(source: String): List<Media3FsrcnnxPass>? {
    val markers = Regex("(?m)^//!HOOK LUMA\\s*$").findAll(source).map { it.range.first }.toList()
    if (markers.size !in setOf(14, 26)) return null
    val known = mutableSetOf("LUMA")
    val passes = mutableListOf<Media3FsrcnnxPass>()
    markers.forEachIndexed { i, start ->
        val lines = source.substring(start, markers.getOrNull(i + 1) ?: source.length).lines()
        val desc = lines.firstOrNull { it.startsWith("//!DESC ") }
            ?.removePrefix("//!DESC ")?.trim() ?: return null
        val bindings = lines.filter { it.startsWith("//!BIND ") }
            .map { it.removePrefix("//!BIND ").trim() }
        val saves = lines.filter { it.startsWith("//!SAVE ") }
            .map { it.removePrefix("//!SAVE ").trim() }
        val body = lines.filterNot { it.startsWith("//!") }.joinToString("\n").trim()
        if (bindings.isEmpty() || bindings.distinct().size != bindings.size ||
            bindings.any { it !in known || !it.matches(Regex("[A-Z][A-Z0-9]*")) } ||
            saves.size != if (i == markers.lastIndex) 0 else 1 ||
            saves.any { it == "LUMA" || !it.matches(Regex("[A-Z][A-Z0-9]*")) } ||
            !body.contains("vec4 hook()") ||
            body.contains("#version") || "#extension" in body || "//!COMPUTE" in lines) return null
        // No output may be sampled while rendering to itself.
        if (saves.any { it in bindings }) return null
        val pass = Media3FsrcnnxPass(desc, bindings, saves.singleOrNull(), body)
        passes += pass
        pass.save?.let(known::add)
    }
    if (passes.first().bindings != listOf("LUMA") || passes.last().bindings != listOf("SUBCONV1") ||
        !passes.last().description.equals("aggregation", ignoreCase = true) ||
        passes[passes.lastIndex - 1].save != "SUBCONV1" ||
        passes.dropLast(1).any { it.save == null }) return null
    return passes
}

/** Translate only the verified MPV GLSL hook macros, without rewriting model weights. */
internal fun media3FsrcnnxFragment(pass: Media3FsrcnnxPass): String = buildString {
    append("#version 100\nprecision highp float;\nvarying vec2 vTexSamplingCoord;\n")
    append("uniform vec2 uInputSize;\n")
    pass.bindings.forEach { name -> append("uniform sampler2D u").append(name).append(";\n") }
    if ("LUMA" in pass.bindings) append(
        """
float inputLuma(vec2 uv) {
  vec3 linearRgb = texture2D(uLUMA, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
  vec3 encoded = pow(clamp(linearRgb, 0.0, 1.0), vec3(1.0 / 2.2));
  return dot(encoded, vec3(0.2126, 0.7152, 0.0722));
}
"""
    )
    pass.bindings.forEach { name ->
        append("#define ").append(name).append("_pos vTexSamplingCoord\n")
        append("#define ").append(name).append("_size uInputSize\n")
        append("#define ").append(name).append("_pt (1.0 / uInputSize)\n")
        if (name == "LUMA") {
            append("#define LUMA_texOff(offset) inputLuma(vTexSamplingCoord + (offset) / uInputSize)\n")
        } else {
            append("#define ").append(name).append("_texOff(offset) texture2D(u")
                .append(name).append(", clamp(vTexSamplingCoord + (offset) / uInputSize, vec2(0.0), vec2(1.0)))\n")
            append("#define ").append(name).append("_tex(uv) texture2D(u")
                .append(name).append(", clamp((uv), vec2(0.0), vec2(1.0)))\n")
        }
    }
    append(pass.body.replace(Regex("\\bmat4x4\\b"), "mat4")
        .replace("vec4 hook()", "vec4 cnnHook()")).append('\n')
    append("void main() { gl_FragColor = cnnHook(); }\n")
}

/** Match MPV's 1.3x guard, but cap expensive fragment CNNs until device audits qualify them. */
internal fun media3FsrcnnxBudgetAllows(
    scaler: String, width: Int, height: Int, outputWidth: Int, outputHeight: Int, fps: Float,
): Boolean {
    if (scaler !in MEDIA3_FSRCNNX_SCALERS || width <= 0 || height <= 0 ||
        outputWidth <= 0 || outputHeight <= 0) return false
    val (w, h) = media3FitSize(width, height, outputWidth, outputHeight)
    val scale = minOf(w.toDouble() / width, h.toDouble() / height)
    if (scale <= 1.3 || scale > 2.0) return false
    val rate = fps.takeIf { it.isFinite() && it > 0f } ?: 30f
    // 8-feature: 640x360/30. 16-feature: 480x270/30. MPV's full 2x 4K load is unsafe
    // with 25 FP16 feature buffers on many mobile GPUs; keep the budget deliberately low.
    val sourcePixels = if (scaler == "fsrcnnx16") 480L * 270L else 640L * 360L
    return width.toLong() * height * rate <= sourcePixels * 30L &&
        w.toLong() * h * rate <= sourcePixels * 120L
}

internal class Media3FsrcnnxEffect(
    private val key: String,
    private val outputWidth: Int,
    private val outputHeight: Int,
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        media3FitSize(inputWidth, inputHeight, outputWidth, outputHeight) ==
            (inputWidth to inputHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("FSRCNNX is SDR-only")
        media3RequireFragmentHighp("FSRCNNX")
        val resource = MPV_IMAGE_SHADERS[key]?.resourceId
            ?: throw VideoFrameProcessingException("No FSRCNNX model for " + key)
        val source = context.resources.openRawResource(resource).bufferedReader().use { it.readText() }
        val passes = media3FsrcnnxPasses(source)
            ?: throw VideoFrameProcessingException("Invalid FSRCNNX shader: " + key)
        return Media3FsrcnnxShaderProgram(passes, outputWidth, outputHeight)
    }
}

private class Media3FsrcnnxShaderProgram(
    private val passes: List<Media3FsrcnnxPass>,
    private val targetWidth: Int,
    private val targetHeight: Int,
) : BaseGlShaderProgram(false, /* texturePoolCapacity= */ 1) {
    private val programs: List<GlProgram> = passes.map { media3GlProgram(media3FsrcnnxFragment(it)) }
    private val compose = media3GlProgram(
        """#version 100
precision highp float;
varying vec2 vTexSamplingCoord;
uniform sampler2D uOriginal;
uniform sampler2D uPrediction;
void main() {
  vec3 original = texture2D(uOriginal, vTexSamplingCoord).rgb;
  vec3 encoded = pow(clamp(original, 0.0, 1.0), vec3(1.0 / 2.2));
  float oldLuma = dot(encoded, vec3(0.2126, 0.7152, 0.0722));
  float predicted = texture2D(uPrediction, vTexSamplingCoord).r;
  // Match the predicted luma while keeping the original chroma differences.
  vec3 corrected = clamp(encoded + vec3(predicted - oldLuma), 0.0, 1.0);
  gl_FragColor = vec4(pow(corrected, vec3(2.2)), 1.0);
}
"""
    )
    private val targets = mutableListOf<Media3Intermediate>()
    private var prediction: Media3Intermediate? = null
    private var width = 1
    private var height = 1
    private var fittedWidth = 1
    private var fittedHeight = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        releaseTargets()
        width = inputWidth
        height = inputHeight
        val fitted = media3FitSize(inputWidth, inputHeight, targetWidth, targetHeight)
        fittedWidth = fitted.first
        fittedHeight = fitted.second
        val maxTex = media3MaxTextureSize()
        if (inputWidth.toLong() * 2L > maxTex || inputHeight.toLong() * 2L > maxTex)
            throw VideoFrameProcessingException("FSRCNNX prediction exceeds maximum texture size")
        try {
            repeat(passes.size - 1) {
                targets += media3SsimIntermediate(width, height)
            }
            // SUBCONV1 predicts a true 2x luma plane; interpolation to the display happens
            // only after the learned subpixel shuffle, never inside its feature maps.
            prediction = media3Intermediate(width * 2, height * 2, false)
        } catch (t: Throwable) {
            releaseTargets()
            throw t
        }
        return Size(fittedWidth, fittedHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val originalFbo = IntArray(1).also {
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0)
        }[0]
        val viewport = IntArray(4).also {
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0)
        }
        val bindings = mutableMapOf<String, Int>("LUMA" to inputTexId)
        try {
            media3LinearFilter(inputTexId)
            passes.forEachIndexed { index, pass ->
                val last = index == passes.lastIndex
                val out = if (last) checkNotNull(prediction) else targets[index]
                GlUtil.focusFramebufferUsingCurrentContext(out.fbo,
                    if (last) width * 2 else width, if (last) height * 2 else height)
                val program = programs[index]
                program.use()
                pass.bindings.forEachIndexed { textureUnit, name ->
                    program.setSamplerTexIdUniform("u" + name, checkNotNull(bindings[name]), textureUnit)
                }
                program.setFloatsUniformIfPresent("uInputSize",
                    floatArrayOf(width.toFloat(), height.toFloat()))
                media3DrawQuad(program)
                pass.save?.let { bindings[it] = out.texture }
            }
            GlUtil.focusFramebufferUsingCurrentContext(originalFbo, viewport[2], viewport[3])
            media3LinearFilter(checkNotNull(prediction).texture)
            compose.use()
            compose.setSamplerTexIdUniform("uOriginal", inputTexId, 0)
            compose.setSamplerTexIdUniform("uPrediction", checkNotNull(prediction).texture, 1)
            media3DrawQuad(compose)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        } finally {
            GlUtil.focusFramebufferUsingCurrentContext(originalFbo, viewport[2], viewport[3])
        }
    }

    private fun releaseTargets() {
        targets.forEach(Media3Intermediate::release)
        targets.clear()
        prediction?.release()
        prediction = null
    }

    override fun release() {
        super.release()
        try {
            releaseTargets()
            programs.forEach(GlProgram::delete)
            compose.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}
