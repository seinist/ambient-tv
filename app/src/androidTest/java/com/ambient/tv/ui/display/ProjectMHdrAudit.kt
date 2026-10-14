package com.ambient.tv.ui.display

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.FrameLayout
import com.ambient.tv.ui.common.projectm.ProjectMAvailability
import com.ambient.tv.ui.common.projectm.ProjectMHdrView
import com.ambient.tv.ui.common.projectm.ProjectMNativeBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin

/** Uses the packaged projectM renderer on the new view while HDR is disabled/unavailable. */
internal class ProjectMHdrAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var error: Throwable? = null
        instrumentation.runOnMainSync { runCatching(action).onFailure { error = it } }
        error?.let { throw it }
    }
    fun run(activity: Activity) {
        check(ProjectMAvailability.isAvailable)
        val frames = AtomicInteger()
        val handle = AtomicLong()
        lateinit var view: ProjectMHdrView
        val pcm = FloatArray(512) { sin(it * .07).toFloat() * .8f }
        main {
            view = ProjectMHdrView(activity)
            view.setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                    val next = ProjectMNativeBridge.nativeCreate()
                    check(next != 0L)
                    val old = handle.getAndSet(next)
                    if (old != 0L) ProjectMNativeBridge.nativeDestroy(old)
                    ProjectMNativeBridge.nativeLoadPreset(next, """
                        [preset00]
                        fDecay=0.98
                        fGammaAdj=1.0
                        fWaveAlpha=1.0
                        fWaveScale=1.0
                        nWaveMode=0
                        wave_r=1.0
                        wave_g=0.5
                        wave_b=0.2
                        wave_a=1.0
                        per_frame_1=zoom=1.01;
                    """.trimIndent(), false)
                }
                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                    ProjectMNativeBridge.nativeResize(handle.get(), width, height, 32, 24)
                }
                override fun onDrawFrame(gl: GL10?) {
                    val current = handle.get()
                    if (current != 0L) {
                        ProjectMNativeBridge.nativeFeedPcm(current, pcm)
                        ProjectMNativeBridge.nativeRender(current)
                        frames.incrementAndGet()
                    }
                }
            })
            activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(640, 360)) })
            view.onResume()
        }
        try {
            fun awaitFrames(after: Int) {
                repeat(100) { if (frames.get() > after + 10) return; Thread.sleep(50) }
                error("projectM did not render frames")
            }
            awaitFrames(0)
            val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            try {
                val done = CountDownLatch(1)
                var result = -1
                main { PixelCopy.request(view, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper())) }
                check(done.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS)
                val colors = mutableSetOf<Int>()
                for (y in 0 until 360 step 4) for (x in 0 until 640 step 4) colors += bitmap.getPixel(x, y)
                check(colors.size > 3) { "projectM rendered a blank image" }
            } finally { bitmap.recycle() }
            main {
                check(view.updateHdr(null, true) == SdrToHdrStatus.OFF)
                check(view.updateHdr(SdrToHdrRequest(400, 0, 1), false) == SdrToHdrStatus.OFF)
                view.onPause()
            }
            val paused = frames.get()
            Thread.sleep(250)
            check(frames.get() == paused)
            main { view.onResume() }
            awaitFrames(paused)
            log("PASS packaged projectM draws a nonblank SDR frame; HDR-off/performance veto and pause/resume keep the native renderer working")
        } finally {
            val destroyed = CountDownLatch(1)
            view.queueEvent {
                handle.getAndSet(0L).takeIf { it != 0L }?.let(ProjectMNativeBridge::nativeDestroy)
                destroyed.countDown()
            }
            check(destroyed.await(5, TimeUnit.SECONDS))
            main { view.onPause(); view.releaseHdr(); activity.setContentView(FrameLayout(activity)) }
        }
    }
}
