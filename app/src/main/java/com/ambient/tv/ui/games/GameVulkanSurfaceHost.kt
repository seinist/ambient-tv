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
 * A lifecycle-owned, separate *real* Android Surface for probing Vulkan WSI.
 *
 * This tiny, non-interactive surface sits under the existing GLSurfaceView. It
 * never receives a presented frame, cannot steal touch/DPAD focus, and does not
 * replace the working GLES compositor. A future native swapchain presenter can
 * instead own a full-size visible SurfaceView after end-to-end validation.
 *
 * All Vulkan calls execute on GLRetroView's GL thread, not on the UI thread.
 * Surface invalidation prevents late native callbacks from restoring stale data.
 */
internal class GameVulkanSurfaceHost(
    context: Context,
    private val retro: GLRetroView,
    private val onFormats: (GameVulkanSurfaceFormats) -> Unit,
) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private var activeSurface: Surface? = null
    private var generation = 0L
    private var disposed = false
    private var canProbe = false

    private val probeSurface = SurfaceView(context).apply {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        holder.setFixedSize(16, 16)
    }

    init {
        // Parent's child order retains GLRetroView as the visible/touch layer.
        // A 1×1 view avoids covering any portion of the emulator or OSD.
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
                onFormats(GameVulkanSurfaceFormats())
            }
        })
    }

    fun coreReady() {
        canProbe = true
        tryProbe()
    }

    private fun tryProbe() {
        if (!canProbe || disposed) return
        val surface = activeSurface?.takeIf { it.isValid } ?: return
        val token = generation
        runCatching {
            retro.queueEvent {
                // WSI surface creation, query and destruction are synchronous.
                // There is no native surface ownership beyond this callback.
                val bits = runCatching {
                    if (surface.isValid) LibretroDroid.probeVulkanSurfaceFormats(surface) else 0
                }.getOrDefault(0)
                main.post {
                    if (!disposed && token == generation && activeSurface === surface) {
                        onFormats(GameVulkanSurfaceFormats(bits))
                    }
                }
            }
        }.onFailure {
            onFormats(GameVulkanSurfaceFormats())
        }
    }

    fun dispose() {
        disposed = true
        activeSurface = null
        generation++
    }
}
