package com.ambient.tv.ui.common.projectm

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import com.ambient.tv.ui.display.SdrToHdrRequest
import com.ambient.tv.ui.display.SdrToHdrStatus
import com.ambient.tv.ui.player.SdrHdrSizeBudget
import com.ambient.tv.ui.player.SdrHdrSurfaceOutput
import com.ambient.tv.ui.player.backend.VulkanNativeProbe
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/** projectM keeps its ordinary SDR renderer/presets. Only its final surface is optionally routed
 * through the shared 10-bit PQ GPU output. Preserve the GL context across route changes.
 */
internal class ProjectMHdrView(context: Context) : GLSurfaceView(context) {
    @Volatile private var input: Surface? = null
    private var hostRunning = false
    private var released = false
    private var unsafe = false
    /** Full HD unless the user opted into 4K HDR for projectM; read on every HDR update. */
    @Volatile var hdrBudget: SdrHdrSizeBudget = SdrHdrSizeBudget.FULL_HD
    private val output = SdrHdrSurfaceOutput(
        disconnect = { pauseRenderer() },
        routeInput = { surface -> input = surface; if (hostRunning && !released) resumeRenderer() },
        restore = { input = null; if (hostRunning && !released && !unsafe) resumeRenderer() },
        unsafeRestore = {
            unsafe = true
            // A stuck/failed HDR window must not leave its last HDR buffer visible after OFF.
            alpha = 0f
            pauseRenderer()
        },
        readDataSpace = { surface ->
            // projectM's RGBA8 output is app-owned SDR, unlike an unknown external video stream.
            // Legacy EGL may leave the tag unspecified (0); the preset output is still sRGB.
            VulkanNativeProbe.dataSpace(surface).let { if (it == 0) SRGB else it }
        },
        budget = { hdrBudget },
    )

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setEGLWindowSurfaceFactory(object : EGLWindowSurfaceFactory {
            override fun createWindowSurface(egl: EGL10, display: EGLDisplay, config: EGLConfig, nativeWindow: Any): EGLSurface {
                val target = input
                // Keep the original framebuffer transfer behaviour. Do not ask EGL for an sRGB
                // framebuffer (which would gamma-encode projectM's already-encoded RGB twice).
                val surface = egl.eglCreateWindowSurface(display, config, target ?: nativeWindow, null)
                if (target != null && surface != EGL10.EGL_NO_SURFACE) {
                    // Metadata for the external sampler; no pixel mutation and no CPU readback.
                    runCatching { VulkanNativeProbe.restoreDataSpace(target, SRGB) }
                }
                return surface
            }
            override fun destroySurface(egl: EGL10, display: EGLDisplay, surface: EGLSurface) {
                egl.eglDestroySurface(display, surface)
            }
        })
        output.attach(this)
    }

    private fun pauseRenderer() = super.onPause()
    private fun resumeRenderer() = super.onResume()

    override fun onResume() {
        if (released || unsafe) return
        hostRunning = true
        if (!output.deferUntilStopped { if (hostRunning && !released && !unsafe) resumeRenderer() }) resumeRenderer()
    }

    override fun onPause() {
        hostRunning = false
        stopHdr()
        pauseRenderer()
    }

    private fun stopHdr(): SdrToHdrStatus {
        val engaged = output.ownsSurface || input != null
        val status = output.update(null, false, 0, 0, 0f)
        if (engaged && !output.deferUntilStopped {
            input = null
            if (hostRunning && !released && !unsafe) resumeRenderer()
        }) input = null
        return status
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopHdr()
        super.surfaceDestroyed(holder)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        if (hostRunning && !released && !unsafe) {
            if (!output.deferUntilStopped { input = null; if (hostRunning && !released && !unsafe) resumeRenderer() }) {
                input = null
                resumeRenderer()
            }
        }
    }

    fun updateHdr(request: SdrToHdrRequest?, enabled: Boolean): SdrToHdrStatus {
        if (request == null || !enabled || released || unsafe || !hostRunning || Build.VERSION.SDK_INT < 34)
            return stopHdr()
        val frame = holder.surfaceFrame
        return output.update(request.copy(peakNits = 300, referenceWhiteNits = 0), VulkanNativeProbe.available,
            frame.width(), frame.height(), display?.refreshRate ?: 0f)
    }

    fun releaseHdr() {
        hostRunning = false
        released = true
        output.release()
    }

    private companion object {
        const val SRGB = (1 shl 16) or (2 shl 22) or (1 shl 27)
    }
}
