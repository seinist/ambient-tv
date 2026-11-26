package com.ambient.tv.ui.player.backend

import android.content.Context
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.videolan.libvlc.LibVLC
import timber.log.Timber

internal object VulkanNativeProbe {
    val available by lazy { runCatching { System.loadLibrary("ambientvulkan"); true }.getOrDefault(false) }
    external fun probe(surface: Surface?): String
    external fun probeValidatedSurface(surface: Surface, device: String, version: Long): String
    external fun vlcModules(): Boolean
    /** Read-only producer dataspace, not a Vulkan capability query. Unknown returns zero. */
    external fun dataSpace(surface: Surface): Int
    external fun restoreDataSpace(surface: Surface, dataSpace: Int): Boolean
}

object VideoAccelerationRuntime {
    private val mutable = MutableStateFlow(VideoAccelerationCapabilities())
    val capabilities = mutable.asStateFlow()
    private val mutableChecking = MutableStateFlow(false)
    val checking = mutableChecking.asStateFlow()
    private val mutableEvidence = MutableStateFlow<Map<String, VulkanPlaybackEvidence>>(emptyMap())
    val playbackEvidence = mutableEvidence.asStateFlow()
    private val lock = Mutex()
    private val startupProbeScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Never blocks the caller (players construct on the main thread). Until the startup probe has
     * finished, unchecked capabilities are returned, which plan the safe non-Vulkan path.
     */
    fun current(context: Context): VideoAccelerationCapabilities {
        val value = mutable.value
        if (!value.checked && !mutableChecking.value && startupProbeScheduled.compareAndSet(false, true)) {
            val app = context.applicationContext
            scope.launch {
                try { refresh(app) } finally { startupProbeScheduled.set(false) }
            }
        }
        return value
    }

    suspend fun refresh(context: Context, force: Boolean = false): VideoAccelerationCapabilities = withContext(Dispatchers.IO) {
        lock.withLock {
            if (mutable.value.checked && !force) return@withLock mutable.value
            // "Erneut prüfen": HDR über GPU verdicts from before the re-check no longer apply.
            if (force) {
                MpvHdrGpuSession.clear()
                mpvZeroCopyFailures.clear()
            }
            mutableChecking.value = true
            try {
                val device = probeDevice()
                val mpv = if (MpvBackendAvailability.nativeRuntimeAvailable) {
                    runCatching { MpvNativeBridge.nativeVulkanSupport() }.getOrDefault(0)
                } else 0
                val vlc = if (VulkanNativeProbe.available) runCatching {
                    val instance = LibVLC(context.applicationContext, arrayListOf("--verbose=0"))
                    try { VulkanNativeProbe.vlcModules() } finally { instance.release() }
                }.getOrDefault(false) else false
                val eglPq = MpvEglHdrProbe.hdr10Pq()
                val eglSurfacePatch = MpvNativeFeatures.EGL_HDR_SURFACE in MpvNativeFeatures.current
                VideoAccelerationCapabilities(true, device, mpv and 1 != 0, mpv and 2 != 0, vlc, eglPq,
                    mpvEglHdrSurface = eglSurfacePatch, surfaceDataSpaceReadable = VulkanNativeProbe.available).also {
                    if (mutable.value.device.device != device.device || mutable.value.device.version != device.version)
                        mutableEvidence.value = emptyMap()
                    mutable.value = it
                    Timber.i("VULKAN capabilities device=%s render=%s profiles=%s mpv=%d vlc=%s reason=%s eglPq=%s " +
                        "mpvEglHdrSurface=%s dataSpaceReadable=%s nativeFeatures=%s",
                        device.device, device.render, device.profiles, mpv, vlc, device.reason, eglPq,
                        eglSurfacePatch, VulkanNativeProbe.available, MpvNativeFeatures.current.sorted())
                    Timber.i("VULKAN MediaCodec interop prerequisites AHB=%s foreignQueue=%s ycbcr=%s syncFd=%s (not an enabled renderer)",
                        device.androidHardwareBufferImport, device.foreignQueueFamily,
                        device.samplerYcbcrConversion, device.syncFdImportExport)
                }
            } finally { mutableChecking.value = false }
        }
    }

    private fun probeDevice(surface: Surface? = null): VulkanDeviceCapabilities = runCatching {
        if (!VulkanNativeProbe.available) return@runCatching VulkanDeviceCapabilities()
        json.decodeFromString<VulkanDeviceCapabilities>(VulkanNativeProbe.probe(surface))
    }.getOrElse { Timber.w(it, "Vulkan probe failed"); VulkanDeviceCapabilities() }

    /** Query the real window without publishing playback evidence from a stale request. */
    private fun probeSurface(surface: Surface): VulkanDeviceCapabilities {
        if (!surface.isValid) return VulkanDeviceCapabilities(reason = "invalid_surface")
        val validated = mutable.value.device
        val probed = if (validated.render && validated.device.isNotBlank()) {
            try {
                json.decodeFromString<VulkanDeviceCapabilities>(
                    VulkanNativeProbe.probeValidatedSurface(surface, validated.device, validated.version),
                ).let { result ->
                    mergeValidatedVulkanSurface(validated, result)
                }
            } catch (_: UnsatisfiedLinkError) {
                // Older packaged native runtime: retain the original full probe.
                probeDevice(surface)
            } catch (error: Exception) {
                Timber.w(error, "Vulkan surface probe failed")
                VulkanDeviceCapabilities()
            }
        } else probeDevice(surface)
        return probed
    }

    /** Check the real Surface before handing it to a Vulkan renderer. */
    fun surfaceSupported(surface: Surface, backend: String? = null,
        onCapabilities: (VulkanDeviceCapabilities) -> Unit = {},
    ): Boolean {
        val probed = probeSurface(surface)
        onCapabilities(probed)
        return acceptSurfaceProbe(probed, backend)
    }

    internal fun acceptSurfaceProbe(probed: VulkanDeviceCapabilities, backend: String?): Boolean {
        Timber.i("VULKAN surface device=%s render=%s reason=%s colorSpaces=%s hdr10=%s formats=%s",
            probed.device, probed.render, probed.reason, probed.surfaceColorSpaces, probed.hdr10SurfaceSupported, probed.surfaceFormats)
        val current = mutable.value.device
        val supported = probed.render && probed.device == current.device && probed.version == current.version
        if (backend != null) mutableEvidence.update { it + (backend to VulkanPlaybackEvidence(
            if (supported) VulkanPlaybackStage.SURFACE_READY else VulkanPlaybackStage.FAILED,
            if (supported) null else "surface",
        )) }
        return supported
    }

    /**
     * VLC's view lifecycle runs on the main thread. Copy the native window reference and do
     * only the capability query on IO. The caller must validate its generation before accepting.
     * No swapchain, decoder or producer is created here.
     */
    internal fun probeSurfaceAsync(surface: Surface, result: (VulkanDeviceCapabilities) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val copy = runCatching {
            val parcel = android.os.Parcel.obtain()
            try {
                surface.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                Surface.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
        }.getOrNull()
        if (copy == null) {
            main.post { result(VulkanDeviceCapabilities(reason = "surface_copy")) }
            return
        }
        scope.launch {
            val probed = try {
                runCatching { probeSurface(copy) }.getOrElse { VulkanDeviceCapabilities(reason = "surface_probe") }
            } finally { runCatching { copy.release() } }
            main.post { result(probed) }
        }
    }

    fun reportOutput(backend: String, frames: Boolean = false) {
        mutableEvidence.update { it + (backend to (it[backend] ?: VulkanPlaybackEvidence()).outputObserved(frames)) }
    }

    /**
     * Only for an explicitly chosen Vulkan output/decoder; "auto" falls back silently by design.
     * One key per app, so repeated fallbacks in a session update the same notice.
     */
    fun notifyUnavailable() {
        com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
            "playback:vulkan", com.ambient.tv.R.string.notice_vulkan_unavailable,
            kind = com.ambient.tv.notifications.NoticeKind.PLAYBACK, interruptPlayback = false,
        ))
    }

    fun reportFallback(backend: String, reason: String) {
        mutableEvidence.update { it + (backend to VulkanPlaybackEvidence(VulkanPlaybackStage.FAILED, reason)) }
    }
}
