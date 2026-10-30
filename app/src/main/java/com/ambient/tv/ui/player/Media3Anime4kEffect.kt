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

/** The same four Anime4K v3.2 CNN models bundled for MPV (MIT, bloc97). */
internal val MEDIA3_ANIME4K_SCALERS = setOf(
    "anime4k", "anime4k_denoise", "anime4k_m", "anime4k_denoise_m",
)

/**
 * A CNN pass from the existing MPV shader asset. A separate GL program is needed for each
 * //!SAVE: learned feature maps are *signed* and must not be clamped into an RGBA8 texture.
 */
internal data class Media3Anime4kPass(
    val description: String,
    val bindings: List<String>,
    val save: String,
    val body: String,
)

/** Strict parser for the shipped MPV Anime4K shader dialect; malformed assets fail closed. */
internal fun media3Anime4kPasses(source: String): List<Media3Anime4kPass>? {
    val permitted = setOf("MAIN", "conv2d_tf", "conv2d_1_tf", "conv2d_2_tf",
        "conv2d_3_tf", "conv2d_4_tf", "conv2d_5_tf", "conv2d_6_tf", "conv2d_last_tf")
    val starts = Regex("(?m)^//!DESC ").findAll(source).map { it.range.first }.toList()
    if (starts.size !in setOf(5, 9)) return null
    val known = mutableSetOf("MAIN")
    val passes = starts.mapIndexed { i, start ->
        val lines = source.substring(start, starts.getOrNull(i + 1) ?: source.length).lines()
        val description = lines.first().removePrefix("//!DESC ").trim()
        val bindings = lines.filter { it.startsWith("//!BIND ") }
            .map { it.removePrefix("//!BIND ").trim() }
        val saves = lines.filter { it.startsWith("//!SAVE ") }
            .map { it.removePrefix("//!SAVE ").trim() }
        val code = lines.filterNot { it.startsWith("//!") }.joinToString("\n").trim()
        if (!description.startsWith("Anime4K-v3.2-") || bindings.isEmpty() ||
            bindings.any { it !in known || it !in permitted } ||
            saves.size != 1 || saves[0] !in permitted ||
            (saves[0] == "MAIN") != (i == starts.lastIndex) ||
            (saves[0] != "MAIN" && saves[0] in known) ||
            !code.contains("vec4 hook()") || code.contains("#version")) return null
        known += saves[0]
        Media3Anime4kPass(description, bindings, saves[0], code)
    }
    if (passes.first().bindings != listOf("MAIN") ||
        passes.last().bindings != listOf("MAIN", "conv2d_last_tf") ||
        passes[passes.lastIndex - 1].save != "conv2d_last_tf" ||
        passes.dropLast(1).any { it.save == "MAIN" }) return null
    // S: 4 convolution passes; M: 7 convolutions plus a 1x1 feature combination.
    if ((passes.size == 5 && passes.dropLast(1).any { it.bindings.size != 1 }) ||
        (passes.size == 9 && passes[7].bindings.size != 7)) return null
    return passes
}

/**
 * MPV MAIN is gamma-encoded in the bundled model, whereas Media3 feeds linear BT.709 RGB
 * into GlEffect. Encode only the original input samples, and decode the final reconstructed RGB.
 * The learned mat4 weights and all intermediate signed feature values remain unchanged.
 */
internal fun media3Anime4kFragment(pass: Media3Anime4kPass): String = buildString {
    append("#version 100\nprecision highp float;\n")
    append("varying vec2 vTexSamplingCoord;\nuniform vec2 uInputSize;\n")
    pass.bindings.forEach { name -> append("uniform sampler2D u").append(name).append(";\n") }
    if ("MAIN" in pass.bindings) {
        append(
            """
vec4 animeInput(vec2 uv) {
  vec4 c = texture2D(uMAIN, clamp(uv, vec2(0.0), vec2(1.0)));
  return vec4(pow(clamp(c.rgb, 0.0, 1.0), vec3(1.0 / 2.2)), c.a);
}
"""
        )
    }
    for (name in pass.bindings) {
        append("#define ").append(name).append("_pos vTexSamplingCoord\n")
        append("#define ").append(name).append("_size uInputSize\n")
        append("#define ").append(name).append("_pt (1.0 / uInputSize)\n")
        if (name == "MAIN") {
            append("#define MAIN_tex(uv) animeInput((uv))\n")
            append("#define MAIN_texOff(offset) animeInput(vTexSamplingCoord + (offset) / uInputSize)\n")
        } else {
            append("#define ").append(name).append("_tex(uv) texture2D(u").append(name).append(", (uv))\n")
            append("#define ").append(name).append("_texOff(offset) texture2D(u").append(name)
                .append(", vTexSamplingCoord + (offset) / uInputSize)\n")
        }
    }
    append(pass.body.replace("vec4 hook()", "vec4 animeHook()")).append('\n')
    if (pass.save == "MAIN") {
        append(
            """
void main() {
  vec4 encoded = animeHook();
  gl_FragColor = vec4(pow(clamp(encoded.rgb, 0.0, 1.0), vec3(2.2)), 1.0);
}
"""
        )
    } else append("void main() { gl_FragColor = animeHook(); }\n")
}

/** A conservative bound for multi-pass Anime4K. M uses eight signed feature maps. */
internal fun media3Anime4kBudgetAllows(
    scaler: String, width: Int, height: Int, outputWidth: Int, outputHeight: Int, fps: Float,
): Boolean {
    if (scaler !in MEDIA3_ANIME4K_SCALERS || width <= 0 || height <= 0 ||
        outputWidth <= 0 || outputHeight <= 0) return false
    val (outW, outH) = media3FitSize(width, height, outputWidth, outputHeight)
    val enlargement = minOf(outW.toDouble() / width, outH.toDouble() / height)
    val rate = fps.takeIf { it.isFinite() && it > 0f } ?: 30f
    // MPV's //!WHEN requires at least 1.2x on both axes; no 3x+ CNN extrapolation.
    if (enlargement <= 1.2 || enlargement > 2.0) return false
    val sourceRate = if (scaler.endsWith("_m")) 960L * 540L * 30L else 1280L * 720L * 30L
    return width.toLong() * height * rate <= sourceRate &&
        outW.toLong() * outH * rate <= 4L * sourceRate &&
        width <= 1920 && height <= 1080
}

/** Exact Anime4K model weights from MPV, evaluated on Media3's GL thread. */
internal class Media3Anime4kEffect(
    private val key: String,
    private val targetWidth: Int,
    private val targetHeight: Int,
) : GlEffect {
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        media3FitSize(inputWidth, inputHeight, targetWidth, targetHeight) ==
            (inputWidth to inputHeight)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("Anime4K CNN is SDR-only")
        media3RequireFragmentHighp("Anime4K")
        val resource = MPV_IMAGE_SHADERS[key]?.resourceId
            ?: throw VideoFrameProcessingException("Anime4K model " + key + " is unavailable")
        val shaderText = context.resources.openRawResource(resource)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val passes = media3Anime4kPasses(shaderText)
            ?: throw VideoFrameProcessingException("Invalid Anime4K model " + key)
        return Media3Anime4kProgram(passes, targetWidth, targetHeight)
    }
}

private class Media3Anime4kProgram(
    private val passes: List<Media3Anime4kPass>,
    private val targetWidth: Int,
    private val targetHeight: Int,
) : BaseGlShaderProgram(false, /* texturePoolCapacity= */ 1) {
    private val programs: List<GlProgram> = passes.map { media3GlProgram(media3Anime4kFragment(it)) }
    private val finish = media3GlProgram(
        """#version 100
precision highp float;
varying vec2 vTexSamplingCoord;
uniform sampler2D uImage;
void main() { gl_FragColor = texture2D(uImage, vTexSamplingCoord); }
"""
    )
    private val saved = linkedMapOf<String, Media3Intermediate>()
    private var fitTarget: Media3Intermediate? = null
    private var inputWidth = 1
    private var inputHeight = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        releaseTextures()
        val maxTexture = media3MaxTextureSize()
        if (inputWidth * 2L > maxTexture || inputHeight * 2L > maxTexture)
            throw VideoFrameProcessingException("Anime4K 2x texture exceeds GPU limit")
        val units = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, units, 0)
        if (passes.maxOf { it.bindings.size } > units[0])
            throw VideoFrameProcessingException("Anime4K needs more fragment samplers")
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        val (w, h) = media3FitSize(inputWidth, inputHeight, targetWidth, targetHeight)
        try {
            passes.dropLast(1).forEach { pass ->
                saved[pass.save] = media3SsimIntermediate(inputWidth, inputHeight)
            }
            if (w != inputWidth * 2 || h != inputHeight * 2) {
                // The model always predicts a 2x feature grid. Its result is fitted to the
                // actual panel with linear RGB; this never forces the display past its mode.
                fitTarget = media3Intermediate(inputWidth * 2, inputHeight * 2, false)
            }
        } catch (t: Throwable) {
            releaseTextures()
            throw t
        }
        return Size(w, h)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val fbo = IntArray(1).also {
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0)
        }[0]
        val viewport = IntArray(4).also {
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0)
        }
        try {
            passes.forEachIndexed { index, pass ->
                val last = index == passes.lastIndex
                if (last) {
                    fitTarget?.let {
                        GlUtil.focusFramebufferUsingCurrentContext(it.fbo, inputWidth * 2, inputHeight * 2)
                    } ?: GlUtil.focusFramebufferUsingCurrentContext(fbo, viewport[2], viewport[3])
                } else {
                    val t = checkNotNull(saved[pass.save])
                    GlUtil.focusFramebufferUsingCurrentContext(t.fbo, inputWidth, inputHeight)
                }
                val program = programs[index]
                program.use()
                pass.bindings.forEachIndexed { sampler, name ->
                    val texture = if (name == "MAIN") inputTexId else checkNotNull(saved[name]).texture
                    program.setSamplerTexIdUniform("u" + name, texture, sampler)
                }
                program.setFloatsUniformIfPresent("uInputSize",
                    floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
                media3DrawQuad(program)
            }
            fitTarget?.let { target ->
                GlUtil.focusFramebufferUsingCurrentContext(fbo, viewport[2], viewport[3])
                media3LinearFilter(target.texture)
                finish.use()
                finish.setSamplerTexIdUniform("uImage", target.texture, 0)
                media3DrawQuad(finish)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        } finally {
            GlUtil.focusFramebufferUsingCurrentContext(fbo, viewport[2], viewport[3])
        }
    }

    private fun releaseTextures() {
        saved.values.forEach(Media3Intermediate::release)
        saved.clear()
        fitTarget?.release()
        fitTarget = null
    }

    override fun release() {
        super.release()
        try {
            releaseTextures()
            programs.forEach(GlProgram::delete)
            finish.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}
