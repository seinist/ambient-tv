package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.display.SdrToHdrUnavailableReason
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import com.ambient.tv.data.local.mpvProperties
import com.ambient.tv.data.local.validateNnedi3ShaderSource
import com.ambient.tv.ui.player.mpvCheapDefaults
import com.ambient.tv.ui.player.reducedVideoEffects
import com.ambient.tv.ui.player.performanceProfile
import com.ambient.tv.ui.player.videoImagePreferences
import com.ambient.tv.ui.player.gpuRenderAtPanelResolution
import android.content.Context
import android.os.SystemClock
import com.ambient.tv.ui.display.SdrToHdrRequest
import com.ambient.tv.ui.display.SdrToHdrStatus
import android.view.Surface
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import timber.log.Timber
import com.ambient.tv.ui.player.HdrStandard
import com.ambient.tv.ui.player.PlaybackHandle
import com.ambient.tv.ui.player.PlaybackStatus
import com.ambient.tv.ui.player.VideoPresentation
import com.ambient.tv.ui.player.VideoPresentationTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.android.asCoroutineDispatcher

private const val MPV_EVENT_END_FILE = 7
/** Read on every track-list access; a change in any of them means a different selection. */
private val TRACK_SELECTION_PROPERTIES = arrayOf(
    "vid", "aid", "sid", "secondary-sid",
    "current-tracks/video/id", "current-tracks/audio/id", "current-tracks/sub/id",
)
private const val MPV_EVENT_FILE_LOADED = 8
private const val MPV_EVENT_SHUTDOWN = 1
/** Poll timeout of the event waiter; also bounds how long release waits for it. */
private const val EVENT_PUMP_POLL_MS = 1_000L
private const val MPV_EVENT_VIDEO_RECONFIG = 17
private const val MPV_EVENT_AUDIO_RECONFIG = 18
private const val MPV_EVENT_PROPERTY_CHANGE = 22

/**
 * Properties mpv pushes on change instead of being read over JNI on every UI tick. The index is
 * the observe id. time-pos is deliberately absent: it changes every frame and is read on demand.
 */
private val OBSERVED_PROPERTIES: List<Pair<String, Int>> = listOf(
    "pause" to MpvNativeBridge.FORMAT_FLAG,
    "paused-for-cache" to MpvNativeBridge.FORMAT_FLAG,
    "seeking" to MpvNativeBridge.FORMAT_FLAG,
    "eof-reached" to MpvNativeBridge.FORMAT_FLAG,
    "duration" to MpvNativeBridge.FORMAT_DOUBLE,
    "audio-codec-name" to MpvNativeBridge.FORMAT_STRING,
    "video-codec" to MpvNativeBridge.FORMAT_STRING,
    "hwdec-current" to MpvNativeBridge.FORMAT_STRING,
    "track-list/count" to MpvNativeBridge.FORMAT_DOUBLE,
)
private const val HDR_AUDIT_TAG = "HDR-AUDIT"
private const val ZERO_COPY_CONFIRMED = Long.MIN_VALUE

/**
 * The output path a new engine starts on. Also read by [MpvPlaybackController] before the engine
 * exists, so the panel can lay out the expected (GPU or direct) options during a renderer switch.
 */
internal fun initialMpvAccelerationPlan(
    context: Context,
    videoOutputMode: String,
    decoderMode: String,
    initialImageOptions: MpvImageOptions,
    nativeHdrPreferred: Boolean,
    hdrGpuPassthrough: Boolean,
    toneMappingRequired: Boolean,
): MpvAccelerationPlan = planMpvAcceleration(videoOutputMode, decoderMode, VideoAccelerationRuntime.current(context),
    // Select the processing path from the chosen width/title image settings.
    // Auto goes to the GPU only for image work the user chose; untouched defaults stay direct.
    gpuProcessing = mpvUserImageProcessing(initialImageOptions, reducedVideoEffects(context), svpAllowed(context)),
    nativeHdrPreferred = nativeHdrPreferred,
    hdrPassthrough = hdrGpuPassthrough, toneMappingRequired = toneMappingRequired,
    zeroCopy = !videoImagePreferences(context).mpvZeroCopyFailed)

internal class MpvPlaybackEngine(
    private val context: Context,
    val audioSession: com.ambient.tv.ui.player.AudioSession,
    private val workerHandler: android.os.Handler,
    private val onOutputTransition: () -> Unit,
    val url: String,
    private val initialStartPositionMs: Long,
    private val headers: Map<String, String>,
    audioPassthroughEnabled: Boolean = false,
    videoOutputMode: String = "direct",
    initialImageOptions: MpvImageOptions = MpvImageOptions(),
    networkCache: Boolean = false,
    decoderMode: String = "auto",
    knownAudioCodec: String? = null,
    private val nativeHdrPreferred: Boolean = false,
    /** HDR10 → HDR10 display via gpu-next/Vulkan passthrough ([mpvHdrGpuDecision]). */
    private val hdrGpuPassthrough: Boolean = false,
    private val liveStream: Boolean = false,
    private val declaredHdr: HdrStandard? = null,
    private val audioOnly: Boolean = false,
    private val preferOriginalAudio: Boolean = false,
    reopeningTimeshift: Boolean = false,
    /** Live TV or a TVHeadend recording: broadcast transport streams that can be interlaced. */
    private val broadcastSource: Boolean = liveStream,
    /** Names the running native step for the stall log ([MpvOwnerStep]); never read back here. */
    private val ownerStep: MpvOwnerStep? = null,
) : PlaybackHandle, VideoPresentationTarget, com.ambient.tv.ui.player.SubtitleDelayTarget {
    init { check(android.os.Looper.myLooper() === workerHandler.looper) }

    private val startupConstructionAt = System.nanoTime()
    private var streamOpened = reopeningTimeshift
    private val networkCacheEnabled = mpvUsesNetworkCache(url, networkCache)
    private var appliedAudioGraph = ""
    private var appliedAc3Filter = false
    /** True after the first non-empty audio-codec-name has been planned. */
    private var observedAudioCodecApplied = false
    val bitstreamPermitted: Boolean get() = audioSession.bitstreamPermitted
    private val displayHdrStandards = com.ambient.tv.ui.player.HdrSupport.displayStandards(context)
    private fun needsHdrToSdr(source: HdrStandard?): Boolean = source != null &&
        !com.ambient.tv.ui.player.nativeHdrSurfacePreferred(source, displayHdrStandards)
    private var acceleration = initialMpvAccelerationPlan(context, videoOutputMode, decoderMode, initialImageOptions,
        nativeHdrPreferred, hdrGpuPassthrough, needsHdrToSdr(declaredHdr))
    /** One copy-back reopen per engine after zero-copy failed at runtime. */
    private var zeroCopyRetried = false
    private var zeroCopyFrameCheckSinceNs = 0L
    private val zeroCopyFrameWatch = ZeroCopyFrameWatch()
    private var accelerationRetried = false
    /** Runtime verdict for the passthrough route; the UI reopens on the direct surface when set. */
    var hdrGpuFailure by mutableStateOf<MpvHdrGpuReason?>(null)
        private set
    private val hdrPassthroughMonitor = MpvHdrPassthroughMonitor()
    /**
     * PQ reaches the display through gpu-next/Vulkan or vo=gpu on an EGL BT2020_PQ surface:
     * OUTPUT hooks see PQ code values.
     */
    private val hdrPassthroughActive: Boolean get() = hdrGpuPassthrough && acceleration.gpu && hdrGpuFailure == null
    /** EGL PQ route ([MpvHdrGpuDecision.openGl]): vo=gpu renders PQ/BT.2020 explicitly. */
    private val hdrPassthroughOpenGl: Boolean get() = hdrPassthroughActive && !acceleration.vulkan
    /** The passthrough route runs (Vulkan or EGL PQ; read by the UI, [hdrPassthroughActive] is engine-internal). */
    val hdrPassthroughVulkan: Boolean get() = hdrPassthroughActive
    /** [MpvHdrPassthroughMonitor] confirmed PQ/BT.2020 on the display for this player. */
    var hdrPassthroughConfirmed by mutableStateOf(false)
        private set
    /** See [MpvHdrPassthroughMonitor.displaySwitched]. */
    var hdrDisplaySwitched by mutableStateOf<Boolean?>(null)
        private set

    /** One software reopen per engine; this does not change the user's stored decoder choice. */
    private var shieldSoftwareDecode = false
    val shieldSoftwareStatus: String? get() = if (shieldSoftwareDecode)
        context.getString(com.ambient.tv.R.string.mpv_shield_broadcast_software) else null
    private var lastPlaybackPositionMs = initialStartPositionMs
    private var fallbackTrackSelection: List<String?>? = null
    /** First AO init failure re-plans. A second -14 is what forces stereo PCM. */
    private var audioAoInitRetried = false
    internal var restoredOutputAudioSelection = false
        private set

    internal fun captureOutputRestartState(): List<String?> =
        fallbackTrackSelection ?: listOf("aid", "sid", "speed").map(::getPropertyString)

    internal fun restoreOutputRestartState(selection: List<String?>) {
        fallbackTrackSelection = selection
        restoredOutputAudioSelection = selection.firstOrNull() != null
    }
    var accelerationFallback: String? = acceleration.fallback
        private set
    private val vulkanRequested = videoOutputMode == "vulkan" || decoderMode == "vulkan"
    // Before the startup probe finishes the plan is the provisional safe path, not a verdict.
    init { if (vulkanRequested && acceleration.fallback != null && !nativeHdrPreferred && VideoAccelerationRuntime.capabilities.value.checked) VideoAccelerationRuntime.notifyUnavailable() }
    private val gpuQualityMode get() = acceleration.gpu
    val supportsGpuFilters: Boolean get() = gpuQualityMode
    /**
     * GPU buffer at the physical HDMI mode instead of the logical window size
     * ([gpuRenderAtPanelResolution]: "Renderauflösung" Auto/Panel/UI).
     */
    val gpuRenderAtPanel: Boolean get() = gpuRenderAtPanelResolution(
        videoImagePreferences(context).gpuRenderResolutionKey, gpuQualityMode, performanceProfile(context).lowEndDevice,
        loadedShaderKeys.isNotEmpty() || mpvUserImageProcessing(imageOptions, reducedVideoEffects(context), svpAllowed(context)),
    )
    override val usesRendererScaling: Boolean get() = gpuQualityMode
    private val configuredVo: String get() = acceleration.vo
    /** gpu-next ignores mpv's --sharpen; before the VO is up, the configured one decides. */
    private fun rendersWithGpuNext(): Boolean =
        (getPropertyString("current-vo")?.takeIf { it.isNotBlank() } ?: configuredVo).trim().lowercase() == "gpu-next"
    private val handle: Long
    // Event-driven state (declared before init, which starts the pump).
    /** Owner-thread values; the public controller publishes immutable UI snapshots. */
    private val observed: MutableMap<String, Any?> = java.util.Collections.synchronizedMap(HashMap())
    private val _stateChanges = MutableStateFlow(0L)
    /** Ticks on every pushed property change or player event; the UI waits on it instead of polling. */
    val stateChanges: kotlinx.coroutines.flow.StateFlow<Long> = _stateChanges.asStateFlow()
    /** True once the event pump runs; without it (no wakeup pipe) the UI keeps its poll loop. */
    @Volatile var eventDriven = false
        private set
    private val pumpRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val drainPosted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val eventHandler = workerHandler
    private var eventPump: Thread? = null
    private val sdrToHdrProperties: MpvHdrProperties = object : MpvHdrProperties {
        override fun outputDataSpace(): Int = attachedSurface?.takeIf { it.isValid }?.let {
            if (VulkanNativeProbe.available) runCatching { VulkanNativeProbe.dataSpace(it) }.getOrDefault(0) else 0
        } ?: 0
        override fun get(name: String): String? = getPropertyString(name)
        override fun set(name: String, value: String): Boolean = trySetProperty(name, value)
    }
    private val sdrToHdr = MpvSdrToHdrController(sdrToHdrProperties)
    private var conversionSampleStarted = 0L
    private val sdrToHdrDisplayWatch = SdrToHdrDisplayWatch()
    private var conversionSampleDrops = 0L
    private var conversionOutputUnsafe = false
    private var attached = false
    private var attachedSurface: Surface? = null
    private var sdrSurfaceOriginal: Int? = null
    private var sdrSurfaceFailed = false
    private var hdr10SwapchainSupported: Boolean? = null
    private var detachedVideoSelection: String? = null
    private var startupOpenedAt = 0L
    private var loaded = false
    private var pendingInitialSeekMs: Long? = initialStartPositionMs.takeIf { !liveStream && it > 0 }
    private val dvResumeWorkaround get() = !liveStream && !gpuQualityMode &&
        com.ambient.tv.ui.player.needsDolbyVisionResumePriming(android.os.Build.MANUFACTURER,
            android.os.Build.VERSION.SDK_INT, declaredHdr, 1L)
    private var dvPrimeTarget: Long? = null
    private var dvPrimeSeeking = false
    private var dvPrimeMute: String? = null
    private var dvPrimeTask: Runnable? = null
    private var dvSurfaceRestart = false

    private fun finishDvPrime() {
        dvPrimeTask?.let(workerHandler::removeCallbacks)
        dvPrimeTask = null
        dvPrimeTarget = null
        dvPrimeSeeking = false
        if (!released) {
            dvPrimeMute?.let { setProperty("mute", it) }
            setProperty("pause", if (nativePlayWanted) "no" else "yes")
        }
        dvPrimeMute = null
    }

    private fun beginDvPrime(target: Long) {
        finishDvPrime()
        dvPrimeTarget = target
        dvPrimeMute = getPropertyString("mute") ?: "no"
        setProperty("mute", "yes")
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000L
        val task = object : Runnable {
            override fun run() {
                if (released || dvPrimeTarget == null) return
                val position = getPropertyDouble("time-pos", 0.0) * 1000.0
                val seeking = getPropertyString("seeking") == "yes"
                if (!dvPrimeSeeking && fileReady && position >= 250 &&
                    getPropertyString("video-out-params/pixelformat") != null) {
                    dvPrimeSeeking = true
                    command("seek", (dvPrimeTarget!! / 1000.0).toString(), "absolute+exact")
                    Timber.i("DV MPV first frame primed; seek target=%d", dvPrimeTarget)
                } else if (dvPrimeSeeking && !seeking && kotlin.math.abs(position - dvPrimeTarget!!) < 1000) {
                    lastPlaybackPositionMs = dvPrimeTarget!!
                    finishDvPrime()
                    return
                }
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    errorMessage = context.getString(com.ambient.tv.R.string.player_seek_failed)
                    finishDvPrime()
                    setProperty("pause", "yes")
                    return
                }
                workerHandler.postDelayed(this, 50L)
            }
        }
        dvPrimeTask = task
        workerHandler.postDelayed(task, 50L)
    }
    private var fileReady = false
    /** Vulkan output is confirmed once per loaded file, not on every status tick. */
    private var vulkanOutputReported = false
    private var explicitDeinterlaceFilter: String? = null
    private var deinterlaceRequested = false
    /** Live field-rate input (50/59.94 fps reported): the deinterlacer runs single-rate. */
    private var deinterlaceSingleRate = false
    /** Method actually in the chain, after reduced-effects mapping. */
    private var appliedDeinterlaceKey = "auto"
    private var deinterlaceSingleRateAttempt: Boolean? = null
    private var deinterlaceAutomatic = false
    private var deinterlaceUpdateJob: Job? = null
    /** Applied mode/filter. The player panel mirrors these after an automatic backoff. */
    var deinterlaceModeKey by mutableStateOf("off")
        private set
    var deinterlaceFilterKey by mutableStateOf("auto")
        private set

    override var subtitleDelayMs by mutableStateOf(0)
        private set

    /** mpv keeps `sub-delay` across the file reloads this controller does (fallbacks, AO retry). */
    override fun setSubtitleDelay(delayMs: Int) {
        val clamped = delayMs.coerceIn(-com.ambient.tv.ui.player.SubtitleDelayTarget.LIMIT_MS, com.ambient.tv.ui.player.SubtitleDelayTarget.LIMIT_MS)
        if (released) return
        if (trySetProperty("sub-delay", (clamped / 1000.0).toString())) subtitleDelayMs = clamped
    }

    /** Deinterlacing remembered for this title; applied by the player content's first setDeinterlace. */
    internal fun presetDeinterlace(mode: String, filter: String) {
        deinterlaceModeKey = mode
        deinterlaceFilterKey = filter
    }
    /** Bumped only when overload replaces the running deinterlacer. Zero is the initial selection. */
    var deinterlaceBackoffGeneration by mutableStateOf(0)
        private set
    /** Yadif is the step after NNEDI. A later overload may turn that step off. User changes clear it. */
    private var deinterlaceSteppedFromNnedi = false
    var deinterlaceFailed by mutableStateOf(false)
        private set
    private var released = false
    private var desiredPlayWhenReady = true
    /**
     * A failed passthrough holds the frame natively but keeps the play intent: the direct reopen
     * takes it over through [wantsToPlay] ([mpvHdrFailureHold]). It holds only while a surface
     * shows it; detached (background) audio plays on ([mpvHdrNativePlayAllowed]).
     */
    private val nativePlayWanted: Boolean get() = mpvHdrNativePlayAllowed(desiredPlayWhenReady, hdrGpuFailure, attached)
    private var activeShaderPresetKey: String? = null
    private var loadedShaderKeys = emptyList<String>()
    private var loadedShaderTransferKey: String? = null
    private val activeCpuImageFilters = linkedMapOf<String, String>()
    private var lastImageTransfer: String? = null
    private var lastSvpSignature: String? = null
    private var svpOverloaded = false
    /** The SVP filter decision was applied for the loaded file (see [mpvSvpInterpolationIntended]). */
    private var svpFilterDecided = false
    /** Last adapter reason read from plex-svp metadata; null while interpolating. */
    @Volatile private var svpRuntimeReason: SvpInactiveReason? = null
    /** Quality stays on the light preset until that probe survives a short sample. */
    private var svpQualityCleared = false
    /** Probe failed: keep light for the session and do not promote or re-prompt. */
    private var svpQualityProbeFailed = false
    /** True only while plex-svp is requested and still in the vf chain. */
    private var svpCopyBackSession = false
    private var lastImageUpscaling = true
    /** Separate from [lastImageUpscaling]: NNEDI/RAVU need ~1.41×, not 1.01×. */
    private var lastLumaShaderEligible = true
    /**
     * An image update was dropped because the source or output changed while its shaders were
     * prepared. Its gates (above) were already recorded, so [refreshImageSource] would not notice
     * a change below the thresholds; this makes it apply the current options once more.
     */
    private var imageApplyStale = false
    /** mpv's own linear-downscaling value, restored when SSimDownscaler is not running. */
    private var linearDownscalingDefault: String? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    internal var renderCheckSignature: String? = null
        private set
    private var renderCheckJob: Job? = null
    private val imageScope = CoroutineScope(SupervisorJob() + workerHandler.asCoroutineDispatcher("mpv-control"))
    private var imageUpdateJob: Job? = null
    var imageOptions by mutableStateOf(initialImageOptions.normalized())
        private set
    /** Only an SVP that runs (or can still run for this file) keeps display-mode matching off. */
    override val usesFrameInterpolation: Boolean
        get() = gpuQualityMode && mpvSvpInterpolationIntended(
            modeOn = effectiveMpvImageOptions(imageOptions, reducedVideoEffects(context), svpAllowed(context), mpvCheapDefaults(context)).svpMode != "off",
            overloaded = svpOverloaded,
            hdr = hdrImageTransfer,
            decided = fileReady && svpFilterDecided,
            inChain = "plex-svp" in activeCpuImageFilters,
            runtimeReason = svpRuntimeReason,
        )
    var saveImageDefaults by mutableStateOf(false)
    var imageOptionWarnings by mutableStateOf<List<String>>(emptyList())
        private set
    var sdrImageProcessing by mutableStateOf(false)
        private set
    /** True only after mpv reports an HDR transfer. Saved picture options are left unchanged. */
    var hdrImageTransfer by mutableStateOf(false)
        private set
    var sourceHdrStandard by mutableStateOf<HdrStandard?>(null)
        private set
    /**
     * Dolby Vision profile of the loaded file's video track, read once per file on every route
     * (null until the track list is known, 0 = no Dolby Vision).
     */
    private var dolbyVisionProfile: Int? = null
    /** The loaded file carries Dolby Vision ([dolbyVisionProfile] > 0), whatever route plays it. */
    var dolbyVisionDetected by mutableStateOf(false)
        private set
    /** mpv reported an SDR transfer for the loaded file (live TV: an earlier HDR reading is stale). */
    var sourceSdrConfirmed by mutableStateOf(false)
        private set
    /** Set once when loaded shaders/filters drop frames; the player pauses and asks to adjust them. */
    var overloadDropPercent: Double? = null
        private set
    var renderOverloaded by mutableStateOf(false)
        private set
    /**
     * "Keep watching anyway" holds for exactly these settings: the render-check signature that
     * overloaded. Any filter change produces a new signature and checking resumes.
     * Also restored from [com.ambient.tv.ui.player.TitleVideoSettingsStore] for this title.
     */
    private var overloadSuspendedSignature: String? = null
    /** Signature currently accepted for this title; captured into title settings on stop. */
    val overloadContinueSignature: String? get() = overloadSuspendedSignature
    /** The automatic step down (deinterlace, upscaler, SVP) offered by the dialog, not applied unasked. */
    private var pendingOverloadReduce: (() -> Unit)? = null
    val canReduceAfterOverload: Boolean get() = pendingOverloadReduce != null

    /** "Settings": the user adjusts manually; the next change re-arms the check. */
    fun dismissRenderOverload() { renderOverloaded = false; pendingOverloadReduce = null }

    /** Restore a previously accepted "keep watching anyway" for this title's filter set. */
    fun restoreOverloadContinue(signature: String?) {
        overloadSuspendedSignature = signature?.takeIf { it.isNotBlank() }
    }

    fun continueDespiteOverload() {
        overloadSuspendedSignature = renderCheckSignature
        renderCheckJob?.cancel()
        renderCheckJob = null
        dismissRenderOverload()
        play()
    }

    fun reduceAfterOverload() {
        val reduce = pendingOverloadReduce
        dismissRenderOverload()
        reduce?.invoke()
        play()
    }

    private fun raiseRenderOverload(dropPercent: Double?, reduce: (() -> Unit)?) {
        overloadDropPercent = dropPercent
        if (renderCheckSignature != null && renderCheckSignature == overloadSuspendedSignature) return
        pause()
        pendingOverloadReduce = reduce
        renderOverloaded = true
    }
    fun loadedImageShaders(): List<String> = loadedShaderKeys


    var errorMessage by mutableStateOf<String?>(null)
        private set
    var ended by mutableStateOf(false)
        private set
    private var awaitingEofReset = false
    private val mutableVideoPresentation = MutableStateFlow(VideoPresentation())
    val videoPresentation = mutableVideoPresentation.asStateFlow()

    init {
        check(MpvBackendAvailability.nativeRuntimeAvailable) { "MPV native runtime is not bundled for this device" }
        handle = MpvNativeBridge.nativeCreate()
        check(handle != 0L) { "mpv_create() failed" }
        // Android MediaCodec textures use samplerExternalOES. NVIDIA also exposes desktop
        // OpenGL, whose shader overloads cannot sample these textures. Keep Android on GLES,
        // including a later Vulkan-to-OpenGL recovery.
        setOption("opengl-es", "yes")
        // Configure the Android VO up front but defer loadfile until a Surface exists. Starting
        // NVIDIA MediaCodec against vo=null leaves it permanently without a native window.
        // SurfaceView allows MediaCodec to feed the Android video plane directly. This preserves
        // HDR signalling and avoids the GPU/TextureView copy path that produced audio-only output
        // on SHIELD. Geometry is handled by the shared native surface host.
        if (acceleration.vulkan) {
            setOption("vo", acceleration.vo)
            setOption("gpu-api", "vulkan")
            setOption("gpu-context", "androidvk")
            setOption("vulkan-device", VideoAccelerationRuntime.capabilities.value.device.device)
            setOption("hwdec", acceleration.hwdec)
            // Passthrough: the swapchain carries the source's PQ/BT.2020 instead of tone-mapping to SDR.
            if (hdrGpuPassthrough) MPV_HDR_PASSTHROUGH_OPTIONS.forEach { (name, value) -> setOption(name, value) }
        } else if (gpuQualityMode) {
            // GPU quality path: MediaCodec still decodes in hardware, but frames pass through
            // mpv's Android OpenGL renderer. This is required for scale/cscale/dscale, deband
            // and GLSL user shaders such as NNEDI3. It is intentionally opt-in because the
            // direct MediaCodec surface path is more reliable for HDR/Dolby Vision on SHIELD.
            setOption("vo", "gpu")
            setOption("gpu-context", "android")
            setOption("hwdec", acceleration.hwdec)
            // EGL PQ passthrough (no Vulkan HDR10): PQ/BT.2020 into a 10-bit BT2020_PQ window surface.
            if (hdrGpuPassthrough) MPV_HDR_OPENGL_OPTIONS.forEach { (name, value) -> setOption(name, value) }
        } else {
            setOption("vo", "mediacodec_embed")
            setOption("hwdec", acceleration.hwdec)
        }
        // Only a direct display surface may engage native Dolby Vision, decided per display like
        // Kodi; GPU outputs leave SD MPEG-2/MPEG-4 to software instead ([mpvLavcDecoderOptions]).
        mpvLavcDecoderOptions(directSurface = !acceleration.vulkan && !gpuQualityMode, display = displayHdrStandards)
            .takeIf { it.isNotEmpty() }?.let { setOption("vd-lavc-o", it) }
        if (gpuQualityMode && needsHdrToSdr(declaredHdr)) {
            // Describe the actual SDR output; never send PQ/BT.2020 code values to it.
            setOption("target-prim", "bt.709")
            setOption("target-trc", sdrCurveTransfer().also { appliedSdrCurve = it })
            setOption("target-peak", "100")
        }
        setOption("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        setOption("force-window", "no")
        setOption("idle", "yes")
        setOption("keep-open", "yes")
        // VideoSurfaceLayout owns fit/crop/stretch. Do not letterbox again inside that surface.
        setOption("keepaspect", "no")
        setOption("audio-display", "no")
        setOption("input-default-bindings", "no")
        setOption("input-vo-keyboard", "no")
        setOption("network-timeout", "15")
        if (mpvNeedsReconnect(url)) setOption("stream-lavf-o", MPV_NETWORK_RECONNECT)
        MpvTlsTrust.caFile(context)?.let { setOption("tls-ca-file", it.absolutePath) }
        // Plex LAN range requests can stall in mpv's extra file cache on SHIELD. YouTube
        // googlevideo seeks and remote servers need that cache, otherwise every jump or
        // throughput dip waits on a cold HTTP range.
        if (networkCacheEnabled) {
            setOption("cache", "yes")
            setOption("demuxer-max-bytes", "64MiB")
            setOption("demuxer-readahead-secs", "20")
            com.ambient.tv.ui.player.mpvTimeshiftCacheOptions(com.ambient.tv.livetv.isLiveTimeshiftUrl(url))
                .forEach { (name, value) -> setOption(name, value) }
        } else {
            setOption("cache", "no")
        }
        if (!preferOriginalAudio) setOption("alang", com.ambient.tv.data.local.LanguagePreferences.playbackLanguageTags(
            videoImagePreferences(context).playbackAudioLanguage).joinToString(","))
        setOption("slang", com.ambient.tv.data.local.LanguagePreferences.playbackLanguageTags(
            videoImagePreferences(context).playbackSubtitleLanguage).joinToString(","))
        setOption("sub-auto", "fuzzy")
        // Text subtitle look from Settings › Subtitles; ASS/SSA keep their own styles.
        videoImagePreferences(context).subtitleAppearance.mpvProperties().forEach { (name, value) -> setOption(name, value) }
        setOption("audio-client-name", com.ambient.tv.branding.AppBrand.name)
        // File options must be configured before mpv_initialize(). Setting `start` afterwards can
        // leave mediacodec_embed on SHIELD displaying the requested frame with a stalled clock.
        if (!liveStream && initialStartPositionMs > 0L) {
            setOption("start", if (dvResumeWorkaround) "0" else (initialStartPositionMs / 1000.0).toString())
        }
        // AAudio/OpenSL ES are PCM paths. Select the encoded-capable Android AO when
        // passthrough is requested; a codec whitelist alone does not choose that output.
        setOption("ao", "audiotrack")
        knownAudioCodec?.trim()?.takeIf { it.isNotEmpty() }?.let { audioSession.sourceCodec = it }
        audioSession.refreshRoute(context)
        setOption("audio-spdif", audioSession.outputPlan().spdifCodecs.joinToString(","))
        Timber.i("%s MPV init vo=%s hwdec=%s gpuFilters=%s passthrough=%s", HDR_AUDIT_TAG, configuredVo, acceleration.hwdec, gpuQualityMode, bitstreamPermitted)
        if (headers.isNotEmpty()) {
            val fields = headers.entries
                .filter { it.key.isNotBlank() && it.value.isNotBlank() }
                // A string list: an unescaped comma (e.g. "(KHTML, like Gecko)" in a user agent)
                // would split one header into two broken ones.
                .joinToString(",") { "${it.key}: ${it.value}".replace(",", "\\,") }
            if (fields.isNotBlank()) setOption("http-header-fields", fields)
        }
        ownerStep?.mark("initialize:mpv_initialize")
        val init = MpvNativeBridge.nativeInitialize(handle)
        if (init < 0) {
            MpvNativeBridge.nativeDestroy(handle)
            error("mpv_initialize() failed: $init")
        }
        if (audioOnly) {
            // Music must open even when no video plane is composed (background/visualizer).
            setProperty("vid", "no")
            setProperty("vo", "null")
            setProperty("force-window", "no")
            command("loadfile", playbackUrlForOpen(), "replace")
            loaded = true
            pendingInitialSeekMs = null
            setProperty("pause", if (nativePlayWanted) "no" else "yes")
        }
        startEventPump()
        updateImageOptions(imageOptions)
        applyAudioOptions()
    }

    /** [mpvVideoSync] for the current audio plan and SVP state. */
    private fun currentVideoSync(): String = mpvVideoSync(
        syncToDisplay = videoImagePreferences(context).syncPlaybackToDisplay,
        gpuOutput = gpuQualityMode,
        encodedAudio = audioSession.outputPlan().encoded,
        svpActive = "plex-svp" in activeCpuImageFilters,
    )

    fun applyAudioOptions() {
        if (released) return
        audioSession.sourceChannels = getPropertyDouble("audio-params/channel-count", 0.0).toInt().takeIf { it > 0 }
        audioSession.refreshRoute(context, audioCodec())
        val plan = audioSession.outputPlan()
        val raw = audioSession.options
        val dsp = audioSession.optionsForPcm()
        val rejected = mutableListOf<String>()
        fun option(name: String, value: String) { if (!trySetProperty(name, value)) rejected.add(name) }
        if (plan.encoded) setPropertyDouble("speed", 1.0)
        // Passthrough cannot be resampled: leave display-resample before a bitstream opens.
        if (gpuQualityMode) currentVideoSync().let { sync -> if (getPropertyString("video-sync") != sync) option("video-sync", sync) }
        option("audio-spdif", plan.spdifCodecs.joinToString(","))
        option("audio-delay", (raw.delayMs / 1000.0).toString())
        option("audio-channels", mpvAudioChannels(plan.channels, plan.encoded, dsp.upmix && audioSession.sourceChannels == 2, plan.applyDsp,
            audioSession.sourceChannels, raw.forcedChannels(audioSession.effectiveRoute.pcmChannels)))
        option("replaygain", if (!plan.applyDsp || dsp.replayGain == "off") "no" else dsp.replayGain)
        option("replaygain-clip", "no")
        if (fileReady) {
            val rate = getPropertyDouble("audio-params/samplerate", 48000.0).toInt().coerceAtLeast(8000)
            val graphOptions = if (plan.mode == com.ambient.tv.ui.player.AudioOutputMode.LOCAL_AC3) dsp.copy(channels = "5.1", upmix = false) else dsp
            val graph = if (plan.applyDsp) mpvAudioGraph(graphOptions, rate, audioSession.sourceChannels) else ""
            syncAudioFilters(graph, plan.mode == com.ambient.tv.ui.player.AudioOutputMode.LOCAL_AC3, rejected)
            if (plan.mode == com.ambient.tv.ui.player.AudioOutputMode.LOCAL_AC3 && !appliedAc3Filter) {
                // Failed native filters must change the actual plan, including diagnostics.
                audioSession.localEncoderFailed = true
                Timber.w("Local AC3 encoder rejected; recovering with local PCM")
                applyAudioOptions()
                return
            }
        }
        audioSession.warning = rejected.takeIf { it.isNotEmpty() }?.joinToString()
    }

    private fun syncAudioFilters(graph: String, encodeAc3: Boolean, rejected: MutableList<String>) {
        val signature = graph + "|" + encodeAc3
        if (signature == appliedAudioGraph) return
        if (appliedAc3Filter) {
            if (!optionalCommand("af", "remove", "@plex-ac3")) rejected.add("af remove")
            else appliedAc3Filter = false
        }
        if (appliedAudioGraph.substringBefore("|").isNotEmpty()) {
            if (!optionalCommand("af", "remove", "@plex-audio")) rejected.add("af remove")
        }
        var appliedGraph = ""
        if (graph.isNotEmpty()) {
            if (optionalCommand("af", "add", "@plex-audio:lavfi=[$graph]")) appliedGraph = graph
            else rejected.add("af")
        }
        if (encodeAc3) {
            if (optionalCommand("af", "add", "@plex-ac3:$MPV_LOCAL_AC3_FILTER")) appliedAc3Filter = true
            else rejected.add("lavcac3enc")
        }
        val encoded = encodeAc3 && appliedAc3Filter
        audioSession.activeLocalConversion = if (encoded) com.ambient.tv.ui.player.audioConversionFormats(
            audioSession.sourceCodec, audioSession.sourceChannels, "ac3", 6) else null
        appliedAudioGraph = if (appliedGraph == graph && encoded == encodeAc3) signature else appliedGraph + "|" + encoded
    }

    private fun setOption(name: String, value: String) {
        val result = MpvNativeBridge.nativeSetOptionString(handle, name, value)
        if (result < 0) Timber.w("AV-AUDIT MPV option rejected: %s (%d)", name, result)
    }

    fun attachSurface(surface: Surface, width: Int = 0, height: Int = 0) {
        if (audioOnly || released || conversionOutputUnsafe || !surface.isValid) return
        if (attachedSurface === surface) return
        detachSurface()
        if (sdrToHdr.rollbackFailed) {
            stopUnsafeConversionOutput()
            return
        }
        hdr10SwapchainSupported = null
        ownerStep?.mark("surface:vulkan_probe")
        if (acceleration.vulkan && !VideoAccelerationRuntime.surfaceSupported(surface, "mpv") {
                hdr10SwapchainSupported = it.hdr10SurfaceSupported
            }) {
            fallbackAcceleration("surface", reload = false)
            // Passthrough does not switch to OpenGL; the view reopens on the direct surface.
            if (hdrGpuFailure != null) return
        }
        ownerStep?.mark("surface:attach")
        MpvNativeBridge.nativeAttachSurface(handle, surface)
        surfaceWidth = width
        surfaceHeight = height
        if (gpuQualityMode && width > 0 && height > 0) {
            setProperty("android-surface-size", "${width}x$height")
        }
        attached = true
        attachedSurface = surface
        Timber.i("%s MPV surface attached size=%dx%d valid=%s", HDR_AUDIT_TAG, width, height, surface.isValid)
        if (!loaded) {
            setProperty("vo", configuredVo)
            detachedVideoSelection?.let { setProperty("vid", it) }
            // Matches mpv-android's first-surface ordering: attach, force a window, then load.
            ownerStep?.mark("surface:force_window")
            setProperty("force-window", "yes")
            startupOpenedAt = System.nanoTime()
            com.ambient.tv.ui.player.logPlaybackStartup("mpv", "initial_load_requested", startupConstructionAt,
                "cache=$networkCacheEnabled readahead_s=${if (networkCacheEnabled) 20 else 0}")
            val requestedResume = pendingInitialSeekMs
            val prime = dvResumeWorkaround && (requestedResume ?: 0) > 0
            if (prime) beginDvPrime(requestedResume!!)
            val resume = requestedResume?.takeUnless { prime }
            ended = false
            errorMessage = null
            ownerStep?.mark("surface:loadfile")
            if (prime) command("loadfile", playbackUrlForOpen(), "replace", "-1", "start=0")
            else if (resume == null) command("loadfile", playbackUrlForOpen(), "replace")
            else command("loadfile", playbackUrlForOpen(), "replace", "-1", "start=${resume / 1000.0}")
            pendingInitialSeekMs = null
            loaded = true
            detachedVideoSelection?.let { setProperty("vid", it) }
            detachedVideoSelection = null
            setProperty("pause", if (nativePlayWanted || dvPrimeTarget != null) "no" else "yes")
        } else {
            setProperty("vo", configuredVo)
            setProperty("force-window", "yes")
            detachedVideoSelection?.let { setProperty("vid", it) }
            detachedVideoSelection = null
        }
    }

    fun surfaceSizeChanged(width: Int, height: Int) {
        if (released) return
        if (surfaceWidth != width || surfaceHeight != height) {
            conversionSampleStarted = 0L
            onOutputTransition()
        }
        surfaceWidth = width
        surfaceHeight = height
        if (gpuQualityMode && width > 0 && height > 0) {
            // mpv documents android-surface-size for gpu-context=android. mediacodec_embed
            // renders directly to the Surface and does not consume this GPU-context property.
            setProperty("android-surface-size", "${width}x$height")
            Timber.i("%s MPV GPU surface size changed=%dx%d", HDR_AUDIT_TAG, width, height)
        }
    }

    private var outputRefreshRate = 0f
    fun displayRefreshRateChanged(refreshRate: Float) {
        if (released || !gpuQualityMode || !refreshRate.isFinite() || refreshRate <= 0f || outputRefreshRate == refreshRate) return
        // Android/EGL's VO does not implement GET_DISPLAY_FPS. Feed the actual mode,
        // not source FPS or the requested mode (HDMI switching is asynchronous).
        if (MpvNativeBridge.nativeSetPropertyString(handle, "display-fps-override", refreshRate.toString()) >= 0) {
            outputRefreshRate = refreshRate
        }
    }

    fun detachSurface(surface: Surface? = null) {
        if (surface != null && attachedSurface !== surface) return
        if (!attached || released) return
        restoreSdrSurface()
        sdrSurfaceFailed = false
        sdrToHdr.reset()
        if (dvResumeWorkaround && loaded) {
            val resume = positionMs()
            finishDvPrime()
            fallbackTrackSelection = captureOutputRestartState()
            dvSurfaceRestart = true
            command("stop")
            pendingInitialSeekMs = resume
            loaded = false
            fileReady = false
            svpFilterDecided = false
            lastPlaybackPositionMs = resume
        }
        detachedVideoSelection = getPropertyString("vid") ?: "auto"
        // Stop the decoder before changing wid/VO; otherwise MediaCodec-copy can
        // deadlock while recreating its buffers against an output being removed.
        setProperty("vid", "no")
        // Disable the VO before releasing its native window; demuxing/audio remain alive.
        setProperty("vo", "null")
        setProperty("force-window", "no")
        runCatching { MpvNativeBridge.nativeDetachSurface(handle) }
        attached = false
        attachedSurface = null
        // A failed passthrough only held the picture; in the background its audio plays on and
        // the view reopens on the direct surface when it returns.
        if (hdrGpuFailure != null && loaded) setProperty("pause", if (nativePlayWanted) "no" else "yes")
    }

    override fun detachVideoOutput() = detachSurface()

    fun playPause() {
        if (wantsToPlay()) pause() else play()
    }

    override fun play() {
        conversionSampleStarted = 0L
        desiredPlayWhenReady = true
        if (ended) { seekTo(0); ended = false }
        setProperty("pause", "no")
    }
    /**
     * HDMI audio came back after a display mode switch: re-plan for the (possibly changed) sink
     * capabilities, then let mpv reopen its AudioTrack (`ao-reload`) like Kodi reopens AudioEngine.
     */
    override fun reopenAudioOutput() {
        if (released || !loaded) return
        // The route cache may still hold the capabilities from before the mode switch.
        com.ambient.tv.ui.player.AudioPassthroughPolicy.invalidateCache()
        applyAudioOptions()
        command("ao-reload")
        Timber.i("%s MPV audio output reopened after HDMI audio plug", HDR_AUDIT_TAG)
    }

    private var outputMuteRestore: String? = null
    override fun setOutputMuted(muted: Boolean) {
        if (released) return
        if (muted) {
            if (outputMuteRestore == null) outputMuteRestore = dvPrimeMute ?: getPropertyString("mute") ?: "no"
            if (dvPrimeMute != null) dvPrimeMute = "yes" else setProperty("mute", "yes")
        } else {
            val restore = outputMuteRestore ?: return
            outputMuteRestore = null
            if (dvPrimeMute != null) dvPrimeMute = restore else setProperty("mute", restore)
        }
    }

    override fun pause() {
        conversionSampleStarted = 0L
        desiredPlayWhenReady = false
        if (dvPrimeTarget == null) setProperty("pause", "yes")
    }

    override fun wantsToPlay(): Boolean = !released && desiredPlayWhenReady && !ended && errorMessage == null

    fun seekBy(deltaMs: Long) {
        conversionSampleStarted = 0L
        dvPrimeTarget?.let { seekTo((it + deltaMs).coerceAtLeast(0)); return }
        val seconds = deltaMs / 1000.0
        // Interactive seeks should land on the nearest keyframe. With MediaCodec, `exact`
        // makes mpv decode every frame from the preceding keyframe up to the requested timestamp;
        // on long-GOP HEVC this can leave the video plane apparently frozen while audio advances.
        command("seek", seconds.toString(), "relative+keyframes")
    }

    override fun seekTo(positionMs: Long) {
        conversionSampleStarted = 0L
        val wasEnded = ended
        val reopenEndedFile = ended && !fileReady
        awaitingEofReset = ended || awaitingEofReset
        ended = false
        observed["eof-reached"] = false
        if (reopenEndedFile) {
            reopenStream(positionMs.coerceAtLeast(0))
            return
        }
        if (dvPrimeTarget != null) {
            dvPrimeTarget = positionMs.coerceAtLeast(0)
            if (dvPrimeSeeking) command("seek", (dvPrimeTarget!! / 1000.0).toString(), "absolute+exact")
            return
        }
        // Prefer a keyframe seek for the Android hardware decoder for the same reason as seekBy().
        command("seek", (positionMs.coerceAtLeast(0L) / 1000.0).toString(), "absolute+keyframes")
        // keep-open pauses mpv internally at EOF without changing the user's play intent.
        // Seeking back must release that automatic pause, while preserving an explicit Pause.
        if (wasEnded) setProperty("pause", if (nativePlayWanted) "no" else "yes")
    }

    override fun restoreAfterRendererSwitch(positionMs: Long, playWhenReady: Boolean, isLive: Boolean) {
        desiredPlayWhenReady = playWhenReady
        if (isLive) pendingInitialSeekMs = null
        if (!isLive) {
            val resumePositionMs = positionMs.coerceAtLeast(0L)
            if (loaded) seekTo(resumePositionMs)
            else pendingInitialSeekMs = resumePositionMs
        }
        setProperty("pause", if (playWhenReady || dvPrimeTarget != null) "no" else "yes")
    }

    fun setSpeed(speed: Float) = setPropertyDouble("speed", if (bitstreamPermitted) 1.0 else speed.toDouble())

    override fun applyVideoPresentation(presentation: VideoPresentation) {
        mutableVideoPresentation.value = presentation
    }

    fun isConfirmedAudioOnly(): Boolean {
        if (!fileReady) return false
        val all = allTracks()
        return all.none { it.type == "video" } && all.any { it.type == "audio" }
    }

    fun setDeinterlace(enabled: Boolean, filter: String = "auto", automatic: Boolean = false) {
        val requestedMode = when {
            !enabled -> "off"
            automatic -> "auto"
            else -> "on"
        }
        val effectiveFilter = effectiveDeinterlaceFilter(filter, reducedVideoEffects(context))
        // Reapplying the backoff result (TVH recomposition) must not forget the Yadif step.
        // A different manual choice does, so the next overload will not turn that choice off.
        if (requestedMode != deinterlaceModeKey || effectiveFilter != deinterlaceFilterKey) {
            if (!released) onOutputTransition()
            deinterlaceSteppedFromNnedi = false
        }
        deinterlaceUpdateJob?.cancel()
        if (released) return
        val previousModeKey = deinterlaceModeKey
        val previousFilterKey = deinterlaceFilterKey
        // Record the selection immediately so the panel matches, including direct output
        // where applyDeinterlace cannot run the software filter chain.
        deinterlaceModeKey = requestedMode
        deinterlaceFilterKey = filter
        if (!gpuQualityMode) return
        deinterlaceUpdateJob = imageScope.launch {
            val weights = if (enabled && effectiveFilter in setOf("nnedi", "nnedi2x")) {
                try {
                    withContext(Dispatchers.IO) { NnediDeinterlaceWeights.path(context) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.w(error, "NNEDI weights unavailable")
                    deinterlaceFailed = true
                    deinterlaceModeKey = previousModeKey
                    deinterlaceFilterKey = previousFilterKey
                    return@launch
                }
            } else null
            if (!released && !applyDeinterlace(enabled, effectiveFilter, automatic, weights)) {
                // The panel must show what is actually running, not the rejected choice.
                deinterlaceModeKey = previousModeKey
                deinterlaceFilterKey = previousFilterKey
            }
        }
    }

    private fun applyDeinterlace(enabled: Boolean, effectiveFilter: String, automatic: Boolean, weights: String?): Boolean {
        if (!gpuQualityMode) {
            // Direct surface output cannot execute this app's software vf chain.
            return false
        }
        // Live TS reports interlaced channels at the field rate and decodes one picture per
        // field. Doubling those again (2x methods, mpv's send_field built-in) produced 100 fps.
        val singleRate = enabled && mpvSingleRateDeinterlace(broadcastSource, containerFps(), effectiveFilter, shieldSoftwareDecode)
        val graph = if (enabled) mpvDeinterlaceGraph(effectiveFilter, automatic, weights, singleRate) else null
        val vf = graph?.let { "lavfi=[$it]" }
        val previousEnabled = deinterlaceRequested
        val previousAutomatic = deinterlaceAutomatic
        val previousExplicitFilter = explicitDeinterlaceFilter
        val previousSingleRate = deinterlaceSingleRate
        val previousAppliedKey = appliedDeinterlaceKey
        deinterlaceRequested = enabled
        deinterlaceAutomatic = automatic
        deinterlaceSingleRate = singleRate
        appliedDeinterlaceKey = effectiveFilter
        deinterlaceFailed = false
        val desiredFilters = desiredImageFilters(
            effectiveMpvImageOptions(imageOptions, reducedVideoEffects(context), svpAllowed(context), mpvCheapDefaults(context)),
            sdrImageProcessing,
        )
        val copyBackNeeded = mpvCopyBackNeeded(
            loadedShaderKeys,
            desiredFilters.isNotEmpty() || activeCpuImageFilters.isNotEmpty(),
            enabled,
        )
        if (!trySetGpuDecoder(copyBackNeeded, cpuFrames = desiredFilters.isNotEmpty() || activeCpuImageFilters.isNotEmpty())) {
            deinterlaceRequested = previousEnabled
            deinterlaceAutomatic = previousAutomatic
            deinterlaceSingleRate = previousSingleRate
            appliedDeinterlaceKey = previousAppliedKey
            deinterlaceFailed = true
            return false
        }
        if (!rebuildMpvVideoFilterChain(desiredFilters, vf)) {
            // Restore the previous chain if a live remove/add was rejected by mpv.
            deinterlaceRequested = previousEnabled
            deinterlaceAutomatic = previousAutomatic
            deinterlaceSingleRate = previousSingleRate
            appliedDeinterlaceKey = previousAppliedKey
            rebuildMpvVideoFilterChain(desiredFilters, previousExplicitFilter)
            updateGpuDecoder()
            deinterlaceFailed = true
            return false
        }
        deinterlaceModeKey = when {
            !enabled -> "off"
            automatic -> "auto"
            else -> "on"
        }
        deinterlaceFilterKey = effectiveFilter
        // Deinterlacing changes the live vf chain independently of image options.
        // Start a new steady-playback sample for its actual, applied configuration.
        armRenderCheck()
        return true
    }

    fun updateImageOptions(options: MpvImageOptions) {
        // Persist the user's selection; Sparsam applies a cheap effective view at the boundary.
        val next = options.normalized()
        if (next != imageOptions) onOutputTransition()
        // An explicit edit needs a new probe; automatic refreshes keep the safety verdict.
        if (next.svp != imageOptions.svp || next.svpMode != imageOptions.svpMode) {
            svpOverloaded = false
            svpQualityCleared = false
            svpQualityProbeFailed = false
        }
        imageOptions = next
        if (!gpuQualityMode || released) return
        imageUpdateJob?.cancel()
        imageApplyStale = false
        imageUpdateJob = imageScope.launch {
            val optionsSnapshot = imageOptions
            val effective = effectiveMpvImageOptions(optionsSnapshot, reducedVideoEffects(context), svpAllowed(context), mpvCheapDefaults(context))
            val transfer = currentMpvTransfer()
            val sdr = fileReady && isMpvSdrTransfer(transfer)
            // PQ/HLG on this GPU VO: mpv tone-maps; only HDR-safe hooks may load.
            val hdr = fileReady && isMpvHdrTransfer(transfer)
            val gpuNext = rendersWithGpuNext()
            val pqOutput = hdr && hdrPassthroughActive
            val fittedScale = currentMpvFittedScale()
            val upscaling = fittedScale > MPV_ANY_UPSCALE_SCALE
            // Renderer hooks cannot run before vf. Skip them while SVP will
            // interpolate, instead of scaling every synthetic frame.
            val svpActive = "plex-svp" in desiredImageFilters(effective, sdr)
            sdrImageProcessing = sdr
            lastImageUpscaling = upscaling
            lastLumaShaderEligible = mpvLumaUpscalerWouldRun(effective.scaler, fittedScale)
            val sourceTransfer = currentMpvSourceTransfer()
            hdrImageTransfer = isMpvHdrTransfer(sourceTransfer)
            sourceHdrStandard = runtimeSourceHdrStandard(sourceTransfer)
            val requestedShaders = mpvImageShaderKeys(effective, sdr, upscaling, fittedScale, svpActive, hdr = hdr, gpuNext = gpuNext, pqOutput = pqOutput)
            val hasComputeShader = requestedShaders.any { MPV_IMAGE_SHADERS[it]?.requiresCompute == true }
            val computeSupport = if (hasComputeShader) {
                mpvComputeSupport(getPropertyString("gpu-api"))
            } else MpvComputeSupport.SUPPORTED
            val gpuNextVulkan = mpvGpuNextVulkan(getPropertyString("current-vo"), getPropertyString("gpu-api"))
            val shaderKeys = requestedShaders.filterNot {
                (MPV_IMAGE_SHADERS[it]?.requiresCompute == true && computeSupport == MpvComputeSupport.UNSUPPORTED) ||
                    (MPV_IMAGE_SHADERS[it]?.requiresGpuNext == true && !gpuNextVulkan)
            }
            val shaderReloadNeeded = shaderKeys != loadedShaderKeys ||
                (mpvTransferAwareShaderLoaded(shaderKeys) && casTransferProfile(transfer)?.suffix != loadedShaderTransferKey)
            // Extract files on IO; all native changes stay on the single MPV owner thread.
            val shaderFiles = if (shaderKeys.isNotEmpty() && shaderReloadNeeded) withContext(Dispatchers.IO) {
                shaderKeys.associateWith { key -> MPV_IMAGE_SHADERS[key]?.let { materializeShader(it, transfer, gpuNextVulkan) } }
            } else emptyMap()
            ensureActive()
            val liveSdr = fileReady && isMpvSdrTransfer(currentMpvTransfer())
            val liveSvp = "plex-svp" in desiredImageFilters(effective, liveSdr)
            val liveGpuNext = rendersWithGpuNext()
            if (released || optionsSnapshot != imageOptions || gpuNext != liveGpuNext ||
                transfer != currentMpvTransfer() || fittedScale != currentMpvFittedScale() ||
                svpActive != liveSvp ||
                sdr != liveSdr) {
                // A newer option change has its own job; anything else is retried by the poll.
                if (!released && optionsSnapshot == imageOptions) imageApplyStale = true
                return@launch
            }
            logImageOptions(effective, shaderKeys, fittedScale, sdr, hdr)
            applyImageOptions(effective, requestedShaders, shaderKeys, shaderFiles, sdr, transfer, computeSupport, hdr, gpuNext)
        }
    }

    private var lastImageLog: String? = null

    /** One line per distinct effective result: confirms Sparsam/Auto, scalers, shaders and SVP on a device. */
    private fun logImageOptions(effective: MpvImageOptions, shaderKeys: List<String>, fittedScale: Double, sdr: Boolean, hdr: Boolean) {
        val profile = performanceProfile(context)
        val line = mpvImageOptionsLogLine(
            playbackMode = profile.playbackMode.key, lowEnd = profile.lowEndDevice, reduced = profile.gateExpensiveVideoVisuals,
            stored = imageOptions, effective = effective, shaderKeys = shaderKeys, svpAllowed = svpAllowed(context),
            fittedScale = fittedScale, sdr = sdr, hdr = hdr, vo = configuredVo,
        )
        if (line == lastImageLog) return
        lastImageLog = line
        Timber.i("%s %s", HDR_AUDIT_TAG, line)
    }

    private fun applyImageOptions(
        o: MpvImageOptions,
        requestedShaders: List<String>,
        shaderKeys: List<String>,
        shaderFiles: Map<String, File?>,
        sdr: Boolean,
        transfer: String?,
        computeSupport: MpvComputeSupport,
        hdr: Boolean = false,
        gpuNext: Boolean = false,
    ) {
        conversionSampleStarted = 0L
        val warnings = mutableListOf<String>()
        fun property(name: String, value: String, fallback: String? = null) {
            // Keep the latest user choice for restoration without replacing conversion's curve.
            if (sdrToHdr.updateBaseline(name, value)) return
            if (getPropertyString(name) == value) return
            if (!trySetProperty(name, value)) {
                warnings += name
                if (fallback != null) trySetProperty(name, fallback)
            }
        }
        if (requestedShaders.any { MPV_IMAGE_SHADERS[it]?.requiresGpuNext == true && it !in shaderKeys }) {
            warnings += "shader-needs-vulkan"
        }
        if (requestedShaders.any { MPV_IMAGE_SHADERS[it]?.requiresCompute == true }) {
            when (computeSupport) {
                MpvComputeSupport.UNKNOWN -> warnings += "nis-compute-unknown"
                MpvComputeSupport.UNSUPPORTED -> warnings += "nis-compute-unsupported"
                MpvComputeSupport.SUPPORTED -> Unit
            }
        }
        if (shaderKeys != loadedShaderKeys ||
            (mpvTransferAwareShaderLoaded(shaderKeys) && casTransferProfile(transfer)?.suffix != loadedShaderTransferKey)
        ) {
            if (optionalCommand("change-list", "glsl-shaders", "clr", "")) {
                loadedShaderKeys = shaderKeys.filter { shaderKey ->
                    val path = shaderFiles[shaderKey]
                    (path != null && optionalCommand("change-list", "glsl-shaders", "append", path.absolutePath))
                        .also { if (!it) warnings += shaderKey }
                }
                loadedShaderTransferKey = if (mpvTransferAwareShaderLoaded(loadedShaderKeys)) casTransferProfile(transfer)?.suffix else null
            } else warnings += "glsl-shaders"
        }
        activeShaderPresetKey = o.scaler.takeIf { it in loadedShaderKeys }
        val desiredFilters = desiredImageFilters(o, sdr)
        // Latch only for the add itself. It is cleared once plex-svp is gone
        // so copy-back does not stick for the rest of the session.
        if ("plex-svp" in desiredFilters) svpCopyBackSession = true
        // Set frame access before adding filters. The SVP latch is only the
        // moment before plex-svp is linked; it is cleared once that filter leaves.
        val copyBackNeeded = mpvCopyBackNeeded(
            loadedShaderKeys,
            desiredFilters.isNotEmpty() || activeCpuImageFilters.isNotEmpty(),
            deinterlaceRequested,
        )
        if (!trySetGpuDecoder(copyBackNeeded, cpuFrames = desiredFilters.isNotEmpty() || activeCpuImageFilters.isNotEmpty())) warnings += "hwdec"
        if (!rebuildMpvVideoFilterChain(desiredFilters, explicitDeinterlaceFilter)) warnings += "vf-order"
        if ("plex-svp" !in activeCpuImageFilters) svpCopyBackSession = false
        svpFilterDecided = fileReady
        updateGpuDecoder()
        val (scale, cscale, dscale) = mpvEffectiveScalers(o)
        property("scale", scale, "lanczos")
        property("cscale", cscale, "lanczos")
        property("dscale", dscale, "mitchell")
        // SSimDownscaler is tuned for dscale=mitchell on gamma-light input, not linear light.
        val defaultLinearDownscaling = linearDownscalingDefault
            ?: getPropertyString("linear-downscaling")?.also { linearDownscalingDefault = it }
        defaultLinearDownscaling?.let { default ->
            property("linear-downscaling", if ("ssim_down" in loadedShaderKeys) "no" else default)
        }
        property("sigmoid-upscaling", if (o.sigmoid) "yes" else "no")
        property("scale-antiring", (o.antiring / 100.0).toString())
        property("cscale-antiring", (o.antiring / 100.0).toString())
        property("dscale-antiring", (o.antiring / 100.0).toString())
        property("deband", if (o.deband != "off") "yes" else "no")
        if (o.deband != "off") {
            val strength = when (o.deband) { "light" -> 16; "strong" -> 48; else -> 32 }
            property("deband-iterations", if (o.deband == "strong") "2" else "1")
            property("deband-threshold", strength.toString())
            property("deband-range", "16")
            property("deband-grain", if (o.deband == "light") "8" else "24")
        }
        // vo=gpu: mpv's unsharp mask, also on a tone-mapped HDR source. gpu-next ignores it,
        // so Light/Medium run as CAS there (see mpvSharpeningShader).
        property("sharpen", mpvBuiltinSharpen(o.sharpening, o.scaler, sdr || hdr, gpuNext))
        property("brightness", if (sdr) o.brightness.toString() else "0")
        property("contrast", if (sdr) o.contrast.toString() else "0")
        property("saturation", if (sdr) o.saturation.toString() else "0")
        property("gamma", if (sdr) o.gamma.toString() else "0")
        val toneMapToSdr = needsHdrToSdr(sourceHdrStandard ?: declaredHdr)
        property("target-prim", when {
            toneMapToSdr -> "bt.709"
            hdrPassthroughOpenGl -> "bt.2020"
            else -> "auto"
        })
        // "SDR-Kurve": the SDR target curve is explicit so the surface tag can follow it.
        val sdrCurve = if (toneMapToSdr || (sdr && !hdrPassthroughActive)) sdrCurveTransfer() else null
        appliedSdrCurve = sdrCurve
        property("target-trc", when {
            sdrCurve != null -> sdrCurve
            // The EGL surface was created PQ; keep rendering PQ into it.
            hdrPassthroughOpenGl -> "pq"
            else -> "auto"
        })
        property("target-peak", if (toneMapToSdr) "100" else "auto")
        property("tone-mapping", o.toneMapping, "auto")
        property("dither-depth", o.ditherDepth, "auto")
        property("interpolation", "no")
        property("video-sync", currentVideoSync())
        if (shieldSoftwareDecode) warnings += MPV_SHIELD_SOFTWARE_WARNING
        imageOptionWarnings = warnings.distinct()
        armRenderCheck()
    }

    /**
     * Non-SVP processing: 3 s of steady playback, then the configured sample (auto: 6 s).
     * SVP, NNEDI deinterlace, and a loaded heavy luma upscaler use 1 s plus a 2 s sample.
     * SVP also watches native cost and plex-svp bypass.
     * All active processing keeps checking decoder and renderer pressure after warmup. Quality (preset 3)
     * is not installed until the light preset survives one of these samples.
     * One overload takes one fallback step, then the existing pause dialog.
     */
    private fun armRenderCheck() {
        val effective = effectiveMpvImageOptions(imageOptions, reducedVideoEffects(context), svpAllowed(context), mpvCheapDefaults(context))
        val signature = mpvRenderCheckSignature(
            shaderKeys = loadedShaderKeys,
            cpuFilters = activeCpuImageFilters,
            deinterlaceEnabled = deinterlaceRequested,
            automaticDeinterlace = deinterlaceAutomatic,
            explicitDeinterlaceFilter = explicitDeinterlaceFilter,
            options = effective,
            hdrPassthrough = hdrPassthroughActive,
        )
        // Any other filter state ends a "keep watching anyway" for the previous one.
        if (signature != overloadSuspendedSignature) overloadSuspendedSignature = null
        if (signature == renderCheckSignature) return
        renderCheckSignature = signature
        renderCheckJob?.cancel()
        renderCheckJob = null
        if (signature == null || signature == overloadSuspendedSignature) return
        val svpAtStart = "plex-svp" in activeCpuImageFilters
        val fastSample = svpAtStart || mpvFastRenderSample(
            deinterlaceEnabled = deinterlaceRequested,
            deinterlaceFilter = deinterlaceFilterKey,
            heavyScalerLoaded = mpvHeavyScalerLoaded(effective.scaler, loadedShaderKeys),
        )
        val warmup = mpvRenderWarmupSeconds(fastSample)
        val sampleSeconds = mpvRenderSampleSeconds(fastSample, videoImagePreferences(context).frameDropTestSeconds)
        val verdictSecond = warmup + sampleSeconds.toInt()
        renderCheckJob = imageScope.launch {
            var steadySeconds = 0
            var baseline = 0L
            var costBaseline = 0L
            while (true) {
                delay(1_000)
                val svpNow = "plex-svp" in activeCpuImageFilters
                val fps = if (svpNow) svpOutputFps(displayFps() ?: svpDisplayFps(context), imageOptions.svp) else pipelineFps()
                if (fps == null || !isPlaying() || com.ambient.tv.ui.player.PlaybackOutputTransition.active() ||
                    getPropertyString("paused-for-cache") == "yes" || getPropertyString("seeking") == "yes") {
                    steadySeconds = 0
                    continue
                }
                steadySeconds++
                val dropped = (frameDropCount() ?: 0L) + (decoderFrameDropCount() ?: 0L)
                val (bypass, nativeCost) = if (svpNow) svpNativeSignals() else 0 to 0L
                if (steadySeconds == warmup) {
                    baseline = dropped
                    costBaseline = nativeCost
                }
                val sampleElapsed = (steadySeconds - warmup).coerceAtLeast(0)
                val costDelta = (nativeCost - costBaseline).coerceAtLeast(0L)
                val reachedVerdict = steadySeconds >= verdictSecond
                if (mpvRenderOverloaded(
                        droppedFrames = if (reachedVerdict) dropped - baseline else 0L,
                        fps = if (reachedVerdict) fps else 0.0,
                        seconds = when {
                            reachedVerdict -> sampleSeconds
                            svpNow && sampleElapsed > 0 -> sampleElapsed.toDouble()
                            else -> 0.0
                        },
                        svpBypassReason = if (svpNow) bypass else 0,
                        svpNativeCostUs = if (svpNow && sampleElapsed > 0) costDelta else 0L,
                    )
                ) {
                    if (sdrToHdr.engaged) {
                        // Prefer removing the optional conversion before the existing effects
                        // watchdog pauses playback. Re-sample the original effects afterward.
                        sdrToHdr.fail()
                        if (sdrToHdr.rollbackFailed) stopUnsafeConversionOutput()
                        else {
                            renderCheckSignature = null
                            updateImageOptions(imageOptions)
                        }
                        return@launch
                    }
                    // Deinterlace and upscaler step first so one overload does not also
                    // drop SVP. Nothing is reduced until the dialog's "reduce" is chosen.
                    if (heavyProcessingStep() != null) {
                        raiseRenderOverload(com.ambient.tv.ui.player.frameDropPercent(dropped - baseline, fps * sampleElapsed)) { fallbackHeavyProcessing() }
                    } else {
                        engageRenderOverload(signature, dropped - baseline, fps, bypass, costDelta, sampleElapsed.toDouble())
                    }
                    return@launch
                }
                if (!reachedVerdict) continue
                // Content complexity and thermal pressure can change after the initial sample.
                if (qualityProbePending()) {
                    if (svpQualityProbeFailed) return@launch
                    svpQualityCleared = true
                    updateImageOptions(imageOptions)
                    return@launch
                }
                steadySeconds = warmup
                baseline = dropped
                costBaseline = nativeCost
            }
        }
    }

    /** Cumulative plex-svp cost and bypass. Missing metadata reads as idle. */
    private fun svpNativeSignals(): Pair<Int, Long> {
        val bypass = getPropertyDouble("vf-metadata/plex-svp/bypass", 0.0).toInt()
        val analysis = getPropertyDouble("vf-metadata/plex-svp/analysis-us", 0.0)
        val synthesis = getPropertyDouble("vf-metadata/plex-svp/synthesis-us", 0.0)
        val cost = (analysis.takeIf { it.isFinite() && it > 0.0 } ?: 0.0) +
            (synthesis.takeIf { it.isFinite() && it > 0.0 } ?: 0.0)
        return bypass to cost.toLong()
    }

    private fun qualityProbePending(): Boolean =
        !svpQualityCleared && !svpQualityProbeFailed && "plex-svp" in activeCpuImageFilters &&
            effectiveMpvImageOptions(imageOptions, reducedVideoEffects(context), svpAllowed(context), mpvCheapDefaults(context)).svpMode == "quality"

    /**
     * Same path for dropped frames, native cost, and plex-svp bypass_reason:
     * pause and show the render-overload dialog. Quality is rewritten to the
     * lighter preset that held, including saved settings. Other modes still
     * drop SVP for the session.
     */
    private fun engageRenderOverload(
        signature: String,
        dropped: Long,
        fps: Double,
        bypass: Int,
        nativeCostUs: Long,
        sampleSeconds: Double,
    ) {
        Timber.w(
            "MPV render overload: drops=%d fps=%.3f bypass=%d costUs=%d sample=%.1f probe=%s sig=%s",
            dropped, fps, bypass, nativeCostUs, sampleSeconds, qualityProbePending(), signature,
        )
        val reducedSharpening = mpvSharpeningAfterOverload(imageOptions.sharpening, loadedShaderKeys)
        raiseRenderOverload(com.ambient.tv.ui.player.frameDropPercent(dropped, fps * sampleSeconds), when {
            "plex-svp" in activeCpuImageFilters -> ({ reduceSvpAfterOverload() })
            reducedSharpening != null -> ({ updateImageOptions(imageOptions.copy(sharpening = reducedSharpening)) })
            // Nothing lighter on the GPU path: "reduce" reopens the title on the direct surface.
            hdrPassthroughActive -> ({ failHdrPassthrough(MpvHdrGpuReason.OVERLOAD) })
            else -> null
        })
    }

    /** Quality steps to the preset that held (saved too); other modes drop SVP for the session. */
    private fun reduceSvpAfterOverload() {
        if ("plex-svp" !in activeCpuImageFilters) return
        if (effectiveMpvImageOptions(imageOptions, reducedVideoEffects(context), svpAllowed(context), mpvCheapDefaults(context)).svpMode == "quality") {
            rememberSurvivedSvpLevel()
            return
        }
        svpOverloaded = true
        updateImageOptions(imageOptions)
    }

    /** Store and announce the lighter preset instead of leaving quality selected. */
    private fun rememberSurvivedSvpLevel() {
        val preferences = videoImagePreferences(context)
        val update = mpvSvpLevelAfterHeavyFailure(
            live = imageOptions,
            stored = preferences.mpvImageOptions,
            survivedMode = MPV_SVP_PROBE_MODE,
            saveImageDefaults = saveImageDefaults,
        ) ?: return
        svpQualityProbeFailed = true
        svpQualityCleared = false
        if (update.persisted) preferences.mpvImageOptions = update.stored
        updateImageOptions(update.live)
        val level = context.getString(com.ambient.tv.ui.player.svpModeLabelRes(update.live.svpMode))
        com.ambient.tv.notifications.AppNotifications.post(
            com.ambient.tv.notifications.AppNotice(
                key = "playback:svp-level",
                message = com.ambient.tv.R.string.notice_svp_set_to,
                args = listOf(level),
                kind = com.ambient.tv.notifications.NoticeKind.PLAYBACK,
                phase = com.ambient.tv.notifications.NoticePhase.INFO,
                interruptPlayback = true,
            ),
        )
    }


    /**
     * One lighter preset per overload, then the dialog already armed by the caller.
     * Direct output applies nothing. Sparsam still forces its cheap effective view;
     * a reduced session may step live, but it does not rewrite a saved preset.
     */
    private fun heavyProcessingStep(): MpvHeavyBackoff? {
        if (!gpuQualityMode) return null
        val reduced = reducedVideoEffects(context)
        val preferences = videoImagePreferences(context)
        val effective = effectiveMpvImageOptions(imageOptions, reduced, svpAllowed(context), mpvCheapDefaults(context))
        return mpvHeavyProcessingBackoff(
            deinterlaceEnabled = deinterlaceRequested && deinterlaceModeKey != "off",
            deinterlaceMode = deinterlaceModeKey,
            deinterlaceFilter = deinterlaceFilterKey,
            storedDeinterlaceMode = preferences.tvheadendDeinterlaceModeKey,
            storedDeinterlaceFilter = preferences.tvheadendMpvDeinterlaceFilterKey,
            steppedFromNnedi = deinterlaceSteppedFromNnedi,
            allowDeinterlacePersist = !reduced,
            liveImage = imageOptions,
            storedImage = preferences.mpvImageOptions,
            heavyScalerLoaded = mpvHeavyScalerLoaded(effective.scaler, loadedShaderKeys),
            saveImageDefaults = saveImageDefaults && !reduced,
        )
    }

    private fun fallbackHeavyProcessing(): Boolean {
        val step = heavyProcessingStep() ?: return false
        val preferences = videoImagePreferences(context)
        deinterlaceUpdateJob?.cancel()
        when (step) {
            is MpvHeavyBackoff.Deinterlace -> {
                val update = step.update
                if (!update.turnedOff) deinterlaceSteppedFromNnedi = true
                val applied = applyDeinterlace(
                    update.mode != "off",
                    update.filter,
                    update.mode == "auto",
                    null,
                )
                if (!applied) return true
                deinterlaceBackoffGeneration++
                postPlaybackNotice(
                    key = "playback:deinterlace-level",
                    message = if (update.turnedOff) com.ambient.tv.R.string.notice_deinterlace_turned_off
                    else com.ambient.tv.R.string.notice_deinterlace_set_yadif,
                )
            }
            is MpvHeavyBackoff.Upscaler -> {
                val update = step.update
                if (update.persisted) preferences.mpvImageOptions = update.stored
                // Lanczos drops the luma shader. applyImageOptions then clears copy-back
                // unless deinterlace, a CPU filter, or another copy-back shader still needs it.
                updateImageOptions(update.live)
                postPlaybackNotice(
                    key = "playback:upscaler-level",
                    message = com.ambient.tv.R.string.notice_upscaler_set_lanczos,
                )
            }
        }
        return true
    }

    private fun postPlaybackNotice(key: String, message: Int) {
        com.ambient.tv.notifications.AppNotifications.post(
            com.ambient.tv.notifications.AppNotice(
                key = key,
                message = message,
                kind = com.ambient.tv.notifications.NoticeKind.PLAYBACK,
                phase = com.ambient.tv.notifications.NoticePhase.INFO,
                interruptPlayback = true,
            ),
        )
    }

    private fun rebuildMpvVideoFilterChain(
        desiredFilters: Map<String, String>,
        desiredDeinterlaceFilter: String?,
    ): Boolean {
        if (activeCpuImageFilters == desiredFilters && explicitDeinterlaceFilter == desiredDeinterlaceFilter) {
            return trySetProperty("deinterlace", if (desiredDeinterlaceFilter != null || !deinterlaceRequested) "no"
                else if (deinterlaceAutomatic) "auto" else "yes")
        }

        onOutputTransition()
        var success = true
        activeCpuImageFilters.keys.toList().forEach { label ->
            if (optionalCommand("vf", "remove", "@$label")) activeCpuImageFilters.remove(label) else success = false
        }
        if (explicitDeinterlaceFilter != null) {
            if (optionalCommand("vf", "remove", "@plex-deint")) explicitDeinterlaceFilter = null else success = false
        }
        if (!success) return false

        if (desiredDeinterlaceFilter != null) {
            setProperty("deinterlace", "no")
            if (optionalCommand("vf", "add", "@plex-deint:$desiredDeinterlaceFilter")) {
                explicitDeinterlaceFilter = desiredDeinterlaceFilter
            } else {
                return false
            }
        } else {
            setProperty("deinterlace", if (!deinterlaceRequested) "no" else if (deinterlaceAutomatic) "auto" else "yes")
        }

        var addSuccess = true
        orderedMpvCpuImageFilters(desiredFilters).forEach { (label, filter) ->
            if (optionalCommand("vf", "add", "@$label:$filter")) {
                activeCpuImageFilters[label] = filter
            } else {
                addSuccess = false
            }
        }
        return addSuccess
    }

    private fun trySetGpuDecoder(copyBack: Boolean, cpuFrames: Boolean = copyBack): Boolean {
        // Image-processing changes must not silently override a Vulkan/software choice.
        // svpCopyBackSession covers the moment before plex-svp is linked into vf.
        // After the filter leaves the chain the latch is cleared, and copy-back
        // stays only for deinterlace, a CPU filter, or a shader that still needs it.
        val desired = acceleration.hwdecForProcessing(copyBack || svpCopyBackSession, deinterlaceRequested,
            cpuFrames = cpuFrames || svpCopyBackSession)
        val current = getPropertyString("hwdec")
        if (current == desired) return true
        // Zero-copy only from the start of a file: copy→opaque MediaCodec on a running vo=gpu
        // left a black video plane. Keep copy-back until the next open.
        if (desired == MPV_ZERO_COPY_HWDEC && current != null && current.isNotBlank()) return true
        onOutputTransition()
        return trySetProperty("hwdec", desired)
    }

    private fun updateGpuDecoder(): Boolean = gpuQualityMode && trySetGpuDecoder(
        mpvCopyBackNeeded(loadedShaderKeys, activeCpuImageFilters.isNotEmpty(), deinterlaceRequested),
        cpuFrames = activeCpuImageFilters.isNotEmpty(),
    )

    private fun currentMpvLevels(): String? = getPropertyString(MPV_PROP_COLORLEVELS)
        ?.takeIf { it.isNotBlank() } ?: getPropertyString(MPV_PROP_DEC_COLORLEVELS)

    /** CAS curve for the current source; HDR also follows the luma range (limited/full). */
    private fun casTransferProfile(transfer: String?): MpvCasTransferProfile? = mpvCasTransferProfile(transfer, currentMpvLevels())

    private fun currentMpvTransfer(): String? = getPropertyString("video-params/gamma")
        ?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    private var hdrPassthroughCheckedAt = 0L
    private var lastHdrSurfaceDiagnostic: String? = null

    private fun checkHdrPassthrough(sourceGamma: String?) {
        if (!hdrPassthroughActive || released || !attached) return
        val now = SystemClock.elapsedRealtime()
        // The surface dataspace is a JNI query; twice a second is enough for a 3 s window.
        if (now - hdrPassthroughCheckedAt < 500L && hdr10SwapchainSupported != false) return
        hdrPassthroughCheckedAt = now
        val display = com.ambient.tv.ui.display.defaultDisplay(context)
        val displayRatio = com.ambient.tv.ui.display.displayHdrSdrRatio(display)
        // Only readings while the surface is marked PQ count as proof of the ratio signal.
        com.ambient.tv.ui.display.DisplayHdrRatioEvidence.note(display, displayRatio, hdrOutput = hdrPassthroughMonitor.confirmed)
        val openGlRoute = acceleration.output == "gpu"
        val facts = MpvHdrPassthroughFacts(
            fileReady = fileReady,
            playing = isPlaying() && getPropertyString("seeking") != "yes" &&
                getPropertyString("paused-for-cache") != "yes" && !com.ambient.tv.ui.player.PlaybackOutputTransition.active(),
            sourceGamma = sourceGamma,
            targetGamma = getPropertyString("video-target-params/gamma"),
            targetPrimaries = getPropertyString("video-target-params/primaries"),
            targetPixelFormat = getPropertyString("video-target-params/pixelformat"),
            dataSpace = if (fileReady && isMpvHdrTransfer(sourceGamma)) sdrToHdrProperties.outputDataSpace() else 0,
            openGl = openGlRoute,
            dataSpaceReadable = VulkanNativeProbe.available,
            swapchainHdr10 = hdr10SwapchainSupported,
            targetMaxLuma = getPropertyDouble("video-target-params/max-luma", 0.0).takeIf { it > 0.0 },
            targetMaxCll = getPropertyDouble("video-params/max-cll", 0.0).takeIf { it > 0.0 },
            displayHdrSdrRatio = displayRatio,
            displayRatioReliable = com.ambient.tv.ui.display.DisplayHdrRatioEvidence.reliable(display),
        )
        val wasConfirmed = hdrPassthroughMonitor.confirmed
        val wasSwitched = hdrPassthroughMonitor.displaySwitched
        val diagnostic = "target=${facts.targetGamma}/${facts.targetPrimaries}/${facts.targetPixelFormat} " +
            "dataspace=0x${facts.dataSpace.toString(16)} swapchainHdr10=${facts.swapchainHdr10} " +
            "maxLuma=${facts.targetMaxLuma} sourceMaxCll=${facts.targetMaxCll} " +
            "displayHdrSdrRatio=${facts.displayHdrSdrRatio ?: "unavailable"}"
        if (diagnostic != lastHdrSurfaceDiagnostic) {
            Timber.i("%s MPV HDR surface %s", HDR_AUDIT_TAG, diagnostic)
            lastHdrSurfaceDiagnostic = diagnostic
        }
        hdrPassthroughMonitor.update(facts, now)?.let(::failHdrPassthrough)
        if (!wasConfirmed && hdrPassthroughMonitor.confirmed) {
            hdrPassthroughConfirmed = true
            // Marked, not proven: this is the app's own swapchain/dataspace, not the TV's mode.
            Timber.i("%s MPV HDR surface marked (gpu-next, PQ/BT.2020); display=%s max-luma=%s max-cll=%s plausible=%s",
                HDR_AUDIT_TAG, mpvHdrDisplayStateLabel(hdrPassthroughMonitor.displaySwitched),
                facts.targetMaxLuma, facts.targetMaxCll, hdrPassthroughMonitor.metadataPlausible)
        }
        if (wasSwitched != hdrPassthroughMonitor.displaySwitched) {
            hdrDisplaySwitched = hdrPassthroughMonitor.displaySwitched
            Timber.i("%s MPV HDR display %s ratio=%s", HDR_AUDIT_TAG,
                mpvHdrDisplayStateLabel(hdrDisplaySwitched), facts.displayHdrSdrRatio ?: "unavailable")
        }
    }

    private fun failHdrPassthrough(reason: MpvHdrGpuReason) {
        if (hdrGpuFailure != null || !hdrGpuPassthrough) return
        Timber.w("%s MPV HDR passthrough failed: %s", HDR_AUDIT_TAG, reason)
        hdrPassthroughMonitor.fail(reason)
        renderCheckJob?.cancel()
        renderCheckJob = null
        val hold = mpvHdrFailureHold(desiredPlayWhenReady, attached)
        conversionSampleStarted = 0L
        if (hold.pauseNative && dvPrimeTarget == null) setProperty("pause", "yes")
        desiredPlayWhenReady = hold.playIntent
        hdrGpuFailure = reason
    }

    /**
     * Dolby Vision: the PQ base layer looks like HDR10, only the track's DV profile tells. Read
     * once per file as soon as the video track is known, on every route, so the view can keep a
     * DV file off the passthrough before it ever opens there ([dolbyVisionDetected]).
     */
    private fun latchDolbyVisionProfile(): Int? {
        dolbyVisionProfile?.let { return it }
        if (!fileReady || getPropertyDouble(MPV_PROP_VIDEO_TRACK_ID, 0.0) <= 0.0) return null
        val profile = getPropertyDouble(MPV_PROP_DOLBY_VISION_PROFILE, 0.0).toInt().coerceAtLeast(0)
        dolbyVisionProfile = profile
        if (dolbyVisionDetected != (profile > 0)) dolbyVisionDetected = profile > 0
        if (profile > 0) Timber.i("%s MPV Dolby Vision profile %d", HDR_AUDIT_TAG, profile)
        return profile
    }

    /**
     * Runtime standard reported to the players. The passthrough player keeps reporting Dolby
     * Vision after its failure (no flip back to HDR10); the direct route keeps its native DV
     * handling and reports the transfer, its DV reading is [dolbyVisionDetected].
     */
    private fun runtimeSourceHdrStandard(sourceGamma: String?): HdrStandard? {
        val profile = latchDolbyVisionProfile()
        return mpvRuntimeHdrStandard(sourceGamma, profile.takeIf { hdrGpuPassthrough && isMpvHdrTransfer(sourceGamma) })
    }

    private fun currentMpvSourceTransfer(): String? {
        val decoded = getPropertyString("video-dec-params/gamma")?.trim()?.lowercase()
        return decoded?.takeIf(::isMpvHdrTransfer) ?: currentMpvTransfer()
    }

    fun sourceDimensions(): Pair<Int, Int>? {
        val width = getPropertyDouble("width", 0.0).toInt()
        val height = getPropertyDouble("height", 0.0).toInt()
        return if (width > 0 && height > 0) width to height else null
    }

    private fun currentMpvFittedScale(): Double = mpvFittedScale(
        getPropertyDouble("video-params/dw", 0.0), getPropertyDouble("video-params/dh", 0.0), surfaceWidth, surfaceHeight,
    )

    private fun currentMpvUpscaling(): Boolean = currentMpvFittedScale() > MPV_ANY_UPSCALE_SCALE

    private fun desiredImageFilters(o: MpvImageOptions, sdr: Boolean): Map<String, String> =
        desiredCopyBackImageFilters(o, sdr)

    private fun desiredCopyBackImageFilters(o: MpvImageOptions, sdr: Boolean): Map<String, String> = buildMap {
        putAll(mpvCpuImageFilters(o, sdr))
        if (sdr && !svpOverloaded && svpAllowed(context)) {
            // Quality waits on light. Sparsam already forced svpMode off above this call.
            val mode = mpvSvpAppliedMode(o.svpMode, svpQualityCleared && !svpQualityProbeFailed)
            val tuning = if (o.svpMode == "quality" && mode != "quality") svpProbeOptions(o.svp) else o.svp
            val size = sourceDimensions()
            mpvSvpFilter(mode, pipelineFps(), displayFps() ?: svpDisplayFps(context), tuning, size?.first, size?.second)?.let {
                put("plex-svp", it)
            }
        }
    }

    private fun refreshImageSource() {
        if (released) return
        val transfer = if (fileReady) currentMpvTransfer() else null
        val sourceGamma = if (fileReady) currentMpvSourceTransfer() else null
        val hdr = isMpvHdrTransfer(sourceGamma)
        if (hdrImageTransfer != hdr) hdrImageTransfer = hdr
        val sdrConfirmed = fileReady && isMpvSdrTransfer(sourceGamma)
        if (sourceSdrConfirmed != sdrConfirmed) sourceSdrConfirmed = sdrConfirmed
        sourceHdrStandard = runtimeSourceHdrStandard(sourceGamma)
        if (dolbyVisionDetected && hdrPassthroughActive) failHdrPassthrough(MpvHdrGpuReason.DOLBY_VISION)
        checkHdrPassthrough(sourceGamma)
        // Network/live inputs may omit HDR until decoding starts. Reopen once on GL
        // rather than leaving the direct codec surface to present HDR as SDR.
        if (!gpuQualityMode && needsHdrToSdr(sourceHdrStandard) && fallbackAcceleration("hdr_to_sdr")) return
        // HDR found only now on a zero-copy plan: tone mapping runs on copy-back (not a zero-copy failure).
        if (gpuQualityMode && acceleration.zeroCopy && !zeroCopyRetried && needsHdrToSdr(sourceHdrStandard)) {
            failZeroCopy("hdr_to_sdr", remember = false)
            return
        }
        if (!gpuQualityMode) return
        refreshSdrCurve()
        if (probeShieldBroadcast()) return
        // container-fps arrives after the first setDeinterlace. Re-plan once per verdict so a
        // field-rate live input switches to the single-rate deinterlacer (50 fps, not 100).
        if (broadcastSource && deinterlaceRequested && fileReady && deinterlaceUpdateJob?.isActive != true) {
            val fieldRate = mpvSingleRateDeinterlace(broadcastSource, containerFps(), appliedDeinterlaceKey, shieldSoftwareDecode)
            if (fieldRate != deinterlaceSingleRate && fieldRate != deinterlaceSingleRateAttempt) {
                deinterlaceSingleRateAttempt = fieldRate
                setDeinterlace(true, appliedDeinterlaceKey, deinterlaceAutomatic)
            }
        }
        // Read live source metadata; target-trc changes video-target-params, not video-params.
        if (sdrToHdr.engaged && (sourceGamma == "linear" || !isMpvSdrTransfer(sourceGamma))) {
            sdrToHdr.reset()
            if (sdrToHdr.rollbackFailed) stopUnsafeConversionOutput()
        }
        // Geometry only matters once SDR (or HDR-safe) shaders can load; skip the reads otherwise.
        val sdrTransfer = transfer != null && (isMpvSdrTransfer(transfer) || isMpvHdrTransfer(transfer))
        val upscaling = if (sdrTransfer) currentMpvUpscaling() else lastImageUpscaling
        val lumaEligible = if (sdrTransfer) {
            mpvLumaUpscalerWouldRun(
                effectiveMpvImageOptions(imageOptions, reducedVideoEffects(context), svpAllowed(context), mpvCheapDefaults(context)).scaler,
                currentMpvFittedScale(),
            )
        } else lastLumaShaderEligible
        val svpSignature = if (imageOptions.svpMode == "off") "off" else
            "${svpAllowed(context)}:${pipelineFps()}:${displayFps() ?: svpDisplayFps(context)}:${sourceDimensions()}"
        // Only a native compute overload removes SVP work; format/timing passthrough is reported, not escalated.
        val svpBypass = if ("plex-svp" in activeCpuImageFilters)
            getPropertyDouble("vf-metadata/plex-svp/bypass", 0.0).toInt() else 0
        svpRuntimeReason = if ("plex-svp" in activeCpuImageFilters) svpRuntimeReason(svpBypass,
            getPropertyDouble("vf-metadata/plex-svp/passthrough", 0.0).toInt()) else null
        // Do not wait out the frame sample when native already bypassed a heavy mode.
        if (mpvSvpNativeOverloaded(svpBypass, 0L, 0.0) && !svpOverloaded && !renderOverloaded &&
            (renderCheckSignature == null || renderCheckSignature != overloadSuspendedSignature)) {
            engageRenderOverload("plex-svp-bypass", 0L, 0.0, svpBypass, 0L, 0.0)
        }
        if (transfer != lastImageTransfer || upscaling != lastImageUpscaling ||
            lumaEligible != lastLumaShaderEligible || svpSignature != lastSvpSignature ||
            (imageApplyStale && imageUpdateJob?.isActive != true)
        ) {
            lastImageTransfer = transfer
            lastImageUpscaling = upscaling
            lastLumaShaderEligible = lumaEligible
            lastSvpSignature = svpSignature
            updateImageOptions(imageOptions)
        }
    }

    private fun materializeShader(shader: MpvShaderAsset, transfer: String?, gpuNextVulkan: Boolean): File? = runCatching {
        val shaderDir = File(context.noBackupFilesDir, "mpv-shaders").apply { mkdirs() }
        val transferProfile = if (shader.transferAware) {
            casTransferProfile(transfer) ?: error("No transfer profile available for " + shader.fileName)
        } else null
        val artCnnOpenGl = shader.fileName.startsWith("quality_artcnn") && !gpuNextVulkan
        val cacheBaseName = if (artCnnOpenGl) shader.fileName.removeSuffix(".glsl") + "_opengl.glsl"
            else shader.cacheFileName ?: shader.fileName
        val fileName = transferProfile?.let {
            cacheBaseName.removeSuffix(".glsl") + "_" + it.suffix + ".glsl"
        } ?: cacheBaseName
        val shaderFile = File(shaderDir, fileName)
        val resolvedResourceId = (if (transferProfile?.singlePassHdr == true) MPV_CAS_HDR_RESOURCE else null)
            ?: shader.resourceId ?: shader.resourceName
            ?.let { name -> context.resources.getIdentifier(name, "raw", context.packageName) }
            ?.takeIf { it != 0 }
            ?: error("Bundled shader resource is missing: ${shader.resourceName ?: shader.fileName}")
        val source = context.resources.openRawResource(resolvedResourceId).bufferedReader().use { it.readText() }
        val transformed = mpvShaderSourceFor(shader, source, transferProfile, artCnnOpenGl)
        fun valid(file: File): Boolean = shader.nnedi3Preset?.let { preset ->
            runCatching { validateNnedi3ShaderSource(file.readText(), preset) }.getOrDefault(false)
        } ?: if (transferProfile != null) {
            runCatching {
                val source = file.readText()
                mpvCasSourceValid(source, transferProfile, shader.casBlend)
            }.getOrDefault(false)
        } else file.isFile

        // An APK update can fix shader code without changing the asset's name. In particular,
        // retaining the pre-fix Adaptive Sharpen source retains its NaN/black-frame bug.
        // This runs on IO only when loading shaders, never in the frame loop.
        if (shaderFile.isFile && (!valid(shaderFile) ||
                runCatching { shaderFile.readText() != transformed }.getOrDefault(true))) shaderFile.delete()
        if (!shaderFile.isFile) {
            // A cancelled IO task may finish copying while a new selection starts. Unique
            // temporary files avoid two workers truncating the same staging file.
            val pendingFile = File.createTempFile("${shader.fileName}.", ".pending", shaderDir)
            try {
                pendingFile.writeText(transformed)
                check(valid(pendingFile)) { "Bundled shader failed validation: ${shader.fileName}" }
                if (shaderFile.exists()) shaderFile.delete()
                check(pendingFile.renameTo(shaderFile)) { "Could not install ${shader.fileName}" }
            } finally {
                pendingFile.delete()
            }
        }
        shaderFile
    }.onFailure { error ->
        Timber.e(error, "Could not prepare MPV image shader %s", shader.fileName)
    }.getOrNull()

    fun videoTracks(): List<MpvTrack> = tracks("video")
    fun preferredAudioTrack(language: String?): MpvTrack? = com.ambient.tv.ui.player.choosePreferredAudioTrack(
        audioTracks(), language, audioSession.settings.tracks,
    ) { com.ambient.tv.ui.player.AudioTrackMetadata(it.language, it.title, it.channels) }

    override fun chapters(): List<com.ambient.tv.domain.model.VideoChapter> = buildList {
        val count = getPropertyDouble("chapter-list/count", 0.0).toInt().coerceIn(0, 2_000)
        repeat(count) { index ->
            val seconds = getPropertyDouble("chapter-list/$index/time", -1.0)
            if (seconds >= 0 && seconds.isFinite()) add(com.ambient.tv.domain.model.VideoChapter(
                (seconds * 1000).toLong(), getPropertyString("chapter-list/$index/title")))
        }
    }

    private val externalSubtitleRequests = mutableSetOf<String>()
    fun ensureExternalSubtitle(track: com.ambient.tv.domain.model.MediaTrack) {
        val url = track.externalUrl ?: return
        if (!fileReady || released || !externalSubtitleRequests.add(url)) return
        val title = (track.title ?: track.languageCode ?: track.language ?: "") + if (track.forced) " [Forced]" else ""
        // sub-add supports detached execution; never wait on a subtitle HTTP request on the UI thread.
        val result = MpvNativeBridge.nativeCommand(handle, arrayOf("async", "sub-add", url, "auto", title, track.languageCode ?: track.language ?: ""))
        if (result < 0) Timber.w("MPV external subtitle could not be scheduled (%d)", result)
    }

    fun selectExternalSubtitle(track: com.ambient.tv.domain.model.MediaTrack) {
        val url = track.externalUrl ?: return
        require(fileReady && !released) { "Subtitle renderer is not ready" }
        val title = (track.title ?: track.languageCode ?: track.language ?: "") + if (track.forced) " [Forced]" else ""
        externalSubtitleRequests.add(url)
        // "select" also selects an already loaded external track; never infer it from list order.
        check(MpvNativeBridge.nativeCommand(handle, arrayOf("async", "sub-add", url, "select", title, track.languageCode ?: track.language ?: "")) >= 0)
    }

    fun audioTracks(): List<MpvTrack> = tracks("audio")
    fun currentSpeed(): Float = getPropertyDouble("speed", 1.0).toFloat()
    fun subtitleTracks(): List<MpvTrack> = tracks("sub")

    fun selectVideo(id: Int?) = setProperty("vid", id?.toString() ?: "auto")
    fun selectAudio(id: Int?) {
        setProperty("aid", id?.toString() ?: "auto")
        // A new track gets its own passthrough retry.
        audioAoInitRetried = false
        // aid changes audio-codec-name asynchronously. Re-plan once immediately and once after
        // mpv has switched the decoder so passthrough/DSP/speed reflect the selected track.
        applyAudioOptions()
        workerHandler.postDelayed({
            if (!released) applyAudioOptions()
        }, 120L)
    }
    fun selectSubtitle(id: Int?) = setProperty("sid", id?.toString() ?: "no")

    fun cycleAudioTrack(): String? {
        val tracks = audioTracks()
        if (tracks.isEmpty()) return null
        val index = tracks.indexOfFirst { it.selected }
        val next = tracks[(if (index < 0) 0 else index + 1) % tracks.size]
        selectAudio(next.id)
        return next.label
    }

    fun cycleSubtitleTrack(): String? {
        val tracks = subtitleTracks()
        val selected = tracks.indexOfFirst { it.selected }
        if (tracks.isEmpty() || selected == tracks.lastIndex) {
            selectSubtitle(null)
            return null
        }
        val next = tracks[if (selected < 0) 0 else selected + 1]
        selectSubtitle(next.id)
        return next.label
    }

    private fun tracks(type: String): List<MpvTrack> = allTracks().filter { it.type == type }

    private var trackCache: List<MpvTrack> = emptyList()
    private var trackCacheKey: String? = null
    private var trackGeneration = 0

    /**
     * The whole track list costs 1 + 8·N JNI reads and is asked for on every recomposition, every
     * session tick and every audio-only check. Titles and languages do not change while a file is
     * loaded, so it is re-read only when the file, the track count or a selection changes.
     */
    @Synchronized
    private fun allTracks(): List<MpvTrack> {
        val count = (observedDouble("track-list/count") ?: getPropertyDouble("track-list/count", 0.0)).toInt().coerceAtLeast(0)
        val key = buildString {
            append(trackGeneration).append('|').append(count)
            for (property in TRACK_SELECTION_PROPERTIES) append('|').append(getPropertyString(property))
        }
        if (key == trackCacheKey) return trackCache
        val tracks = buildList {
            repeat(count) { i ->
                val type = getPropertyString("track-list/$i/type") ?: return@repeat
                val id = getPropertyDouble("track-list/$i/id", -1.0).toInt()
                if (id < 0) return@repeat
                add(
                    MpvTrack(
                        id = id,
                        type = type,
                        title = getPropertyString("track-list/$i/title"),
                        channels = getPropertyDouble("track-list/$i/demux-channel-count", 0.0).toInt(),
                        codec = getPropertyString("track-list/$i/codec"),
                        language = getPropertyString("track-list/$i/lang"),
                        selected = getPropertyString("track-list/$i/selected") == "yes",
                        forced = getPropertyString("track-list/$i/forced") == "yes" ||
                            getPropertyString("track-list/$i/title")?.contains("forced", ignoreCase = true) == true,
                    ),
                )
            }
        }
        trackCache = tracks
        trackCacheKey = key
        return tracks
    }

    @Synchronized
    private fun invalidateTracks() {
        trackGeneration++
        trackCacheKey = null
    }

    fun positionMs(): Long {
        dvPrimeTarget?.let { return it }
        val current = (getPropertyDouble("time-pos", 0.0).coerceAtLeast(0.0) * 1000.0).toLong()
        if (current > 0L) lastPlaybackPositionMs = current
        // During detach/VO teardown time-pos can briefly read 0. Renderer switches must
        // still resume from the last confirmed clock, not the start of the file.
        return if (current > 0L) current else lastPlaybackPositionMs.coerceAtLeast(0L)
    }
    fun durationMs(): Long = ((if (isObserved("duration")) observedDouble("duration") ?: 0.0
        else getPropertyDouble("duration", 0.0)).coerceAtLeast(0.0) * 1000.0).toLong()
    fun isPaused(): Boolean = if (dvPrimeTarget != null) !desiredPlayWhenReady
        else observedFlag("pause") ?: (getPropertyString("pause") == "yes")
    fun isPlaying(): Boolean = dvPrimeTarget == null && !released && loaded && !isPaused() && !ended && errorMessage == null
    private fun restoreSdrSurface(): Boolean {
        val original = sdrSurfaceOriginal ?: return true
        sdrSurfaceOriginal = null
        val surface = attachedSurface?.takeIf { it.isValid } ?: return true
        // A new swapchain may already have switched to PQ/HLG. Never overwrite it.
        return runCatching {
            !VulkanNativeProbe.available || VulkanNativeProbe.dataSpace(surface) !in MPV_SDR_SURFACE_DATASPACES ||
                VulkanNativeProbe.restoreDataSpace(surface, original)
        }.getOrDefault(false)
    }

    /**
     * Configured SDR curve for target-trc ([mpvSdrCurveTransfer]). Auto reads the display's HDR state from
     * the HDR/SDR ratio (API 34+) with the R1 evidence; below API 34 there is no ratio, so Auto stays BT.1886.
     */
    private fun sdrCurveTransfer(): String {
        val display = com.ambient.tv.ui.display.defaultDisplay(context)
        return mpvSdrCurveTransfer(videoImagePreferences(context).mpvSdrCurveKey,
            mpvSdrAutoDisplayInHdr(com.ambient.tv.ui.display.displayHdrSdrRatio(display),
                com.ambient.tv.ui.display.DisplayHdrRatioEvidence.reliable(display)))
    }

    /** SDR curve last sent as target-trc; null while target-trc is not an SDR curve (PQ, auto, conversion). */
    private var appliedSdrCurve: String? = null
    private var sdrCurveCheckedAtMs = 0L

    /**
     * Auto follows the display: after a display or HDR/SDR mode change (re-read every 2 s while playing)
     * the SDR curve is updated in place; the surface tag follows via [syncSdrSurface].
     */
    private fun refreshSdrCurve() {
        val applied = appliedSdrCurve ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - sdrCurveCheckedAtMs < 2_000L) return
        sdrCurveCheckedAtMs = now
        if (sdrToHdr.engaged) return
        val desired = sdrCurveTransfer()
        if (mpvSdrCurveNeedsUpdate(applied, desired, getPropertyString("target-trc")) && trySetProperty("target-trc", desired)) {
            appliedSdrCurve = desired
            Timber.i("%s MPV SDR curve re-evaluated: %s -> %s", HDR_AUDIT_TAG, applied, desired)
        }
    }

    /** Surface tag requested for SDR GPU output, null while untagged (stats). */
    @Volatile var sdrSurfaceDataSpace: Int? = null
        private set

    private fun syncSdrSurface() {
        if (released || !attached || !fileReady || sdrSurfaceFailed) return
        val surface = attachedSurface?.takeIf { it.isValid } ?: return
        if (!VulkanNativeProbe.available) return
        val vo = getPropertyString("current-vo")
        val filtered = getPropertyString("video-out-params/gamma")
        val targetTransfer = getPropertyString("video-target-params/gamma")
        val targetPrimaries = getPropertyString("video-target-params/primaries")
        val desired = mpvSdrSurfaceDataSpace(vo, getPropertyString("video-params/gamma"), filtered,
            getPropertyString("video-params/primaries"), targetTransfer, targetPrimaries,
            sdrToHdr.engaged || listOf("icc-profile", "image-lut", "target-lut")
                .any { !getPropertyString(it).isNullOrBlank() },
            toneMappedToSdr = gpuQualityMode && !hdrPassthroughActive && needsHdrToSdr(sourceHdrStandard ?: declaredHdr))
        sdrSurfaceDataSpace = desired
        if (desired == null) {
            if (!restoreSdrSurface()) stopUnsafeConversionOutput()
            return
        }
        try {
            val current = VulkanNativeProbe.dataSpace(surface)
            if (current == desired) return
            if (sdrSurfaceOriginal == null) sdrSurfaceOriginal = current
            if (!VulkanNativeProbe.restoreDataSpace(surface, desired)) error("Native window rejected BT.709")
            Timber.i("HDR-AUDIT MPV SDR surface: %s, target-trc=%s vo=%s", mpvSdrSurfaceLabel(desired), targetTransfer, vo)
        } catch (e: Exception) {
            if (!restoreSdrSurface()) stopUnsafeConversionOutput()
            sdrSurfaceFailed = true
            Timber.w(e, "MPV SDR surface colour signalling unavailable")
        }
    }

    override fun status(): PlaybackStatus {
        pollEvents()
        syncSdrSurface()
        if (!vulkanOutputReported && acceleration.vulkan && attached && fileReady &&
            getPropertyString("current-vo") == "gpu-next" && outputPixelFormat() != null) {
            vulkanOutputReported = true
            VideoAccelerationRuntime.reportOutput("mpv")
        }
        val eof = observedFlag("eof-reached") ?: (getPropertyString("eof-reached") == "yes")
        if (!eof) awaitingEofReset = false
        if (loaded && fileReady && !awaitingEofReset && dvPrimeTarget == null && eof) ended = true
        return PlaybackStatus(positionMs(), durationMs(), isPlaying(), ended, errorMessage)
    }
    /** First picture shown (or audio file loaded): ends the watchdog's opening phase. */
    fun openingDone(): Boolean = mpvOpeningDone(fileReady, audioOnly,
        if (fileReady && !audioOnly) getPropertyDouble("video-params/w", 0.0).toInt() else 0)
    fun videoCodec(): String? = getPropertyString("video-codec") ?: getPropertyString("video-format")
    fun audioCodec(): String? = if (isObserved("audio-codec-name")) (observed["audio-codec-name"] as? String)?.takeIf(String::isNotEmpty)
        else getPropertyString("audio-codec-name")

    /** Re-plan once when mpv first reports a codec. Init often runs before that name exists. */
    fun noteAudioCodecName(codec: String?) {
        if (observedAudioCodecApplied || codec.isNullOrBlank()) return
        observedAudioCodecApplied = true
        applyAudioOptions()
    }
    fun videoSize(): String? {
        val w = getPropertyDouble("width", 0.0).toInt()
        val h = getPropertyDouble("height", 0.0).toInt()
        return if (w > 0 && h > 0) "${w}×${h}" else null
    }
    fun videoAspectRatio(): Float? {
        val reported = getPropertyDouble("video-params/aspect", 0.0).toFloat()
        if (reported.isFinite() && reported > 0f) return reported
        val width = getPropertyDouble("width", 0.0).toFloat()
        val height = getPropertyDouble("height", 0.0).toFloat()
        return if (width > 0f && height > 0f) width / height else null
    }
    fun containerFps(): Double? = getPropertyDouble("container-fps", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
    /**
     * Picture rate leaving the deinterlacer: the SVP source and the expected render rate.
     * Broadcast only (live TV, TVHeadend recordings); other VOD keeps container-fps as before.
     */
    private fun pipelineFps(): Double? = if (broadcastSource) {
        mpvDeinterlacedFps(containerFps(), deinterlaceRequested, appliedDeinterlaceKey, deinterlaceSingleRate)
    } else containerFps()
    fun videoFps(): Double = getPropertyDouble("estimated-vf-fps", 0.0)
    fun decoder(): String? = getPropertyString("hwdec-current")?.takeIf { it.isNotBlank() && it != "no" }

    fun videoBitrateBitsPerSecond(): Long? = getPropertyDouble("video-bitrate", -1.0).takeIf { it > 0.0 }?.toLong()
    fun audioBitrateBitsPerSecond(): Long? = getPropertyDouble("audio-bitrate", -1.0).takeIf { it > 0.0 }?.toLong()
    /**
     * True while a missing picture is explained by the network, not by a stuck decoder: mpv is
     * seeking, paused for its cache, or holds less than a second of demuxed data. A stuck
     * MediaCodec instead stops consuming an already filled demuxer queue.
     */
    fun waitingForData(): Boolean {
        val pausedForCache = observedFlag("paused-for-cache") ?: (getPropertyString("paused-for-cache") == "yes")
        val seeking = observedFlag("seeking") ?: (getPropertyString("seeking") == "yes")
        if (pausedForCache || seeking) return true
        val buffered = cacheDurationSeconds() ?: return false
        return mpvStarvedForData(buffered)
    }

    fun cacheDurationSeconds(): Double? = (if (isObserved("demuxer-cache-duration")) observedDouble("demuxer-cache-duration") ?: Double.NaN
        else getPropertyDouble("demuxer-cache-duration", Double.NaN)).takeIf { it.isFinite() && it >= 0.0 }
    fun cacheSpeedBytesPerSecond(): Long? = getPropertyDouble("cache-speed", -1.0).takeIf { it > 0.0 }?.toLong()
    fun avSyncSeconds(): Double? = getPropertyDouble("avsync", Double.NaN).takeIf { it.isFinite() }
    fun frameDropCount(): Long? = getPropertyDouble("frame-drop-count", Double.NaN).takeIf { it.isFinite() && it >= 0.0 }?.toLong()
    internal fun isMuted(): Boolean = getPropertyString("mute") == "yes"
    fun decoderFrameDropCount(): Long? = getPropertyDouble("decoder-frame-drop-count", Double.NaN).takeIf { it.isFinite() && it >= 0.0 }?.toLong()
    fun displayFps(): Double? = getPropertyDouble("display-fps", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
    fun pixelFormat(): String? = getPropertyString("video-params/pixelformat")?.takeIf { it.isNotBlank() }
    fun outputPixelFormat(): String? = getPropertyString("video-out-params/pixelformat")?.takeIf { it.isNotBlank() }
    fun activeVideoFilters(): String? = getPropertyString("vf")?.takeIf { it.isNotBlank() && it != "[]" }
    /** Raw mpv `deinterlace` property (yes/no/auto). Explicit vf deinterlacers leave this at "no". */
    fun activeDeinterlace(): String? = getPropertyString("deinterlace")?.takeIf { it.isNotBlank() }
    /** Live SVP state for the panel; null with SVP off. */
    fun svpState(): SvpState? {
        val mode = imageOptions.svpMode
        if (mode == "off") return null
        val inChain = "plex-svp" in activeCpuImageFilters
        val effective = effectiveMpvImageOptions(imageOptions, reducedVideoEffects(context), svpAllowed(context), mpvCheapDefaults(context))
        val unavailable = when {
            !gpuQualityMode -> SvpInactiveReason.ENGINE
            !performanceProfile(context).allowSvpInterpolation -> SvpInactiveReason.PERFORMANCE_MODE
            !SvpNative.isEnabledAndAvailable() -> SvpInactiveReason.LIBRARY
            effective.svpMode == "off" -> SvpInactiveReason.PERFORMANCE_MODE
            hdrImageTransfer -> SvpInactiveReason.HDR
            else -> null
        }
        val size = sourceDimensions()
        val runtime = if (inChain) svpRuntimeReason(
            getPropertyDouble("vf-metadata/plex-svp/bypass", 0.0).toInt(),
            getPropertyDouble("vf-metadata/plex-svp/passthrough", 0.0).toInt(),
        ) else null
        val backend = if (inChain) when (getPropertyString("vf-metadata/plex-svp/backend")) {
            "vulkan" -> 2; "cpu" -> 1; else -> 0
        } else 0
        return svpState(
            mode = mode,
            custom = imageOptions.svp.isCustom,
            unavailable = unavailable,
            overloaded = svpOverloaded,
            decision = svpDecision(pipelineFps(), displayFps() ?: svpDisplayFps(context), effective.svp, size?.first, size?.second),
            inChain = inChain,
            runtimeReason = runtime,
            backend = backend,
        )
    }
    fun svpStatus(): String? = formatSvpStatus(svpState())
    fun activeScaler(): String? = if (supportsGpuFilters) getPropertyString("scale")?.takeIf { it.isNotBlank() } else null
    /** `scale` is a configured property even when the source already fits the output. */
    fun activeUpscaling(): Boolean? = currentMpvFittedScale().takeIf { it.isFinite() }
        ?.let { it > MPV_ANY_UPSCALE_SCALE }
    fun activeChromaScaler(): String? = if (supportsGpuFilters) getPropertyString("cscale")?.takeIf { it.isNotBlank() } else null
    fun activeDownscaler(): String? = if (supportsGpuFilters) getPropertyString("dscale")?.takeIf { it.isNotBlank() } else null
    fun activeShaderPreset(): String? = if (supportsGpuFilters) activeShaderPresetKey else null
    fun configuredVideoOutput(): String? = getPropertyString("current-vo")?.takeIf { it.isNotBlank() }
        ?.let { if (it == "gpu-next" && acceleration.vulkan) "$it / Vulkan" else it }
    /** Output format plus the layout mpv hands to the AO, e.g. "s16 · 5.1 · 6 ch". */
    fun audioOutputFormat(): String? = getPropertyString("audio-out-params/format")?.takeIf { it.isNotBlank() }?.let { format ->
        mpvAudioOutputText(format, getPropertyString("audio-out-params/channels"),
            getPropertyDouble("audio-out-params/channel-count", 0.0).toInt())
    }

    private var lastAudioOutputLog: String? = null
    private var lastAudioOutputLogDiagnostics = false

    /** Logs the opened audio output once per change (source layout, AO layout, driver, plan). */
    private fun logAudioOutput() {
        val format = getPropertyString("audio-out-params/format")?.takeIf { it.isNotBlank() } ?: return
        val plan = audioSession.outputPlan()
        val line = "MPV audio output ao=${getPropertyString("current-ao") ?: "?"} " +
            "in=${getPropertyString("audio-params/channels") ?: "?"}(${getPropertyDouble("audio-params/channel-count", 0.0).toInt()}ch) " +
            "out=$format ${getPropertyString("audio-out-params/channels") ?: "?"}(${getPropertyDouble("audio-out-params/channel-count", 0.0).toInt()}ch) " +
            "audio-channels=${getPropertyString("audio-channels") ?: "?"} spdif=${getPropertyString("audio-spdif").orEmpty().ifEmpty { "-" }} " +
            "plan=${plan.mode}/${plan.channels} routePcmChannels=${audioSession.route.pcmChannels}"
        val diagnostics = com.ambient.tv.diagnostics.DiagnosticLog.enabled
        if (line == lastAudioOutputLog && diagnostics == lastAudioOutputLogDiagnostics) return
        lastAudioOutputLog = line
        lastAudioOutputLogDiagnostics = diagnostics
        com.ambient.tv.ui.player.logAudioRouteLine(line)
    }
    fun audioOutputDriver(): String? = getPropertyString("current-ao")?.takeIf { it.isNotBlank() }

    /** Bumped when codecs, geometry or decoder may have changed (load, end, A/V reconfig). */
    @Volatile var mediaInfoGeneration = 0
        private set


    private fun observedFlag(name: String): Boolean? = if (name in observed) observed[name] as? Boolean else null
    private fun observedDouble(name: String): Double? = if (name in observed) observed[name] as? Double else null
    private fun isObserved(name: String) = name in observed

    /**
     * Subscribes to [OBSERVED_PROPERTIES] and starts a waiter thread that blocks on mpv's wakeup
     * pipe. Events are drained only on the MPV owner thread, never on the Android main looper.
     */
    private fun startEventPump() {
        OBSERVED_PROPERTIES.forEachIndexed { index, (name, format) ->
            MpvNativeBridge.nativeObserveProperty(handle, index + 1L, name, format)
        }
        val fd = MpvNativeBridge.nativeWakeupFd(handle)
        if (fd < 0) return
        pumpRunning.set(true)
        eventDriven = true
        eventPump = Thread({
            while (pumpRunning.get()) {
                val woken = MpvNativeBridge.nativeWaitWakeup(fd, EVENT_PUMP_POLL_MS.toInt())
                if (woken < 0) break
                if (woken == 1 && pumpRunning.get() && drainPosted.compareAndSet(false, true)) {
                    eventHandler.post {
                        drainPosted.set(false)
                        if (!released) pollEvents()
                    }
                }
            }
        }, "mpv-events").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Ends the waiter before the native context is destroyed. Returns false when the waiter is
     * still alive: it polls and drains the wakeup pipe, whose descriptor the destroy closes, so a
     * late waiter could read from whatever file the system hands that descriptor number next.
     */
    private fun stopEventPump(): Boolean {
        if (!pumpRunning.getAndSet(false)) return true
        eventDriven = false
        runCatching { MpvNativeBridge.nativeInterruptWakeup(handle) }
        val pump = eventPump
        // The interrupt wakes it at once; the bound covers one full poll timeout as a fallback.
        runCatching { pump?.join(EVENT_PUMP_POLL_MS + 500L) }
        eventPump = null
        return pump?.isAlive != true
    }

    private fun recordPropertyChange() {
        val index = MpvNativeBridge.nativeEventPropertyId(handle).toInt() - 1
        val name = OBSERVED_PROPERTIES.getOrNull(index)?.first ?: return
        observed[name] = when (MpvNativeBridge.nativeEventPropertyFormat(handle)) {
            MpvNativeBridge.FORMAT_FLAG -> MpvNativeBridge.nativeEventPropertyFlag(handle)
            MpvNativeBridge.FORMAT_DOUBLE -> MpvNativeBridge.nativeEventPropertyDouble(handle)
            MpvNativeBridge.FORMAT_STRING -> MpvNativeBridge.nativeEventPropertyString(handle)
            else -> null
        }
        if (name == "audio-codec-name" || name == "video-codec" || name == "hwdec-current") mediaInfoGeneration++
        // Observe even a short seek/cache pause which starts and ends between HDR polls.
        if (observed[name] == true && (name == "pause" || name == "seeking" || name == "paused-for-cache"))
            conversionSampleStarted = 0L
    }

    private var missingHardwareVideoSinceNs = 0L
    private var audioDroppedSinceNs = 0L

    /**
     * mpv's AO init failure ends only audio-only files with -14. With video, mpv drops the audio
     * track and keeps playing silent video. The same re-plan therefore also runs when the
     * selected audio track has had no active output for [MPV_AUDIO_DROP_CONFIRM_NS].
     */
    private fun checkDroppedAudio(): Boolean {
        // Cheapest read first: normally an audio track is active and nothing else is queried.
        val dropped = !audioSession.forceStereoPcm && fileReady && desiredPlayWhenReady && !audioOnly &&
            dvPrimeTarget == null && getPropertyString("current-tracks/audio/id").isNullOrBlank() &&
            positionMs() > 1_500 &&
            mpvAudioOutputDropped(
                selectedAid = getPropertyString("aid"),
                currentAudioId = null,
                hasAudioTracks = runCatching { audioTracks().isNotEmpty() }.getOrDefault(false),
                hasVideoTracks = runCatching { videoTracks().isNotEmpty() }.getOrDefault(false),
            )
        if (!dropped) {
            audioDroppedSinceNs = 0L
            return false
        }
        val now = System.nanoTime()
        if (audioDroppedSinceNs == 0L) audioDroppedSinceNs = now
        if (now - audioDroppedSinceNs < MPV_AUDIO_DROP_CONFIRM_NS) return false
        audioDroppedSinceNs = 0L
        recoverAudioOutputFailure("audio track dropped by mpv", positionMs())
        return true
    }

    /**
     * MPV_ERROR_AO_INIT_FAILED or a dropped audio track. Reject only the passthrough codec and
     * re-plan (multichannel PCM or local AC3). Force stereo if that open fails as well.
     */
    private fun recoverAudioOutputFailure(cause: String, reopenPositionMs: Long) {
        fallbackTrackSelection = listOf("aid", "sid", "speed").map(::getPropertyString)
        val rejected = mutableListOf<String>()
        syncAudioFilters("", false, rejected)
        fileReady = false
        svpFilterDecided = false
        ended = false
        // Only a bitstream codec has a different plan to fall back to. A PCM codec
        // (AAC, MP3, FLAC, Opus) would reopen the same PCM output, so it goes
        // straight to the stereo retry.
        val codec = audioSession.sourceCodec
        if (audioSession.options.normalized().forcesOutput() && !audioSession.forcedChannelsFailed) {
            // A forced 5.1/7.1 asked for more than the output accepts: follow the
            // route again for this playback; the saved setting stays as it is.
            audioSession.forcedChannelsFailed = true
            com.ambient.tv.ui.player.logAudioRouteLine("MPV audio output rejected forced output " +
                "(${audioSession.options.forceChannels}ch, ${audioSession.options.output}; $cause); using the reported output for this playback")
        } else if (!audioAoInitRetried && mpvBitstreamCodec(codec)) {
            audioAoInitRetried = true
            com.ambient.tv.ui.player.AudioCodecRejects.reject(codec)
        } else {
            audioSession.forceStereoPcm = true
            com.ambient.tv.ui.player.logAudioRouteLine("MPV audio output failed ($cause); stereo PCM for this playback")
        }
        applyAudioOptions()
        reopenStream(reopenPositionMs)
    }

    fun pollEvents() {
        if (released) return
        repeat(64) {
            val event = MpvNativeBridge.nativePollEvent(handle)
            if (event == MPV_EVENT_FILE_LOADED || event == MPV_EVENT_END_FILE ||
                event == MPV_EVENT_VIDEO_RECONFIG || event == MPV_EVENT_AUDIO_RECONFIG) mediaInfoGeneration++
            if (event == MPV_EVENT_AUDIO_RECONFIG) logAudioOutput()
            if (event != 0) _stateChanges.value++
            if (event == MPV_EVENT_PROPERTY_CHANGE) {
                recordPropertyChange()
                return@repeat
            }
            when (event) {
                0 -> {
                    refreshImageSource()
                    // mediacodec_embed cannot present software frames. MPV can drop an
                    // unsupported video track (e.g. AV1 on Shield) without an end-file error
                    // and continue playing audio. Reopen once on GLES after playback has
                    // advanced, so normal decoder startup and audio-only files are excluded.
                    if (fileReady && attached && desiredPlayWhenReady && !nativeHdrPreferred &&
                        acceleration.output == "direct" && !accelerationRetried && positionMs() > 1_000 &&
                        getPropertyString("hwdec-current").let { it.isNullOrBlank() || it == "no" } &&
                        videoTracks().isNotEmpty()) {
                        val now = System.nanoTime()
                        if (missingHardwareVideoSinceNs == 0L) missingHardwareVideoSinceNs = now
                        // Surface reattachment briefly clears hwdec-current even for supported
                        // codecs. Require a sustained absence before replacing the video path.
                        if (now - missingHardwareVideoSinceNs >= 2_000_000_000L) {
                            fallbackAcceleration("mediacodec_unavailable")
                            missingHardwareVideoSinceNs = 0L
                            return
                        }
                    } else missingHardwareVideoSinceNs = 0L
                    if (checkDroppedAudio()) return
                    if (fileReady && acceleration.zeroCopy && checkZeroCopy()) return
                    if (fileReady && acceleration.decoder == "vulkan") {
                        getPropertyString("hwdec-current")?.takeIf { !it.startsWith("vulkan") && it.isNotBlank() }?.let {
                            val fallback = "Vulkan Video → $it: decoder negotiation"
                            if (accelerationFallback != fallback && vulkanRequested) VideoAccelerationRuntime.notifyUnavailable()
                            accelerationFallback = fallback
                        }
                    }
                    return
                }
                MPV_EVENT_END_FILE -> {
                    // Swallow only the END_FILE of our own "stop"; later failures must surface.
                    if (dvSurfaceRestart) { dvSurfaceRestart = false; return@repeat }
                    // Stop/loadfile/redirect are lifecycle events, not a completed episode.
                    // Older optional JNI libraries do not expose the reason: only accept their
                    // explicit eof-reached flag or a real playback error.
                    val reason = runCatching { MpvNativeBridge.nativeEndReason(handle) }.getOrDefault(-1)
                    val nativeError = runCatching { MpvNativeBridge.nativePlaybackError(handle) }.getOrDefault(0)
                    val eof = observedFlag("eof-reached") ?: (getPropertyString("eof-reached") == "yes")
                    if (!mpvTerminalEnd(reason, nativeError, eof)) return@repeat
                    // Before the track cache goes: did the file open with a video track?
                    val loadedWithVideo = fileReady && runCatching { videoTracks().isNotEmpty() }.getOrDefault(false)
                    invalidateTracks()
                    sdrToHdr.reset()
                    if (sdrToHdr.rollbackFailed) {
                        stopUnsafeConversionOutput()
                        return
                    }
                    val error = nativeError
                    if (error < 0) com.ambient.tv.diagnostics.DiagnosticLog.error("playback", "MPV end-file error $error ${com.ambient.tv.diagnostics.diagnosticUrl(url)}")
                    // MPV_ERROR_AO_INIT_FAILED. Reject only the passthrough codec and re-plan
                    // (multichannel PCM or local AC3). Force stereo if that open also returns -14.
                    if (error == -14 && !audioSession.forceStereoPcm) {
                        recoverAudioOutputFailure("AO init $error", lastPlaybackPositionMs)
                        return@repeat
                    }
                    // Only a video output/decoder failure is a zero-copy failure; network, demux
                    // and load errors would lock zero-copy for a problem it did not cause.
                    if (acceleration.zeroCopy && !zeroCopyRetried && mpvZeroCopyFailureError(error, loadedWithVideo)) {
                        failZeroCopy("playback_error_$error")
                        return@repeat
                    }
                    if (error < 0 && error != -14 && fallbackAcceleration("playback_error_$error")) return@repeat
                    val durationAtEnd = runCatching { durationMs() }.getOrDefault(0L)
                    val endedEarly = error >= 0 && mpvEndedEarly(lastPlaybackPositionMs, durationAtEnd)
                    ended = error >= 0 && !endedEarly
                    fileReady = false
                    svpFilterDecided = false
                    if (error < 0) errorMessage = if (error == -14) "MPV audio: $error" else "MPV: $error"
                    // Worded so the players treat it as a lost connection (server check, retry).
                    if (endedEarly) errorMessage = "MPV: unexpected end of stream at ${lastPlaybackPositionMs / 1000}s of ${durationAtEnd / 1000}s"
                }
                MPV_EVENT_FILE_LOADED -> {
                    zeroCopyFrameCheckSinceNs = 0L
                    zeroCopyFrameWatch.reset()
                    // A new file: read its Dolby Vision profile again (latchDolbyVisionProfile).
                    dolbyVisionProfile = null
                    dvSurfaceRestart = false
                    // A reload/reopen drops sub-add tracks; allow them to be added again.
                    externalSubtitleRequests.clear()
                    com.ambient.tv.ui.player.logPlaybackStartup("mpv", "file_loaded_event", startupOpenedAt)
                    startupOpenedAt = 0L
                    invalidateTracks()
                    sdrToHdr.reset()
                    if (sdrToHdr.rollbackFailed) {
                        stopUnsafeConversionOutput()
                        return
                    }
                    fileReady = true
                    if (shieldSoftwareDecode) {
                        setDeinterlace(deinterlaceModeKey != "off", deinterlaceFilterKey, deinterlaceModeKey == "auto")
                    }
                    vulkanOutputReported = false
                    ended = false
                    applyAudioOptions()
                    fallbackTrackSelection?.let { values ->
                        listOf("aid", "sid", "speed").zip(values).forEach { (name, value) -> if (value != null) setProperty(name, value) }
                        fallbackTrackSelection = null
                    }
                    logHdrDiagnostics("file-loaded")
                    setProperty("pause", if (nativePlayWanted || dvPrimeTarget != null) "no" else "yes")
                }
                MPV_EVENT_SHUTDOWN -> { errorMessage = context.getString(com.ambient.tv.R.string.mpv_shutdown); return }
            }
        }
        workerHandler.post { if (!released) pollEvents() }
    }

    /** Resolve a fresh timeshift live edge on every reopen, including surface recovery. */
    private fun playbackUrlForOpen(): String =
        com.ambient.tv.livetv.LiveTimeshift.playbackUrl(url, streamOpened).also { streamOpened = true }

    /** Keep checking incomplete metadata; MediaCodec's absent interlace flag is not a negative. */
    private fun probeShieldBroadcast(): Boolean {
        if (shieldSoftwareDecode || !fileReady || !attached ||
            !mpvShieldSoftwareEligible(currentDeviceIsShield, acceleration, broadcastSource)) return false
        val height = (getPropertyDouble("current-tracks/video/demux-h", 0.0).takeIf { it > 0.0 }
            ?: getPropertyDouble("video-params/h", 0.0)).toInt().takeIf { it > 0 }
        val signals = MpvInterlaceSignals(videoCodec(), null, false, containerFps(), height)
        if (!mpvShieldSoftwareRequired(signals)) return false
        switchShieldBroadcastToSoftware()
        return true
    }

    private fun switchShieldBroadcastToSoftware() {
        // Recordings reopen where they were; live always reopens at its live edge.
        val resumeAt = if (liveStream) 0L else (positionMs().takeIf { it > 0L } ?: lastPlaybackPositionMs)
        shieldSoftwareDecode = true
        acceleration = acceleration.copy(decoder = "software")
        accelerationFallback = shieldSoftwareStatus
        Timber.i("%s SHIELD broadcast: reopen with hwdec=no, vo=%s, codec=%s fps=%s; CPU frames for GPU filters",
            HDR_AUDIT_TAG, configuredVo, videoCodec(), containerFps())
        onOutputTransition()
        deinterlaceUpdateJob?.cancel()
        imageUpdateJob?.cancel()
        fallbackTrackSelection = listOf("aid", "sid", "speed").map(::getPropertyString)
        optionalCommand("vf", "clr", "")
        activeCpuImageFilters.clear()
        explicitDeinterlaceFilter = null
        deinterlaceRequested = false
        svpCopyBackSession = false
        loadedShaderKeys = emptyList()
        lastImageTransfer = null
        lastImageUpscaling = true
        lastLumaShaderEligible = true
        lastSvpSignature = null
        trySetProperty("glsl-shaders", "")
        trySetProperty("deinterlace", "no")
        setProperty("vo", "null")
        setProperty("hwdec", acceleration.hwdec)
        setProperty("vo", configuredVo)
        fileReady = false
        svpFilterDecided = false
        ended = false
        reopenStream(resumeAt)
        setProperty("pause", if (nativePlayWanted) "no" else "yes")
        // FILE_LOADED restores deinterlacing; the normal image-source refresh restores shaders.
    }

    /** Read by the display-switch hold: a reopen moves the position without a user seek. */
    @Volatile var reopenGeneration = 0
        private set

    private fun reopenStream(positionMs: Long) {
        reopenGeneration++
        // Same watchdog allowance as the first open while loadfile and the first frames run.
        ownerStep?.reopening("reopen:loadfile")
        startupOpenedAt = System.nanoTime()
        if (liveStream) command("loadfile", playbackUrlForOpen(), "replace")
        else command("loadfile", playbackUrlForOpen(), "replace", "-1", "start=${com.ambient.tv.ui.player.rendererReopenPositionMs(false, positionMs) / 1000.0}")
    }

    /**
     * Zero-copy watchdog on vo=gpu: mpv ending on mediacodec-copy means the AImageReader interop
     * is unavailable (remember it, nothing to reopen); an engaged zero-copy decoder whose VO has
     * no output parameters after 3 s of real playback (not buffering, clock advancing) is treated as a
     * black plane, reopened on copy-back and remembered; see [ZeroCopyFrameWatch].
     */
    private fun checkZeroCopy(): Boolean {
        // Copy-back chosen at runtime for a filter/shader is not a zero-copy failure.
        if (zeroCopyFrameCheckSinceNs == ZERO_COPY_CONFIRMED || getPropertyString("hwdec") != MPV_ZERO_COPY_HWDEC) return false
        val current = getPropertyString("hwdec-current")
        if (mpvZeroCopyInteropFailed(acceleration, current)) {
            Timber.w("%s MPV zero-copy unavailable (hwdec-current=%s); copy-back from now on", HDR_AUDIT_TAG, current)
            videoImagePreferences(context).mpvZeroCopyFailed = true
            acceleration = acceleration.copy(zeroCopy = false)
            return false
        }
        if (current?.trim() != "mediacodec" || !desiredPlayWhenReady || videoTracks().isEmpty()) {
            zeroCopyFrameCheckSinceNs = 0L
            zeroCopyFrameWatch.reset()
            return false
        }
        val now = System.nanoTime()
        if (zeroCopyFrameCheckSinceNs == 0L) zeroCopyFrameCheckSinceNs = now
        if (getPropertyString("video-out-params/w").isNullOrBlank()) {
            val buffering = getPropertyString("paused-for-cache") == "yes"
            val position = (getPropertyDouble("time-pos", -1.0) * 1000.0).toLong()
            val verdict = zeroCopyFrameWatch.poll(now, buffering, position)
            if (verdict is ZeroCopyNoFrames.Fail && !zeroCopyRetried) {
                failZeroCopy(if (verdict.remember) "no_video_frames" else "no_video_frames_unconfirmed", remember = verdict.remember)
                return true
            }
        } else {
            // Frames reach the VO: the check is done for this file.
            zeroCopyFrameCheckSinceNs = ZERO_COPY_CONFIRMED
        }
        return false
    }

    /**
     * Reopen this file on mediacodec-copy (once per engine). [remember] records a real failure
     * for this app version; a planned switch (HDR→SDR found at runtime) does not lock zero-copy.
     */
    private fun failZeroCopy(reason: String, remember: Boolean = true) {
        Timber.w("%s MPV zero-copy %s (%s); reopening with mediacodec-copy", HDR_AUDIT_TAG,
            if (remember) "failed" else "not used", reason)
        if (remember) videoImagePreferences(context).mpvZeroCopyFailed = true
        zeroCopyRetried = true
        zeroCopyFrameCheckSinceNs = 0L
        zeroCopyFrameWatch.reset()
        acceleration = acceleration.copy(zeroCopy = false)
        accelerationFallback = "MediaCodec zero-copy → copy-back: $reason"
        val position = positionMs().takeIf { it > 0L } ?: lastPlaybackPositionMs
        fallbackTrackSelection = listOf("aid", "sid", "speed").map(::getPropertyString)
        setProperty("vo", "null")
        setProperty("hwdec", acceleration.hwdec)
        setProperty("vo", configuredVo)
        fileReady = false
        svpFilterDecided = false
        ended = false
        if (attached) reopenStream(position)
    }

    private fun fallbackAcceleration(reason: String, reload: Boolean = true): Boolean {
        // OpenGL cannot signal HDR on Android: leave the passthrough player to the direct reopen.
        if (mpvHdrPassthroughBlocksGlFallback(hdrGpuPassthrough, acceleration.gpu, released)) {
            Timber.w("MPV HDR passthrough: %s fallback (%s), reopening on the direct surface",
                if (acceleration.vulkan) "Vulkan" else "EGL PQ", reason)
            failHdrPassthrough(if (acceleration.vulkan) MpvHdrGpuReason.VULKAN_FALLBACK else MpvHdrGpuReason.OPENGL_FALLBACK)
            return true
        }
        val directDecoderFallback = acceleration.output == "direct" && reason in setOf("mediacodec_unavailable", "hdr_to_sdr")
        if ((!acceleration.vulkan && !directDecoderFallback) || accelerationRetried || released) return false
        sdrToHdr.reset()
        if (sdrToHdr.rollbackFailed) {
            stopUnsafeConversionOutput()
            return false
        }
        VideoAccelerationRuntime.reportFallback("mpv", reason)
        if (vulkanRequested) VideoAccelerationRuntime.notifyUnavailable()
        accelerationRetried = true
        val position = positionMs().takeIf { it > 0L } ?: lastPlaybackPositionMs
        fallbackTrackSelection = listOf("aid", "sid", "speed").map(::getPropertyString)
        val previousOutput = if (acceleration.vulkan) "Vulkan" else "MediaCodec Surface"
        acceleration = fallbackMpvAcceleration(acceleration, reason)
        accelerationFallback = "$previousOutput → OpenGL / ${acceleration.decoder}: $reason"
        Timber.w("MPV output fallback: %s", reason)
        val savedDeinterlaceFilter = explicitDeinterlaceFilter
        optionalCommand("vf", "clr", "")
        activeCpuImageFilters.clear()
        explicitDeinterlaceFilter = null
        loadedShaderKeys = emptyList()
        svpCopyBackSession = false
        lastImageTransfer = null
        lastImageUpscaling = true
        lastLumaShaderEligible = true
        lastSvpSignature = null
        trySetProperty("glsl-shaders", "")
        setProperty("vo", "null")
        setProperty("gpu-api", "opengl")
        setProperty("gpu-context", "android")
        setProperty("hwdec", acceleration.hwdec)
        setProperty("vo", configuredVo)
        if (deinterlaceRequested) {
            if (!trySetGpuDecoder(true) || !rebuildMpvVideoFilterChain(emptyMap(), savedDeinterlaceFilter)) {
                deinterlaceFailed = true
            }
        }
        if (reload && attached) {
            fileReady = false
            svpFilterDecided = false
            ended = false
            reopenStream(position)
            setProperty("pause", if (nativePlayWanted || dvPrimeTarget != null) "no" else "yes")
        }
        return true
    }

    override fun updateSdrToHdr(request: SdrToHdrRequest?): SdrToHdrStatus {
        if (released) return SdrToHdrStatus.OFF
        // Do not change the selected renderer, decoder or acceleration mode for this option.
        // Check decoded AND filtered frames: HDR tone-mapped by another filter is not SDR input.
        val decodedGamma = getPropertyString("video-dec-params/gamma")
        val sourceGamma = getPropertyString("video-params/gamma")
        val filteredGamma = getPropertyString("video-out-params/gamma")
        val supported = request != null && !conversionOutputUnsafe && attached && fileReady && !ended && errorMessage == null &&
            !performanceProfile(context).gateSdrToHdr && acceleration.vulkan && hdr10SwapchainSupported != false &&
            (isPlaying() || sdrToHdr.active) &&
            getPropertyString("current-gpu-context") == "androidvk" &&
            supportsMpvSdrToHdrInput(getPropertyString("current-vo"), getPropertyString("gpu-api"),
                decodedGamma, sourceGamma, filteredGamma, getPropertyString("video-params/primaries")) &&
            listOf("icc-profile", "image-lut", "target-lut").all { getPropertyString(it).isNullOrBlank() }
        val now = SystemClock.elapsedRealtime()
        val status = sdrToHdr.update(request, supported, now)
        if (sdrToHdr.rollbackFailed) {
            stopUnsafeConversionOutput()
            return SdrToHdrStatus.FAILED
        }
        if (status != SdrToHdrStatus.ACTIVE) sdrToHdrDisplayWatch.reset()
        else {
            val display = com.ambient.tv.ui.display.defaultDisplay(context)
            val ratio = com.ambient.tv.ui.display.displayHdrSdrRatio(display)
            com.ambient.tv.ui.display.DisplayHdrRatioEvidence.note(display, ratio, hdrOutput = true)
            val displayHdr = com.ambient.tv.ui.display.displayHdrModeFromRatio(ratio,
                com.ambient.tv.ui.display.DisplayHdrRatioEvidence.reliable(display))
            if (sdrToHdrDisplayWatch.update(displayHdr,
                    isPlaying() && !com.ambient.tv.ui.player.PlaybackOutputTransition.active(), now)) {
                Timber.w("%s SDR to HDR disabled: display stayed SDR (ratio=%s); back to SDR output", HDR_AUDIT_TAG, ratio)
                sdrToHdr.fail()
                if (sdrToHdr.rollbackFailed) stopUnsafeConversionOutput()
                conversionSampleStarted = 0L
                return SdrToHdrStatus.FAILED
            }
        }
        if (status != SdrToHdrStatus.ACTIVE || !isPlaying() ||
            getPropertyString("seeking") == "yes" || getPropertyString("paused-for-cache") == "yes" ||
            com.ambient.tv.ui.player.PlaybackOutputTransition.active()) {
            conversionSampleStarted = 0L
        } else {
            val dropped = (frameDropCount() ?: 0L) + (decoderFrameDropCount() ?: 0L)
            if (conversionSampleStarted == 0L) {
                // Leave mode-switch warmup out of the steady-playback sample.
                conversionSampleStarted = now + 3_000L
                conversionSampleDrops = dropped
            } else if (now < conversionSampleStarted) {
                conversionSampleDrops = dropped
            } else if (now - conversionSampleStarted >= (mpvRenderSampleSeconds(false, videoImagePreferences(context).frameDropTestSeconds) * 1_000).toLong()) {
                val seconds = (now - conversionSampleStarted) / 1000.0
                if (mpvRenderOverloaded(dropped - conversionSampleDrops, containerFps() ?: 0.0, seconds)) {
                    Timber.w("SDR to HDR disabled for this player: sustained frame drops")
                    sdrToHdr.fail()
                    if (sdrToHdr.rollbackFailed) stopUnsafeConversionOutput()
                    conversionSampleStarted = 0L
                    return SdrToHdrStatus.FAILED
                }
                conversionSampleStarted = now
                conversionSampleDrops = dropped
            }
        }
        return status
    }

    private fun stopUnsafeConversionOutput() {
        if (conversionOutputUnsafe || released) return
        conversionOutputUnsafe = true
        Timber.e("SDR to HDR rollback failed; stopping unverified video output")
        pause()
        detachSurface()
        errorMessage = context.getString(com.ambient.tv.R.string.sdr_to_hdr_restore_failed)
    }

    override fun uiHdrStandard(): com.ambient.tv.ui.player.HdrStandard? {
        if (!released && attached && fileReady && !ended && errorMessage == null && sdrToHdr.active)
            return com.ambient.tv.ui.player.HdrStandard.HDR10
        // GPU paths can tone-map to SDR. Trust an attached, ready native HDR output, including
        // Vulkan/gpu-next, the same way as mediacodec_embed. Missing gamma leaves the UI in SDR.
        if (released || !fileReady || !attached || ended || errorMessage != null) return null
        val vo = getPropertyString("current-vo")
        val gamma = mpvHdrOutputGamma(vo, getPropertyString("video-out-params/gamma"), getPropertyString("video-target-params/gamma"))
        return when (mpvNativeHdrOutput(vo, gamma)) {
            "hdr10" -> com.ambient.tv.ui.player.HdrStandard.HDR10
            "hlg" -> com.ambient.tv.ui.player.HdrStandard.HLG
            else -> null
        }
    }

    override fun sdrToHdrPath(): com.ambient.tv.ui.display.SdrToHdrPath {
        val vo = getPropertyString("current-vo")?.trim()?.lowercase()
        val api = getPropertyString("gpu-api")?.trim()?.lowercase()
        val vulkanGpuNext = acceleration.vulkan && (vo.isNullOrBlank() || vo == "gpu-next") &&
            (api.isNullOrBlank() || api == "vulkan")
        val supportedInput = fileReady && supportsMpvSdrToHdrInput(vo, api,
            getPropertyString("video-dec-params/gamma"), getPropertyString("video-params/gamma"),
            getPropertyString("video-out-params/gamma"), getPropertyString("video-params/primaries"))
        val reason = when {
            released || !attached || !fileReady || ended -> SdrToHdrUnavailableReason.NOT_READY
            errorMessage != null || sdrToHdr.conversionFailed || conversionOutputUnsafe -> SdrToHdrUnavailableReason.OUTPUT_FAILED
            !vulkanGpuNext || getPropertyString("current-gpu-context") != "androidvk" -> SdrToHdrUnavailableReason.PLAYER_OUTPUT
            performanceProfile(context).gateSdrToHdr -> SdrToHdrUnavailableReason.REDUCED_EFFECTS
            hdr10SwapchainSupported == false -> SdrToHdrUnavailableReason.SWAPCHAIN_NO_HDR10
            !supportedInput -> SdrToHdrUnavailableReason.SOURCE_NOT_SDR
            listOf("icc-profile", "image-lut", "target-lut").any { !getPropertyString(it).isNullOrBlank() } -> SdrToHdrUnavailableReason.FILTER_CONFLICT
            else -> null
        }
        return com.ambient.tv.ui.display.SdrToHdrPath(
            kind = if (vulkanGpuNext) com.ambient.tv.ui.display.SdrToHdrPlayerKind.MPV_VULKAN
            else com.ambient.tv.ui.display.SdrToHdrPlayerKind.MPV_UNSUPPORTED,
            blocked = reason != null, reason = reason,
        )
    }

    private fun logHdrDiagnostics(reason: String) {
        val names = listOf(
            "current-vo", "current-ao", "audio-codec-name", "audio-params/channels", "audio-params/channel-count",
            "audio-out-params/format", "audio-out-params/channels", "audio-out-params/channel-count", "audio-spdif",
            "video-codec", "video-codec-name", "video-format", "hwdec-current",
            "width", "height", "video-params/pixelformat", "video-params/aspect",
            "video-params/primaries", "video-params/gamma", "video-params/colormatrix",
            "video-params/colorlevels", "video-params/sig-peak", "video-params/max-cll",
            "video-params/max-fall", "video-out-params/pixelformat",
            "video-out-params/primaries", "video-out-params/gamma",
            "video-out-params/colormatrix", "video-out-params/colorlevels"
        )
        val values = names.joinToString(" | ") { name ->
            "$name=${getPropertyString(name) ?: getPropertyDouble(name, Double.NaN).takeIf { !it.isNaN() }?.toString() ?: "?"}"
        }
        Timber.i("%s MPV %s | %s", HDR_AUDIT_TAG, reason, values)
    }

    private fun command(vararg args: String) {
        check(android.os.Looper.myLooper() === workerHandler.looper)
        if (released) return
        val rc = MpvNativeBridge.nativeCommand(handle, args)
        if (rc < 0) errorMessage = context.getString(com.ambient.tv.R.string.mpv_command_failed, rc)
    }

    private fun optionalCommand(vararg args: String): Boolean {
        check(android.os.Looper.myLooper() === workerHandler.looper)
        if (released) return false
        val rc = MpvNativeBridge.nativeCommand(handle, args)
        if (rc < 0) Timber.w("Optional MPV command failed (%d): %s", rc, args.joinToString(" "))
        return rc >= 0
    }

    private fun setProperty(name: String, value: String) {
        trySetProperty(name, value)
    }

    private fun trySetProperty(name: String, value: String): Boolean {
        check(android.os.Looper.myLooper() === workerHandler.looper)
        if (released) return false
        val result = MpvNativeBridge.nativeSetPropertyString(handle, name, value)
        if (result < 0) Timber.w("AV-AUDIT MPV property rejected: %s (%d)", name, result)
        return result >= 0
    }

    private fun setPropertyDouble(name: String, value: Double) {
        if (!released) MpvNativeBridge.nativeSetPropertyDouble(handle, name, value)
    }

    private fun getPropertyString(name: String): String? {
        check(android.os.Looper.myLooper() === workerHandler.looper)
        return if (!released) MpvNativeBridge.nativeGetPropertyString(handle, name) else null
    }
    private fun getPropertyDouble(name: String, fallback: Double): Double {
        check(android.os.Looper.myLooper() === workerHandler.looper)
        return if (!released) MpvNativeBridge.nativeGetPropertyDouble(handle, name, fallback) else fallback
    }

    override fun release() {
        if (released) return
        if (mpvStopsStreamBeforeDestroy(liveStream, broadcastSource)) {
            // Drop the server connection first, without waiting for mpv's reply: the old live
            // subscription closes while the event pump and mpv_terminate_destroy finish, and the
            // next player does not wait on a network read.
            runCatching { MpvNativeBridge.nativeCommand(handle, arrayOf("async", "stop")) }
                .onFailure { Timber.w(it, "MPV async stop before destroy failed") }
        }
        finishDvPrime()
        sdrToHdr.reset()
        imageScope.cancel()
        detachVideoOutput()
        released = true
        if (stopEventPump()) MpvNativeBridge.nativeDestroy(handle)
        // A leaked mpv context costs memory until the process ends; a closed descriptor still in
        // use by the waiter can corrupt an unrelated stream.
        else error("MPV event waiter did not stop; audio output is still owned")
    }
}

/** Live TV and Tvheadend recordings stop their stream before the handle is destroyed. */
internal fun mpvStopsStreamBeforeDestroy(liveStream: Boolean, broadcastSource: Boolean): Boolean =
    liveStream || broadcastSource

/** Audio stays off with an audio track selected: mpv dropped it after an output failure. */
internal fun mpvAudioOutputDropped(selectedAid: String?, currentAudioId: String?, hasAudioTracks: Boolean, hasVideoTracks: Boolean): Boolean =
    hasVideoTracks && hasAudioTracks && selectedAid != null && selectedAid != "no" && selectedAid != "false" &&
        currentAudioId.isNullOrBlank()

/** Sustained time without audio output before [mpvAudioOutputDropped] counts (track switches are shorter). */
internal const val MPV_AUDIO_DROP_CONFIRM_NS = 1_500_000_000L

/** Codecs MPV may send as bitstream (passthrough, DTS core, local AC3 plan). */
internal fun mpvBitstreamCodec(codec: String?): Boolean =
    codec?.lowercase() in setOf("ac3", "eac3", "truehd", "mlp", "dts", "dca", "dtshd", "dts-hd")
