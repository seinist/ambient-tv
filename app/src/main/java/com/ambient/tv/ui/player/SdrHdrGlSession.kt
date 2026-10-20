package com.ambient.tv.ui.player

import android.graphics.SurfaceTexture
import android.opengl.EGL14.*
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.*
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.RequiresApi
import com.ambient.tv.ui.display.SdrToHdrRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

internal interface SdrHdrSession {
    fun prepare()
    fun connect(surface: Surface)
    fun close(done: (Boolean) -> Unit)
    fun discontinuity() {}
    /** Live SDR white in nits; the output (PQ surface, metadata, peak) stays as negotiated. */
    fun referenceWhite(nits: Int) {}
}

internal fun interface SdrHdrSessionFactory {
    fun create(request: SdrToHdrRequest, width: Int, height: Int, fps: Float,
        ready: (Surface) -> Unit, presented: () -> Unit, failed: (Throwable) -> Unit): SdrHdrSession
}

/** All GL and SurfaceTexture work stays on one thread; no pixel readback/CPU conversion. */
@RequiresApi(34)
internal class SdrHdrGlSession(
    private val request: SdrToHdrRequest,
    private val width: Int,
    private val height: Int,
    private val fps: Float,
    private val ready: (Surface) -> Unit,
    private val presented: () -> Unit,
    private val failed: (Throwable) -> Unit,
    private val budget: SdrHdrSizeBudget = SdrHdrSizeBudget.FULL_HD,
) : SdrHdrSession {
    private val thread = HandlerThread("SdrHdrOutput").apply { start() }
    private val handler = Handler(thread.looper)
    private val stopping = AtomicBoolean(false)
    private var display = EGL_NO_DISPLAY
    private var context = EGL_NO_CONTEXT
    private var window = EGL_NO_SURFACE
    private var pbuffer = EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private var texture: SurfaceTexture? = null
    private var input: Surface? = null
    private var program = 0
    private var metadataExtensions: Set<String> = emptySet()
    private var videoLocation = -1
    private var transferLocation = -1
    private var peakNitsLocation = -1
    private var referenceWhiteLocation = -1
    @Volatile private var referenceWhiteNits = request.effectiveReferenceWhiteNits.toFloat()
    private var matrixLocation = -1
    private var positionLocation = -1
    private var textureId = 0
    private var firstFrame = true
    private val frameGate = SdrHdrFrameGate()
    private var slowFrames = 0
    private var lastFrameNs = 0L
    private var measuredFrames = 0
    private var missedFrames = 0
    private var measurementStart = 0L
    private val matrix = FloatArray(16)
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private fun task(block: () -> Unit) {
        handler.post {
            if (!stopping.get()) runCatching(block).onFailure {
                if (!stopping.getAndSet(true)) failed(it)
            }
        }
    }

    override fun prepare() = task {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
        check(display != EGL_NO_DISPLAY)
        check(eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val extensions = eglQueryString(display, EGL_EXTENSIONS).orEmpty().split(' ').toSet()
        metadataExtensions = extensions
        check("EGL_KHR_gl_colorspace" in extensions && "EGL_EXT_gl_colorspace_bt2020_pq" in extensions)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(eglChooseConfig(display, intArrayOf(
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT or EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
            EGL_RED_SIZE, 10, EGL_GREEN_SIZE, 10, EGL_BLUE_SIZE, 10, EGL_ALPHA_SIZE, 2,
            EGL_NONE,
        ), 0, configs, 0, 1, count, 0) && count[0] > 0)
        config = configs[0]!!
        for (channel in intArrayOf(EGL_RED_SIZE, EGL_GREEN_SIZE, EGL_BLUE_SIZE)) {
            val bits = IntArray(1)
            check(eglGetConfigAttrib(display, config, channel, bits, 0) && bits[0] >= 10)
        }
        context = eglCreateContext(display, config, EGL_NO_CONTEXT,
            intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE), 0)
        check(context != EGL_NO_CONTEXT)
        pbuffer = eglCreatePbufferSurface(display, config,
            intArrayOf(EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE), 0)
        check(pbuffer != EGL_NO_SURFACE && eglMakeCurrent(display, pbuffer, pbuffer, context))
        check("GL_OES_EGL_image_external" in glGetString(GL_EXTENSIONS).orEmpty().split(' '))
        program = glCreateProgram()
        fun compile(type: Int, source: String): Int {
            val shader = glCreateShader(type)
            glShaderSource(shader, source.trimIndent())
            glCompileShader(shader)
            val ok = IntArray(1)
            glGetShaderiv(shader, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val message = glGetShaderInfoLog(shader)
                glDeleteShader(shader)
                error(message)
            }
            return shader
        }
        val vertex = compile(GL_VERTEX_SHADER, SdrHdrShader.vertex)
        glAttachShader(program, vertex)
        glDeleteShader(vertex)
        val fragment = compile(GL_FRAGMENT_SHADER, SdrHdrShader.fragment)
        glAttachShader(program, fragment)
        glDeleteShader(fragment)
        glLinkProgram(program)
        val linked = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { glGetProgramInfoLog(program) }
        videoLocation = glGetUniformLocation(program, "video")
        transferLocation = glGetUniformLocation(program, "transfer")
        peakNitsLocation = glGetUniformLocation(program, "peakNits")
        referenceWhiteLocation = glGetUniformLocation(program, "referenceWhite")
        matrixLocation = glGetUniformLocation(program, "textureMatrix")
        positionLocation = glGetAttribLocation(program, "position")
        val names = IntArray(1)
        glGenTextures(1, names, 0)
        textureId = names[0]
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        texture = SurfaceTexture(textureId).also { st ->
            st.setDefaultBufferSize(width, height)
            st.setOnFrameAvailableListener({
                if (!stopping.get()) {
                    if (frameGate.available()) task { drain() }
                }
            }, handler)
            input = Surface(st)
        }
        check(glGetError() == GL_NO_ERROR)
        ready(input!!)
    }

    /** Called only AFTER the original producer has released this native window. */
    override fun connect(surface: Surface) = task {
        check(surface.isValid)
        window = eglCreateWindowSurface(display, config, surface,
            intArrayOf(0x309D, 0x3340, EGL_NONE), 0) // EGL_GL_COLORSPACE_KHR / BT2020_PQ_EXT
        check(window != EGL_NO_SURFACE)
        val colorSpace = IntArray(1)
        check(eglQuerySurface(display, window, 0x309D, colorSpace, 0) && colorSpace[0] == 0x3340)
        // Static HDR metadata so the TV tone-maps for what is really sent (peak = requested nits)
        // instead of assuming a 1000/4000-nit master. Optional: drivers without it ignore this.
        for ((attribute, value) in sdrHdrStaticMetadata(request.peakNits.toFloat(), metadataExtensions)) {
            if (!eglSurfaceAttrib(display, window, attribute, value)) {
                timber.log.Timber.w("HDR-AUDIT EGL metadata rejected attribute=0x%s error=0x%s",
                    attribute.toString(16), eglGetError().toString(16))
            }
        }
        timber.log.Timber.i("HDR-AUDIT EGL PQ surface negotiated peak=%d metadataAttributes=%d; physical display mode unknown",
            request.peakNits, sdrHdrStaticMetadata(request.peakNits.toFloat(), metadataExtensions).size)
        check(eglMakeCurrent(display, window, window, context))
        check(eglSwapInterval(display, 1))
        drain()
    }

    /** updateTexImage latches the latest available buffer. Coalesce notifications before it. */
    private fun drain() {
        if (!stopping.get() && frameGate.consume(window != EGL_NO_SURFACE)) render()
    }

    /** Picked up by the next rendered frame; no session restart and no HDR renegotiation. */
    override fun referenceWhite(nits: Int) {
        referenceWhiteNits = nits.toFloat()
    }

    override fun discontinuity() = task {
        frameGate.discontinuity()
        slowFrames = 0
        lastFrameNs = 0L
        measuredFrames = 0
        missedFrames = 0
        measurementStart = SystemClock.elapsedRealtime() + 3000L
    }

    private fun render() {
        if (stopping.get()) return
        val started = SystemClock.elapsedRealtime()
        val st = checkNotNull(texture)
        st.updateTexImage()
        val transfer = checkNotNull(sdrHdrTransfer(st.dataSpace)) { "Unconfirmed SDR frame dataspace" }
        if (!frameGate.acceptTimestamp(st.timestamp)) return
        // Inspect every frame, including adaptive format changes, BEFORE any HDR submission.
        st.getTransformMatrix(matrix)
        val size = IntArray(1)
        check(eglQuerySurface(display, window, EGL_WIDTH, size, 0))
        val outputWidth = size[0]
        check(eglQuerySurface(display, window, EGL_HEIGHT, size, 0))
        check(sdrHdrSizeAllowed(outputWidth, size[0], fps, budget)) { "HDR output exceeds GPU budget" }
        glViewport(0, 0, outputWidth, size[0])
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
        glUniform1i(videoLocation, 0)
        glUniform1i(transferLocation, transfer)
        glUniform1f(peakNitsLocation, request.peakNits.toFloat())
        glUniform1f(referenceWhiteLocation, referenceWhiteNits)
        glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0)
        glEnableVertexAttribArray(positionLocation)
        glVertexAttribPointer(positionLocation, 2, GL_FLOAT, false, 0, quad)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        check(glGetError() == GL_NO_ERROR)
        sdrHdrPresentationTimeNs(st.timestamp, System.nanoTime())?.let { presentAt ->
            EGLExt.eglPresentationTimeANDROID(display, window, presentAt)
        }
        check(eglSwapBuffers(display, window))
        if (firstFrame) {
            firstFrame = false
            measurementStart = started + 3000L
            presented()
        }
        // Sustained slow swaps OR skipped source frames revoke conversion, not playback.
        val frameBudget = 1000f / fps
        slowFrames = if (SystemClock.elapsedRealtime() - started > frameBudget * 1.5f) slowFrames + 1 else 0
        check(slowFrames < 12) { "HDR GPU output is too slow" }
        val stamp = st.timestamp
        if (started >= measurementStart && lastFrameNs > 0 && stamp > lastFrameNs) {
            val intervals = ((stamp - lastFrameNs) / (1_000_000_000.0 / fps)).roundToInt()
            // A seek/pause is not a GPU overload sample.
            if (intervals in 1..8) {
                measuredFrames += intervals
                missedFrames += intervals - 1
            }
            if (started - measurementStart >= 6000L) {
                check(measuredFrames < 30 || missedFrames * 10 <= measuredFrames) { "HDR output dropped frames" }
                measurementStart = started
                measuredFrames = 0
                missedFrames = 0
            }
        }
        lastFrameNs = stamp
    }

    /** Producer is disconnected first; restore direct output only from this completion callback. */
    override fun close(done: (Boolean) -> Unit) {
        stopping.set(true)
        handler.post {
            var success = true
            fun cleanup(action: () -> Unit) { runCatching(action).onFailure { success = false } }
            cleanup { texture?.setOnFrameAvailableListener(null) }
            cleanup { input?.release() }
            cleanup { texture?.release() }
            cleanup {
                if (display != EGL_NO_DISPLAY) {
                    if (program != 0) glDeleteProgram(program)
                    if (textureId != 0) glDeleteTextures(1, intArrayOf(textureId), 0)
                    check(eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT))
                }
            }
            cleanup { if (window != EGL_NO_SURFACE) check(eglDestroySurface(display, window)) }
            cleanup { if (pbuffer != EGL_NO_SURFACE) check(eglDestroySurface(display, pbuffer)) }
            cleanup { if (context != EGL_NO_CONTEXT) check(eglDestroyContext(display, context)) }
            cleanup { if (display != EGL_NO_DISPLAY) check(eglTerminate(display)) }
            eglReleaseThread()
            thread.quitSafely()
            done(success)
        }
    }
}
