package com.ambient.tv.ui.player

import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil

/*
 * GL capability checks and intermediate textures for the multi-pass Media3 shaders (RAVU, NIS,
 * MPV kernels). They run on the effect chain's GL thread, inside its current context.
 */

/** Whether fragment shaders get highp floats; mediump (fp16) cannot address 4K texels exactly. */
internal fun media3FragmentHighp(): Boolean {
    val range = IntArray(2)
    val precision = IntArray(1)
    GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, precision, 0)
    return precision[0] > 0
}

/**
 * The LUT shaders index texels and decode 16-bit weights; without highp they would sample the
 * wrong texels. Throwing hands the failure to [Media3ImageProcessing]'s fallback (effects off).
 */
internal fun media3RequireFragmentHighp(what: String) {
    if (!media3FragmentHighp()) throw VideoFrameProcessingException("$what needs highp fragment floats")
}

internal fun media3MaxTextureSize(): Int =
    IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]

/** A render target between passes: texture and its framebuffer. */
internal class Media3Intermediate(val texture: Int, val fbo: Int) {
    fun release() {
        GlUtil.deleteFbo(fbo)
        GlUtil.deleteTexture(texture)
    }
}

/**
 * Half-float target where the context can render to and filter it (MPV keeps its intermediate
 * passes at 16 bit too), else RGBA8. [highPrecision] (HDR chains) always uses Media3's own.
 */
internal fun media3Intermediate(width: Int, height: Int, highPrecision: Boolean): Media3Intermediate {
    if (!highPrecision) halfFloatTexture(width, height)?.let { return it }
    return try {
        val texture = GlUtil.createTexture(width, height, highPrecision)
        Media3Intermediate(texture, GlUtil.createFboForTexture(texture))
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
}

private fun halfFloatTexture(width: Int, height: Int): Media3Intermediate? {
    val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
    val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
    val es3 = version.contains("OpenGL ES 3")
    val renderable = "GL_EXT_color_buffer_half_float" in extensions || (es3 && "GL_EXT_color_buffer_float" in extensions)
    val usable = renderable && (es3 || ("GL_OES_texture_half_float" in extensions && "GL_OES_texture_half_float_linear" in extensions))
    if (!usable) return null
    val texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    if (es3) {
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
    } else {
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GL_HALF_FLOAT_OES, null)
    }
    val fbo = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
    val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE &&
        GLES20.glGetError() == GLES20.GL_NO_ERROR
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    if (complete) return Media3Intermediate(texture, fbo)
    GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
    GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    return null
}

private const val GL_HALF_FLOAT_OES = 0x8D61
