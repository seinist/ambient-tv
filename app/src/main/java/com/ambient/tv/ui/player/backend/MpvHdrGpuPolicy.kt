package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.player.HdrStandard

/**
 * Why HDR plays on the direct MediaCodec surface although "HDR über GPU verarbeiten" is on.
 * Null reason on a [MpvHdrGpuDecision] means the option does not concern this playback.
 */
enum class MpvHdrGpuReason {
    /** Output "Direkt" was chosen explicitly for this playback (player, title or resolution profile). */
    OUTPUT_DIRECT,
    /** Dolby Vision keeps its native path; MediaCodec copy-back has no RPU to reshape. */
    DOLBY_VISION,
    /** HLG (and HDR10+ on an HDR10+ display) keep their native path. */
    NOT_HDR10,
    /** Probe not finished or the device fails the MPV Vulkan gate (Vulkan 1.2, hostQueryReset). */
    VULKAN_UNAVAILABLE,
    /** The probe reported a Vulkan surface without the HDR10 (ST2084) colour space. */
    SWAPCHAIN_NO_HDR10,
    /** The display reports no HDR10; the regular tone-mapping path applies. */
    DISPLAY_NO_HDR10,
    /** Sparsam: no shader would run, so the GPU path brings nothing. */
    REDUCED_EFFECTS,
    /** No HDR-safe shader or deband is selected: the GPU path would only add copy-back cost. */
    NO_EFFECTS,
    /** mpv/Android did not confirm a PQ BT.2020 target in time. */
    NOT_CONFIRMED,
    /** The Vulkan output failed for this title (surface or VO init); kept for the media only. */
    VULKAN_FALLBACK,
    /** Sustained dropped frames on the GPU path with nothing left to reduce. */
    OVERLOAD,
}

data class MpvHdrGpuDecision(val gpu: Boolean, val reason: MpvHdrGpuReason?) {
    companion object {
        val NOT_APPLICABLE = MpvHdrGpuDecision(false, null)
        val GPU = MpvHdrGpuDecision(true, null)
        fun direct(reason: MpvHdrGpuReason) = MpvHdrGpuDecision(false, reason)
    }
}

/**
 * HDR10 on an HDR10 display through vo=gpu-next + Vulkan with target-colorspace-hint
 * (passthrough), so HDR-safe shaders can run, instead of the forced mediacodec_embed.
 * Everything else keeps today's routing; [MpvHdrGpuDecision.reason] tells the UI why.
 * [failure] is a runtime verdict for this media (see [MpvHdrGpuSession]).
 *
 * The global output default "Direkt" (the factory setting) does not block the option; only
 * [explicitDirect] (chosen in the player, title or resolution profile) does.
 * [effectsActive]: an HDR-safe shader or deband would actually run ([mpvHdrGpuEffectsActive]).
 * [tvDevice]: the option is a TV (leanback) feature; phones keep the direct surface.
 */
internal fun mpvHdrGpuDecision(
    enabled: Boolean,
    output: String,
    source: HdrStandard?,
    display: Set<HdrStandard>,
    capabilities: VideoAccelerationCapabilities,
    reducedEffects: Boolean,
    failure: MpvHdrGpuReason? = null,
    explicitDirect: Boolean = false,
    effectsActive: Boolean = true,
    tvDevice: Boolean = true,
): MpvHdrGpuDecision {
    if (!enabled || !tvDevice || source == null) return MpvHdrGpuDecision.NOT_APPLICABLE
    return when {
        source == HdrStandard.DOLBY_VISION -> MpvHdrGpuDecision.direct(MpvHdrGpuReason.DOLBY_VISION)
        // HDR10+ on a display without HDR10+ reaches the panel as HDR10 anyway.
        source == HdrStandard.HLG || (source == HdrStandard.HDR10_PLUS && HdrStandard.HDR10_PLUS in display) ->
            MpvHdrGpuDecision.direct(MpvHdrGpuReason.NOT_HDR10)
        HdrStandard.HDR10 !in display -> MpvHdrGpuDecision.direct(MpvHdrGpuReason.DISPLAY_NO_HDR10)
        !capabilities.mpvVulkanOutput -> MpvHdrGpuDecision.direct(MpvHdrGpuReason.VULKAN_UNAVAILABLE)
        capabilities.device.hdr10SurfaceSupported == false -> MpvHdrGpuDecision.direct(MpvHdrGpuReason.SWAPCHAIN_NO_HDR10)
        // A runtime failure also routes this playback to "Direkt"; keep naming the cause.
        failure != null -> MpvHdrGpuDecision.direct(failure)
        output == "direct" && explicitDirect -> MpvHdrGpuDecision.direct(MpvHdrGpuReason.OUTPUT_DIRECT)
        reducedEffects -> MpvHdrGpuDecision.direct(MpvHdrGpuReason.REDUCED_EFFECTS)
        !effectsActive -> MpvHdrGpuDecision.direct(MpvHdrGpuReason.NO_EFFECTS)
        else -> MpvHdrGpuDecision.GPU
    }
}

/**
 * "Direkt" counts as a choice for this playback only when made in the player ([sessionOutput]) or
 * when a saved title/resolution profile deviates from the global default to "Direkt". Resolution
 * profiles store their output in full, so a "direct" equal to [globalOutput] is no choice.
 */
internal fun mpvHdrGpuExplicitDirect(sessionOutput: String?, titleOutput: String?, resolutionOutput: String?,
    globalOutput: String = "direct"): Boolean =
    sessionOutput == "direct" || (globalOutput != "direct" && (titleOutput == "direct" || resolutionOutput == "direct"))

/**
 * True when the GPU route would run anything on an HDR10 source with PQ output: an HDR-safe
 * shader ([mpvImageShaderKeys] with pqOutput) or deband. [options] are the effective options
 * (Sparsam/SVP already applied); an unknown [fittedScale] counts as upscaling.
 */
internal fun mpvHdrGpuEffectsActive(options: com.ambient.tv.data.local.MpvImageOptions, fittedScale: Double? = null): Boolean =
    options.normalized().deband != "off" ||
        mpvImageShaderKeys(options, sdr = false, upscaling = true, fittedScale = fittedScale, svpActive = false,
            hdr = true, gpuNext = true, pqOutput = true).isNotEmpty()

/** Output handed to the controller: Vulkan for the passthrough, "Direkt" after a recorded failure. */
internal fun mpvHdrGpuOutputMode(decision: MpvHdrGpuDecision, configured: String, failure: MpvHdrGpuReason?): String = when {
    decision.gpu -> "vulkan"
    failure != null && decision.reason == failure -> "direct"
    else -> configured
}

/**
 * Engine side of a failed passthrough: the native player is paused while the view reopens the
 * title on the direct surface, but the user's play intent is kept, so the reopen plays on.
 */
internal data class MpvHdrFailureHold(val pauseNative: Boolean, val playIntent: Boolean)

/**
 * [attached]: a surface is attached. Without one (background audio, vo=null) nothing reopens
 * until the view returns, so audio keeps playing instead of waiting paused.
 */
internal fun mpvHdrFailureHold(playIntent: Boolean, attached: Boolean = true): MpvHdrFailureHold =
    MpvHdrFailureHold(pauseNative = attached, playIntent = playIntent)

/** mpv may play: no passthrough failure, or no surface the failure would show on. */
internal fun mpvHdrNativePlayAllowed(playIntent: Boolean, failure: MpvHdrGpuReason?, attached: Boolean): Boolean =
    playIntent && (failure == null || !attached)

/** OpenGL cannot signal HDR on Android: a passthrough player never takes the OpenGL fallback. */
internal fun mpvHdrPassthroughBlocksGlFallback(hdrGpuPassthrough: Boolean, vulkan: Boolean, released: Boolean): Boolean =
    hdrGpuPassthrough && vulkan && !released

/**
 * What a runtime source reading means for "HDR über GPU": Dolby Vision (any profile reported by
 * mpv's track list) wins over its PQ base layer, otherwise the transfer decides.
 */
internal fun mpvRuntimeHdrStandard(transfer: String?, dolbyVisionProfile: Int?): HdrStandard? =
    if (dolbyVisionProfile != null && dolbyVisionProfile > 0) HdrStandard.DOLBY_VISION else hdrStandardFromMpvTransfer(transfer)

/**
 * libplacebo passthrough: the swapchain is tagged from the source (PQ/BT.2020 + HDR10 metadata)
 * and strict mode renders into what was actually negotiated. target-trc/prim stay auto.
 */
internal val MPV_HDR_PASSTHROUGH_OPTIONS = linkedMapOf(
    "target-colorspace-hint" to "yes",
    "target-colorspace-hint-mode" to "source",
    "target-colorspace-hint-strict" to "yes",
    // Nothing is tone-mapped on this route: skip the per-frame compute pass and keep the
    // static HDR10 metadata instead of a detected (dynamic) peak.
    "hdr-compute-peak" to "no",
)

/** Facts read from mpv and Android while the passthrough route plays. */
internal data class MpvHdrPassthroughFacts(
    val fileReady: Boolean,
    val playing: Boolean,
    val sourceGamma: String?,
    val targetGamma: String?,
    val targetPrimaries: String?,
    val targetPixelFormat: String?,
    val dataSpace: Int,
    val swapchainHdr10: Boolean?,
    /** `video-target-params/max-luma` (cd/m²) negotiated for the swapchain, null when unknown. */
    val targetMaxLuma: Double? = null,
    /** Source MaxCLL (`video-params/max-cll`) passed on as HDR10 metadata, null when unknown. */
    val targetMaxCll: Double? = null,
)

/**
 * Static HDR10 metadata that reaches the swapchain. Null means unknown (mpv did not publish
 * it); a published value must look like HDR: a mastering peak above SDR white and below the
 * PQ ceiling, and a MaxCLL that is not negative.
 */
internal fun mpvHdrMetadataPlausible(maxLuma: Double?, maxCll: Double?): Boolean? {
    if (maxLuma == null && maxCll == null) return null
    val lumaOk = maxLuma == null || (maxLuma.isFinite() && maxLuma > 203.0 && maxLuma <= 10_000.0)
    val cllOk = maxCll == null || (maxCll.isFinite() && maxCll >= 0.0 && maxCll <= 10_000.0)
    return lumaOk && cllOk
}

/**
 * Confirms the passthrough once per player: mpv must report a PQ/BT.2020 10-bit target and
 * Android a BT.2020/ST2084 producer surface within [timeoutMs] of steady playback. A failure is
 * final for this player; the UI then reopens the title on the direct surface.
 */
internal class MpvHdrPassthroughMonitor(private val timeoutMs: Long = 3_000L) {
    var confirmed = false
        private set
    var failure: MpvHdrGpuReason? = null
        private set
    /** Metadata verdict at confirmation ([mpvHdrMetadataPlausible]); logged, not a failure. */
    var metadataPlausible: Boolean? = null
        private set
    private var since = -1L

    fun update(facts: MpvHdrPassthroughFacts, nowMs: Long): MpvHdrGpuReason? {
        if (failure != null) return failure
        if (facts.swapchainHdr10 == false) return fail(MpvHdrGpuReason.SWAPCHAIN_NO_HDR10)
        val pqSource = facts.sourceGamma?.trim()?.lowercase() in setOf("pq", "smpte2084")
        if (!facts.fileReady || !pqSource) {
            // A source that turns out not to be PQ simply renders SDR; nothing to confirm.
            since = -1L
            return null
        }
        val ok = facts.targetGamma?.trim()?.lowercase() in setOf("pq", "smpte2084") &&
            facts.targetPrimaries?.trim()?.lowercase() == "bt.2020" &&
            facts.targetPixelFormat?.trim()?.lowercase() in MPV_HDR_TARGET_PIXEL_FORMATS &&
            mpvHdrSurfaceConfirmed(facts.dataSpace)
        if (ok) {
            if (!confirmed) metadataPlausible = mpvHdrMetadataPlausible(facts.targetMaxLuma, facts.targetMaxCll)
            confirmed = true
            since = -1L
            return null
        }
        // Also after a confirmation: PQ lost for a whole window of steady playback means the
        // swapchain fell back to SDR. Short gaps (reload, renegotiation) are tolerated.
        if (!facts.playing) {
            since = -1L
            return null
        }
        if (since < 0L || nowMs < since) since = nowMs
        return if (nowMs - since >= timeoutMs) fail(MpvHdrGpuReason.NOT_CONFIRMED) else null
    }

    fun fail(reason: MpvHdrGpuReason): MpvHdrGpuReason {
        if (failure == null) failure = reason
        return failure!!
    }
}

/** Same libplacebo formats the SDR→HDR controller accepts as a 10-bit target. */
internal val MPV_HDR_TARGET_PIXEL_FORMATS = setOf("rgb10a2", "bgr10a2", "rgba16", "rgba16hf", "rgba16f")

/**
 * HDR media detected at runtime and passthrough failures for this app process.
 *
 * Keys are media identities ([mpvHdrGpuMediaKey]): source + media id (+ version) where the
 * playback has one, otherwise the URL without query (session ids, tickets, tokens). Plex remux
 * URLs share one path for every title, so the URL alone is only the fallback.
 *
 * Failures are kept at the level they describe:
 * - display ([SWAPCHAIN_NO_HDR10][MpvHdrGpuReason.SWAPCHAIN_NO_HDR10], and
 *   [NOT_CONFIRMED][MpvHdrGpuReason.NOT_CONFIRMED] after it happened twice in a row on that
 *   display; a confirmed passthrough in between resets the count, [recordConfirmed]),
 * - media + resolution (everything else, and a first NOT_CONFIRMED, which may be transient).
 * Both maps are bounded and every entry carries the display it was seen on, so another display
 * starts clean. [clear] runs when the option is toggled and on "Erneut prüfen".
 *
 * Live sources only keep a detection for [LIVE_DETECTION_TTL_MS] and reopen at most once per
 * [LIVE_REOPEN_WINDOW_MS]; an SDR reading forgets it without a reopen ([forgetDetected]).
 * A Dolby Vision detection of a file is not replaced by the HDR10 reading of its base layer.
 */
object MpvHdrGpuSession {
    const val MAX_ENTRIES = 64
    const val LIVE_DETECTION_TTL_MS = 2 * 60 * 60 * 1000L
    const val LIVE_REOPEN_WINDOW_MS = 10 * 60 * 1000L

    private data class Detected(val standard: HdrStandard, val live: Boolean, val atMs: Long)

    private fun <V> bounded() = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > MAX_ENTRIES
    }

    private val lock = Any()
    private val detected = bounded<Detected>()
    private val liveReopens = bounded<Long>()
    private val mediaFailures = bounded<MpvHdrGpuReason>()
    private val displayFailures = bounded<MpvHdrGpuReason>()
    private val notConfirmed = bounded<Int>()
    /** Compose reads this to recompose when a verdict that changes the route was recorded. */
    private val version = androidx.compose.runtime.mutableIntStateOf(0)

    /** URL fallback of [mpvHdrGpuMediaKey]: without fragment and query. */
    fun mediaKey(url: String): String = url.substringBefore('#').substringBefore('?')
    private fun mediaFailureKey(media: String, resolution: String?, display: String) = display + "|" + media + "|" + resolution.orEmpty()
    private fun bump() { version.intValue++ }

    fun detectedHdr(media: String?, nowMs: Long = android.os.SystemClock.elapsedRealtime()): HdrStandard? {
        version.intValue
        media ?: return null
        return synchronized(lock) {
            detected[media]?.takeIf { !it.live || nowMs - it.atMs in 0..LIVE_DETECTION_TTL_MS }?.standard
        }
    }

    /** False when nothing changed or a live source already reopened within the window. */
    fun recordDetected(media: String, standard: HdrStandard, live: Boolean = false,
        nowMs: Long = android.os.SystemClock.elapsedRealtime()): Boolean = synchronized(lock) {
        val previous = detected[media]
        val stillValid = previous != null && (!previous.live || nowMs - previous.atMs in 0..LIVE_DETECTION_TTL_MS)
        if (stillValid && previous.standard == standard) return@synchronized false
        // The direct reopen of a Dolby Vision file reads its PQ base layer as HDR10.
        if (stillValid && !live && previous.standard == HdrStandard.DOLBY_VISION && standard == HdrStandard.HDR10) {
            return@synchronized false
        }
        if (live) {
            val last = liveReopens[media]
            if (last != null && nowMs - last in 0 until LIVE_REOPEN_WINDOW_MS) return@synchronized false
            liveReopens[media] = nowMs
        }
        detected[media] = Detected(standard, live, nowMs)
        bump()
        true
    }

    /**
     * A live source that now reads SDR: the next tune of this channel starts on the normal route.
     * No recompose; the running SDR playback keeps its route.
     */
    fun forgetDetected(media: String) { synchronized(lock) { detected.remove(media) } }

    fun failure(media: String?, resolution: String?, display: String): MpvHdrGpuReason? {
        version.intValue
        return synchronized(lock) {
            displayFailures[display] ?: media?.let { mediaFailures[mediaFailureKey(it, resolution, display)] }
        }
    }

    fun recordFailure(media: String, resolution: String?, display: String, reason: MpvHdrGpuReason) {
        synchronized(lock) {
            when (reason) {
                MpvHdrGpuReason.SWAPCHAIN_NO_HDR10 -> displayFailures[display] = reason
                MpvHdrGpuReason.NOT_CONFIRMED -> {
                    val count = (notConfirmed[display] ?: 0) + 1
                    notConfirmed[display] = count
                    if (count >= 2) displayFailures[display] = reason
                }
                else -> Unit
            }
            val key = mediaFailureKey(media, resolution, display)
            if (key !in mediaFailures) mediaFailures[key] = reason
        }
        bump()
    }

    /** The passthrough was confirmed on [display]: an earlier NOT_CONFIRMED there was transient. */
    fun recordConfirmed(display: String) { synchronized(lock) { notConfirmed.remove(display) } }

    fun clear() {
        synchronized(lock) {
            detected.clear(); liveReopens.clear(); mediaFailures.clear(); displayFailures.clear(); notConfirmed.clear()
        }
        bump()
    }

    internal fun sizes(): List<Int> = synchronized(lock) {
        listOf(detected.size, liveReopens.size, mediaFailures.size, displayFailures.size, notConfirmed.size)
    }
}

/**
 * Media identity for [MpvHdrGpuSession]: source (+ live server) + media id + version
 * (`mediaIndex` of the route) when the playback has an id; trailers and playbacks without
 * metadata fall back to the URL without query. Null when neither is known.
 */
internal fun mpvHdrGpuMediaKey(source: String?, mediaId: String?, liveSourceId: String?, route: String?,
    trailer: Boolean, url: String?): String? {
    val id = mediaId?.trim().orEmpty()
    if (!trailer && id.isNotEmpty()) {
        val version = route?.let { Regex("[?&]mediaIndex=(\\d+)").find(it)?.groupValues?.get(1) }.orEmpty()
        return listOf("media", source.orEmpty(), liveSourceId.orEmpty(), id, version).joinToString("|")
    }
    return url?.let(MpvHdrGpuSession::mediaKey)?.takeIf { it.isNotBlank() }?.let { "url|$it" }
}

/**
 * Source standard for the decision: a Dolby Vision reading (this player's track list or an
 * earlier detection) wins over the HDR10 the base layer reports; otherwise what the server
 * declared, then what mpv detected.
 */
internal fun mpvHdrGpuSourceStandard(declared: HdrStandard?, detected: HdrStandard?, dolbyVisionRuntime: Boolean): HdrStandard? = when {
    dolbyVisionRuntime || detected == HdrStandard.DOLBY_VISION -> HdrStandard.DOLBY_VISION
    else -> declared ?: detected
}

/** Display identity for [MpvHdrGpuSession]: a different TV/AVR or HDR capability set starts clean. */
internal fun mpvHdrDisplayKey(displayId: Int?, displayName: String?, standards: Set<HdrStandard>): String =
    listOf(displayId?.toString().orEmpty(), displayName.orEmpty(), standards.map { it.name }.sorted().joinToString("+")).joinToString(":")

/** Settings: the device-level part of [mpvHdrGpuDecision] (null while probing or when nothing blocks). */
internal fun mpvHdrGpuDeviceReason(capabilities: VideoAccelerationCapabilities, display: Set<HdrStandard>): MpvHdrGpuReason? {
    if (!capabilities.checked) return null
    return mpvHdrGpuDecision(true, "auto", HdrStandard.HDR10, display, capabilities, reducedEffects = false).reason
        ?.takeIf { it in setOf(MpvHdrGpuReason.VULKAN_UNAVAILABLE, MpvHdrGpuReason.SWAPCHAIN_NO_HDR10, MpvHdrGpuReason.DISPLAY_NO_HDR10) }
}
