@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import java.nio.ByteBuffer

/**
 * Real-device offscreen GLES smoke test. Runs production SSim multi-pass programs on
 * a known constant-gray input and checks GLSL compilation, FP16 FBOs and readback.
 * Android/JVM unit tests cannot guarantee shader compilation on the target GPU.
 */
internal fun auditMedia3SsimShaders(context: Context, log: (String) -> Unit) {
    val egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    check(egl != EGL14.EGL_NO_DISPLAY)
    check(EGL14.eglInitialize(egl, IntArray(2), 0, IntArray(2), 0))
    val configurations = arrayOfNulls<android.opengl.EGLConfig>(1)
    val count = IntArray(1)
    check(EGL14.eglChooseConfig(egl, intArrayOf(
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_NONE), 0, configurations, 0, 1, count, 0) && count[0] > 0)
    val configuration = checkNotNull(configurations[0])
    var glContext = EGL14.eglCreateContext(egl, configuration, EGL14.EGL_NO_CONTEXT,
        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
    if (glContext == EGL14.EGL_NO_CONTEXT) {
        glContext = EGL14.eglCreateContext(egl, configuration, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
    }
    val surface = EGL14.eglCreatePbufferSurface(egl, configuration,
        intArrayOf(EGL14.EGL_WIDTH, 64, EGL14.EGL_HEIGHT, 64, EGL14.EGL_NONE), 0)
    check(glContext != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE)
    check(EGL14.eglMakeCurrent(egl, surface, surface, glContext))
    val textures = mutableListOf<Int>()
    val fbos = mutableListOf<Int>()
    val programs = mutableListOf<BaseGlShaderProgram>()

    fun grayTexture(width: Int, height: Int): Int {
        val tex = GlUtil.createTexture(width, height, false)
        textures += tex
        val bytes = ByteBuffer.allocateDirect(width * height * 4)
        repeat(width * height) {
            bytes.put(128.toByte()); bytes.put(128.toByte()); bytes.put(128.toByte()); bytes.put(255.toByte())
        }
        bytes.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        return tex
    }

    fun execute(label: String, effect: GlEffect, width: Int, expected: Int) {
        val program = effect.toGlShaderProgram(context, false) as BaseGlShaderProgram
        programs += program
        val size = program.configure(width, width)
        check(size.width == expected && size.height == expected) {
            label + " output " + size.width + "x" + size.height + ", expected " + expected
        }
        val input = grayTexture(width, width)
        val output = GlUtil.createTexture(expected, expected, false)
        textures += output
        val fbo = GlUtil.createFboForTexture(output)
        fbos += fbo
        GlUtil.focusFramebufferUsingCurrentContext(fbo, expected, expected)
        program.drawFrame(input, 0L)
        val actual = ByteBuffer.allocateDirect(4)
        GLES20.glReadPixels(expected / 2, expected / 2, 1, 1,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, actual)
        GlUtil.checkGlError()
        for (channel in 0..2) {
            val value = actual.get(channel).toInt() and 255
            check(value in 103..153) { label + " channel " + channel + "=" + value + ", expected gray 128" }
        }
        log("PASS " + label + " real GLES shader, " + width + " to " + expected)
    }
    try {
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        val hasFp16 = "GL_EXT_color_buffer_half_float" in extensions ||
            (version.contains("OpenGL ES 3") && "GL_EXT_color_buffer_float" in extensions)
        if (!media3FragmentHighp() || !hasFp16) {
            log("SKIP SSim GL: no fragment highp or renderable FP16")
            return
        }
        execute("SSimDown", Media3SsimDownEffect(16, 16), 32, 16)
        execute("SSimSuper Cubic", Media3SsimSuperEffect(
            Media3CubicScaleEffect(32, 32, b = 0f, c = 0.5f)), 16, 32)
        execute("SSimSuper Lanczos3", Media3SsimSuperEffect(
            Media3KernelEffect("ssim_lanczos3", 32, 32, sigmoid = false)), 16, 32)
    } finally {
        programs.forEach { runCatching { it.release() } }
        fbos.forEach { runCatching { GlUtil.deleteFbo(it) } }
        textures.forEach { runCatching { GlUtil.deleteTexture(it) } }
        EGL14.eglMakeCurrent(egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(egl, surface)
        EGL14.eglDestroyContext(egl, glContext)
        EGL14.eglTerminate(egl)
        EGL14.eglReleaseThread()
    }
}