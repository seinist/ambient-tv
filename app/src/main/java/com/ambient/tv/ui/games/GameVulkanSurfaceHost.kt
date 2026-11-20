package com.ambient.tv.ui.games

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.LibretroDroid

/**
 * A separate real Android Vulkan WSI Surface, initially only 1x1 in the UI.
 *
 * The GLSurfaceView keeps ownership of emulation, touch, input and the GLES
 * fallback. Vulkan can be shown fullscreen ONLY after the tiny diagnostic has
 * actually presented SDR frames and the user explicitly requests fullscreen.
 * The media-overlay layer stays below the window's Compose OSD, never on top
 * of the entire window. Its Z order is chosen before window attachment.
 *
 * All native Vulkan calls are serialized through GLRetroView.queueEvent.
 */
internal class GameVulkanSurfaceHost(
    context: Context,
    private val retro: GLRetroView,
    private val onFormats: (GameVulkanSurfaceFormats) -> Unit,
    private val onNativeStatus: (GameVulkanSdrStatus) -> Unit,
    private val onFullscreenLost: () -> Unit,
) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var activeSurface: Surface? = null
    @Volatile private var generation = 0L
    @Volatile private var disposed = false
    @Volatile private var nativeSdrDiagnostic = false

    private var canProbe = false
    private var fullscreenRequested = false
    private var fullscreenVisible = false
    private var fullscreenAttempt = 0L
    private var lastStatus = GameVulkanSdrStatus()

    private val probeSurface = SurfaceView(context).apply {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        // Above GLRetroView's Surface, but below the Activity/Compose window.
        // setZOrderOnTop(true) would hide OSD, so never use it here.
        setZOrderMediaOverlay(true)
        holder.setFixedSize(16, 16)
    }

    init {
        // The regular view hierarchy keeps touch with the fullscreen GLRetroView.
        addView(probeSurface, LayoutParams(1, 1, Gravity.BOTTOM or Gravity.END))
        addView(retro, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        probeSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                activeSurface = holder.surface
                generation++
                tryProbe()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                activeSurface = holder.surface
                generation++
                tryProbe()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                activeSurface = null
                generation++
                detachNativeSdrSurface()
                onFormats(GameVulkanSurfaceFormats())
                onNativeStatus(GameVulkanSdrStatus())
                // A view resize itself can destroy/recreate Surface. Only an
                // explicit native failure or a watchdog timeout demotes fullscreen.
            }
        })
    }

    fun coreReady() {
        canProbe = true
        tryProbe()
    }

    fun setNativeSdrDiagnostic(enabled: Boolean) {
        if (nativeSdrDiagnostic == enabled || disposed) return
        nativeSdrDiagnostic = enabled
        if (!enabled) {
            setFullscreenRequested(false)
            detachNativeSdrSurface()
            onNativeStatus(GameVulkanSdrStatus())
        } else {
            tryProbe()
        }
    }

    /** Must be requested on the UI thread; never auto-promote based on display HDR support. */
    fun setFullscreenRequested(enabled: Boolean) {
        if (disposed) return
        if (!enabled) {
            fullscreenRequested = false
            demoteToProbe()
            return
        }
        if (!nativeSdrDiagnostic || !canProbe) return
        fullscreenRequested = true
        if (lastStatus.phase == GameVulkanSdrStatus.Phase.PRESENTING) promoteToFullscreen()
    }

    fun observeNativeStatus(status: GameVulkanSdrStatus) {
        lastStatus = status
        if (fullscreenVisible && GameVulkanFullscreenPolicy.mustFallback(status)) {
            fullscreenRequested = false
            demoteToProbe()
            onFullscreenLost()
        } else if (fullscreenRequested && !fullscreenVisible &&
            GameVulkanFullscreenPolicy.shouldShowFullscreen(true, status)) {
            promoteToFullscreen()
        }
    }

    private fun promoteToFullscreen() {
        if (fullscreenVisible || disposed || !nativeSdrDiagnostic) return
        fullscreenVisible = true
        val attempt = ++fullscreenAttempt
        // Trigger a real Surface resize and Vulkan swapchain recreation.
        // Compose overlays remain visible; GLRetroView still receives input.
        probeSurface.holder.setSizeFromLayout()
        probeSurface.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        lastStatus = GameVulkanSdrStatus(1)
        main.postDelayed({
            if (!disposed && fullscreenVisible && fullscreenAttempt == attempt &&
                lastStatus.phase != GameVulkanSdrStatus.Phase.PRESENTING) {
                fullscreenRequested = false
                demoteToProbe()
                onFullscreenLost()
            }
        }, 5000L)
    }

    private fun demoteToProbe() {
        if (!fullscreenVisible) return
        fullscreenVisible = false
        fullscreenAttempt++
        probeSurface.holder.setFixedSize(16, 16)
        probeSurface.layoutParams = LayoutParams(1, 1, Gravity.BOTTOM or Gravity.END)
    }

    private fun detachNativeSdrSurface() {
        if (!canProbe) return
        runCatching { retro.queueEvent { LibretroDroid.setNativeSdrVulkanSurface(null) } }
    }

    private fun tryProbe() {
        if (!canProbe || disposed) return
        val surface = activeSurface?.takeIf { it.isValid } ?: return
        val token = generation
        runCatching {
            retro.queueEvent {
                // Do not attach an obsolete surface if destroy/recreation occurred
                // while this task was queued on the GL thread.
                if (disposed || token != generation || activeSurface !== surface || !surface.isValid)
                    return@queueEvent
                val bits = runCatching {
                    LibretroDroid.probeVulkanSurfaceFormats(surface)
                }.getOrDefault(0)
                if (nativeSdrDiagnostic && surface.isValid && token == generation) {
                    runCatching { LibretroDroid.setNativeSdrVulkanSurface(surface) }
                }
                val status = GameVulkanSdrStatus(runCatching {
                    LibretroDroid.getNativeSdrVulkanStatus()
                }.getOrDefault(0))
                main.post {
                    if (!disposed && token == generation && activeSurface === surface) {
                        onFormats(GameVulkanSurfaceFormats(bits))
                        onNativeStatus(status)
                    }
                }
            }
        }.onFailure { onFormats(GameVulkanSurfaceFormats()) }
    }

    fun dispose() {
        if (disposed) return
        fullscreenRequested = false
        demoteToProbe()
        if (nativeSdrDiagnostic) detachNativeSdrSurface()
        disposed = true
        activeSurface = null
        generation++
        fullscreenAttempt++
    }
}
