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
    private val json = Json { ignoreUnknownKeys = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Never blocks the caller (players construct on the main thread). Until the startup probe has
     * finished, unchecked capabilities are returned, which plan the safe non-Vulkan path.
     */
    fun current(context: Context): VideoAccelerationCapabilities {
        val value = mutable.value
        if (!value.checked && !mutableChecking.value) {
            val app = context.applicationContext
            scope.launch { refresh(app) }
        }
        return value
    }

    suspend fun refresh(context: Context, force: Boolean = false): VideoAccelerationCapabilities = withContext(Dispatchers.IO) {
        lock.withLock {
            if (mutable.value.checked && !force) return@withLock mutable.value
            // "Erneut prüfen": HDR über GPU verdicts from before the re-check no longer apply.
            if (force) MpvHdrGpuSession.clear()
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
                VideoAccelerationCapabilities(true, device, mpv and 1 != 0, mpv and 2 != 0, vlc).also {
                    if (mutable.value.device.device != device.device || mutable.value.device.version != device.version)
                        mutableEvidence.value = emptyMap()
                    mutable.value = it
                    Timber.i("VULKAN capabilities device=%s render=%s profiles=%s mpv=%d vlc=%s reason=%s",
                        device.device, device.render, device.profiles, mpv, vlc, device.reason)
                }
            } finally { mutableChecking.value = false }
        }
    }

    private fun probeDevice(surface: Surface? = null): VulkanDeviceCapabilities = runCatching {
        if (!VulkanNativeProbe.available) return@runCatching VulkanDeviceCapabilities()
        json.decodeFromString<VulkanDeviceCapabilities>(VulkanNativeProbe.probe(surface))
    }.getOrElse { Timber.w(it, "Vulkan probe failed"); VulkanDeviceCapabilities() }

    /** Check the real Surface before handing it to a Vulkan renderer. */
    fun surfaceSupported(surface: Surface, backend: String? = null,
        onCapabilities: (VulkanDeviceCapabilities) -> Unit = {},
    ): Boolean {
        if (!surface.isValid) return false
        val probed = probeDevice(surface)
        onCapabilities(probed)
        Timber.i("VULKAN surface device=%s render=%s reason=%s colorSpaces=%s hdr10=%s",
            probed.device, probed.render, probed.reason, probed.surfaceColorSpaces, probed.hdr10SurfaceSupported)
        val supported = probed.render && probed.device == mutable.value.device.device
        if (backend != null) mutableEvidence.update { it + (backend to VulkanPlaybackEvidence(
            if (supported) VulkanPlaybackStage.SURFACE_READY else VulkanPlaybackStage.FAILED,
            if (supported) null else "surface",
        )) }
        return supported
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
