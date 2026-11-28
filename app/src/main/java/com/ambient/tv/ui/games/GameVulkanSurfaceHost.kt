package com.ambient.tv.ui.games

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.swordfish.libretrodroid.GLRetroView

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
    private val outputTransfer: GameVulkanTransferMode,
    private val onFormats: (GameVulkanSurfaceFormats) -> Unit,
    private val onNativeStatus: (GameVulkanSdrStatus) -> Unit,
    private val onFullscreenLost: () -> Unit,
    private val onDiagnosticLost: () -> Unit,
) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var activeSurface: Surface? = null
    @Volatile private var generation = 0L
    // Surface callbacks, unlike settings updates, invalidate actual native window ownership.
    @Volatile private var surfaceRevision = 0L
    @Volatile private var probeSerial = 0L
    private val surfaceWork = GameVulkanSurfaceWork<GameHdrDisplayCapabilities>()
    @Volatile private var disposed = false
    @Volatile private var nativeSdrDiagnostic = false
    @Volatile private var nativeIntegerScaling = false
    @Volatile private var nativeIntegerScalingAuto = false
    @Volatile private var nativeShaderPreset = ""
    // Never treat a manual PQ/HLG selection as core source evidence.
    @Volatile private var confirmedCoreTransfer: GameVulkanTransferMode? = null

    @Volatile private var canProbe = false
    @Volatile private var displayHdr = GameHdrDisplayCapabilities()
    @Volatile private var fullscreenRequested = false
    @Volatile private var fullscreenVisible = false
    @Volatile private var fullscreenSurfaceReady = false
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
                fullscreenSurfaceReady = false
                setNativeFullscreenGate(false)
                activeSurface = holder.surface
                surfaceRevision++
                generation++
                tryProbe()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Resize revokes the old presentation before another frame may
                // skip GLES. The tiny capability probe never qualifies.
                fullscreenSurfaceReady = fullscreenVisible && width > 16 && height > 16
                setNativeFullscreenGate(false)
                activeSurface = holder.surface
                surfaceRevision++
                generation++
                tryProbe()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                fullscreenSurfaceReady = false
                setNativeFullscreenGate(false)
                activeSurface = null
                surfaceRevision++
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

    /**
     * The surface that currently shows the game, as Compose state: handheld ambient fill must
     * follow a switch to native Vulkan WSI, where GLES may skip drawing its own surface.
     */
    private var presentation by mutableStateOf<SurfaceView>(retro)

    /** Sample the actual presentation for handheld ambient fill, including native Vulkan WSI. */
    fun presentationSurface(): SurfaceView = presentation

    fun updateDisplayHdr(info: GameHdrDisplayCapabilities) {
        if (disposed || displayHdr == info) return
        displayHdr = info
        val hdrStillSupported = when (outputTransfer) {
            GameVulkanTransferMode.SDR -> true
            GameVulkanTransferMode.PQ_BT2020 -> info.hdr10
            GameVulkanTransferMode.HLG_BT2020 -> info.hlg
        }
        if (!hdrStillSupported && nativeSdrDiagnostic) {
            setNativeSdrDiagnostic(false)
            onFullscreenLost()
            onDiagnosticLost()
        }
        tryProbe() // Re-query capabilities from the current Android display mode.
    }

    /** A known bundled Slang preset, or empty for the fast raw-Pixel output. */
    fun updateShaderPreset(path: String?) {
        val selected = path.orEmpty()
        if (disposed || nativeShaderPreset == selected) return
        nativeShaderPreset = selected
        // Disarm the old frame before changing a live WSI filter; a resized
        // swapchain never inherits a "PRESENTING" badge from its previous filter.
        if (fullscreenVisible) {
            fullscreenRequested = false
            demoteToProbe()
            onFullscreenLost()
        }
        lastStatus = GameVulkanSdrStatus(1)
        onNativeStatus(lastStatus)
        if (canProbe) {
            // The GL thread is the sole owner of Vulkan devices, swapchains and
            // librashader history. tryProbe serializes setPreset before setSurface.
            tryProbe()
        }
    }

    /** Invalid/changed source evidence tears down an active experimental HDR surface. */
    /** Follow the ordinary picture setting without recreating the Vulkan window. */
    fun updateIntegerScaling(mode: com.ambient.tv.games.GameIntegerScaling) {
        val auto = mode == com.ambient.tv.games.GameIntegerScaling.AUTO
        if (disposed || (nativeIntegerScaling == mode.enabled && nativeIntegerScalingAuto == auto)) return
        nativeIntegerScaling = mode.enabled
        nativeIntegerScalingAuto = auto
        if (canProbe) runCatching {
            retro.queueEvent {
                if (!disposed) {
                    RetroCoreRuntime.core.setNativeVulkanIntegerScalingAuto(nativeIntegerScalingAuto)
                    RetroCoreRuntime.core.setNativeVulkanIntegerScaling(nativeIntegerScaling)
                }
            }
        }
    }

    fun updateSourceContract(transfer: GameVulkanTransferMode?) {
        if (disposed || confirmedCoreTransfer == transfer) return
        confirmedCoreTransfer = transfer
        if (nativeSdrDiagnostic && outputTransfer != GameVulkanTransferMode.SDR &&
            confirmedCoreTransfer != outputTransfer) {
            setNativeSdrDiagnostic(false)
            onDiagnosticLost()
        } else if (nativeSdrDiagnostic) {
            tryProbe()
        }
    }

    fun setNativeSdrDiagnostic(enabled: Boolean) {
        if (nativeSdrDiagnostic == enabled || disposed) return
        if (enabled && outputTransfer != GameVulkanTransferMode.SDR &&
            confirmedCoreTransfer != outputTransfer) {
            onDiagnosticLost()
            return
        }
        nativeSdrDiagnostic = enabled
        // Invalidate any queued GL probe/attachment from the previous mode.
        generation++
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
        if (GameVulkanTransferGate.frameConfirmed(outputTransfer, lastStatus)) promoteToFullscreen()
    }

    fun observeNativeStatus(status: GameVulkanSdrStatus) {
        lastStatus = status
        if (fullscreenVisible && GameVulkanFullscreenPolicy.mustFallback(status)) {
            fullscreenRequested = false
            demoteToProbe()
            onFullscreenLost()
        } else if (fullscreenRequested && !fullscreenVisible &&
            GameVulkanFullscreenPolicy.shouldShowFullscreen(true, status, outputTransfer)) {
            promoteToFullscreen()
        }
        setNativeFullscreenGate(GameVulkanFullscreenPolicy.canSkipGles(
            fullscreenRequested, fullscreenVisible, fullscreenSurfaceReady, lastStatus, outputTransfer,
        ))
    }

    private fun promoteToFullscreen() {
        if (fullscreenVisible || disposed || !nativeSdrDiagnostic ||
            !GameVulkanTransferGate.frameConfirmed(outputTransfer, lastStatus)) return
        setNativeFullscreenGate(false)
        fullscreenSurfaceReady = false
        generation++
        fullscreenVisible = true
        presentation = probeSurface
        val attempt = ++fullscreenAttempt
        // Trigger a real Surface resize and Vulkan swapchain recreation.
        // Compose overlays remain visible; GLRetroView still receives input.
        probeSurface.holder.setSizeFromLayout()
        probeSurface.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        lastStatus = GameVulkanSdrStatus(1)
        // Do not carry a "PRESENTING" badge from the old 16px swapchain into
        // the newly resized fullscreen surface before its first native frame.
        onNativeStatus(lastStatus)
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
        setNativeFullscreenGate(false)
        fullscreenSurfaceReady = false
        if (!fullscreenVisible) return
        fullscreenVisible = false
        presentation = retro
        generation++
        fullscreenAttempt++
        probeSurface.holder.setFixedSize(16, 16)
        probeSurface.layoutParams = LayoutParams(1, 1, Gravity.BOTTOM or Gravity.END)
    }

    private fun detachNativeSdrSurface() {
        if (!canProbe) return
        runCatching { retro.queueEvent {
            try {
                RetroCoreRuntime.core.setNativeVulkanFullscreenActive(false)
                RetroCoreRuntime.core.setNativeSdrVulkanSurface(null)
            } finally {
                surfaceWork.detached()
            }
        } }
    }

    private fun setNativeFullscreenGate(enabled: Boolean) {
        if (!canProbe) return
        val token = generation
        runCatching { retro.queueEvent {
            // The native backend additionally checks success of the exact frame
            // being rendered. A delayed UI status never suppresses a failed frame.
            RetroCoreRuntime.core.setNativeVulkanFullscreenActive(
                enabled && !disposed && token == generation && nativeSdrDiagnostic &&
                    fullscreenRequested && fullscreenVisible && fullscreenSurfaceReady,
            )
        } }
    }

    private fun tryProbe() {
        if (!canProbe || disposed) return
        val surface = activeSurface?.takeIf { it.isValid } ?: return
        val token = generation
        val revision = surfaceRevision
        val serial = ++probeSerial
        val caps = displayHdr
        fun current() = !disposed && serial == probeSerial && token == generation &&
            revision == surfaceRevision && activeSurface === surface && surface.isValid
        runCatching {
            retro.queueEvent {
                // Coalesce superseded probes while preserving the GL FIFO lifecycle boundary.
                // A real SurfaceChanged is a new revision even if Android reuses its object.
                if (!current()) return@queueEvent
                val bits = surfaceWork.cachedFormats(revision, caps) ?: runCatching {
                    val eotfs = if (caps.hdr10 || caps.hlg || caps.hdr10Plus || caps.dolbyVision) {
                        1 or (if (caps.hdr10) 2 else 0) or (if (caps.hlg) 4 else 0)
                    } else 0
                    RetroCoreRuntime.core.setHdrDisplayInfo(
                        caps.peakNits ?: 0f,
                        caps.averageNits ?: 0f,
                        caps.minimumNits ?: 0f,
                        eotfs,
                    )
                    RetroCoreRuntime.core.probeVulkanSurfaceFormats(surface).also {
                        surfaceWork.rememberFormats(revision, caps, it)
                    }
                }.getOrDefault(0)
                if (nativeSdrDiagnostic && current()) {
                    runCatching {
                        val attach = surfaceWork.needsAttachment(revision)
                        if (attach) RetroCoreRuntime.core.setNativeVulkanFullscreenActive(false)
                        // The setters retain native compatibility/retirement checks. A
                        // shader, scaling or metadata change is not itself a new window.
                        RetroCoreRuntime.core.setNativeVulkanCoreSourceTransfer(
                            confirmedCoreTransfer?.nativeCode ?: 0,
                        )
                        RetroCoreRuntime.core.setNativeVulkanIntegerScalingAuto(nativeIntegerScalingAuto)
                        RetroCoreRuntime.core.setNativeVulkanIntegerScaling(nativeIntegerScaling)
                        RetroCoreRuntime.core.setNativeVulkanOutputTransfer(outputTransfer.nativeCode)
                        RetroCoreRuntime.core.setNativeVulkanShaderPreset(nativeShaderPreset)
                        if (attach && current()) {
                            RetroCoreRuntime.core.setNativeSdrVulkanSurface(surface)
                            // JNI returns void: an absent native window is not an
                            // accepted attachment. A later probe can retry it.
                            if (RetroCoreRuntime.core.getNativeSdrVulkanStatus() and 1 != 0) {
                                surfaceWork.attached(revision)
                            }
                        }
                    }
                }
                val status = GameVulkanSdrStatus(runCatching {
                    RetroCoreRuntime.core.getNativeSdrVulkanStatus()
                }.getOrDefault(0))
                main.post {
                    if (current()) {
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
