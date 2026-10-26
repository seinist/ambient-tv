package com.ambient.tv.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.data.local.AudioSettings
import com.ambient.tv.data.local.AudioTrackPreference
import com.ambient.tv.ui.player.backend.MpvBackendAvailability

/** Owned by the playback handle, so closing the panel/minimizing does not lose changes. */
class AudioSession(
    private val preferences: AppPreferences,
    val backend: String,
    val sourcePassthrough: Boolean,
    audioOnly: Boolean = false,
) {
    var audioOnly by mutableStateOf(audioOnly)
        private set

    /** Content policy, independent of saved output profiles and visualizer selection. */
    fun updateAudioOnly(value: Boolean) {
        if (audioOnly == value) return
        val before = outputPlan()
        audioOnly = value
        renderOptions = optionsForPcm()
        val after = outputPlan()
        if (before != after) routeChanged(before, after)
    }

    var settings by mutableStateOf(preferences.audioSettings.copy(performanceHint = false))
        private set
    /**
     * A/V delay chosen in the player for the current title; null uses the profile's delay. Only
     * while [titleScoped]: the profile delay (Settings) stays the default for everything else.
     */
    var titleDelayMs by mutableStateOf<Int?>(null)
        private set
    /** True while the playing item can remember its own delay (including live channels, excluding trailers). */
    var titleScoped = false
    private var manuallyEdited = false
    internal val acceptsAutomaticProfile: Boolean get() = !manuallyEdited
    private var automaticProfile: AudioSettings? = null
    private var automaticProfileInitialized = false

    /** Late resolution discovery may refine defaults, but must not undo player edits. */
    internal fun restoreAutomaticProfile(profile: AudioSettings?): Boolean {
        if (manuallyEdited || (automaticProfileInitialized && profile == automaticProfile)) return false
        restoreProfile(profile, automatic = true)
        automaticProfile = profile
        automaticProfileInitialized = true
        return true
    }

    /** Keep manual edits and automatic-profile provenance across output/decoder restarts. */
    internal fun restoreFromSession(previous: AudioSession) {
        // Output failures belong to the renderer that hit them: a rebuild of the same renderer
        // keeps them, a switch to another renderer starts clean (it re-sets them if it fails too).
        val sameRenderer = previous.backend == backend
        forceStereoPcm = previous.forceStereoPcm && sameRenderer
        forcedChannelsFailed = previous.forcedChannelsFailed && sameRenderer
        localEncoderFailed = previous.localEncoderFailed
        audioOnly = audioOnly || previous.audioOnly
        restoreProfile(previous.settings, automatic = true)
        restoreTitleDelay(previous.titleDelayMs)
        manuallyEdited = previous.manuallyEdited
        automaticProfile = previous.automaticProfile
        automaticProfileInitialized = previous.automaticProfileInitialized
        // Renderer/output/decoder replacement continues this playback, including its diagnostics.
        performanceHint = previous.performanceHint
        showFpsOverlay = previous.showFpsOverlay
    }

    /** Apply a playback profile without replacing the global audio defaults. */
    fun restoreProfile(profile: AudioSettings?, automatic: Boolean = false) {
        if (!automatic) manuallyEdited = true
        val old = options
        settings = (profile ?: preferences.audioSettings).normalized().copy(performanceHint = false)
        titleDelayMs = null
        renderOptions = optionsForPcm()
        apply(old, options)
    }
    /**
     * Stats-for-Nerds for this playback only. Default off; never written to preferences.
     * Closing the player drops the flag with the session.
     */
    var performanceHint by mutableStateOf(false)
    /**
     * Compact FPS / display-refresh overlay for this playback only. Default off; never persisted.
     * Closing the player drops the flag with the session.
     */
    var showFpsOverlay by mutableStateOf(false)
    var route by mutableStateOf(AudioRouteCapabilities())
    var mpvAllowed by mutableStateOf(true)
        private set
    val effectiveRoute: AudioRouteCapabilities get() = if (mpvAllowed || !route.mpvAvailable) route else route.copy(mpvAvailable = false)

    fun updateMpvAllowed(allowed: Boolean) {
        if (mpvAllowed == allowed) return
        val before = outputPlan()
        mpvAllowed = allowed
        renderOptions = optionsForPcm()
        val after = outputPlan()
        if (before != after) routeChanged(before, after)
    }
    val options: AudioOptions get() = settings.currentForRoute(route.profile).let { o -> titleDelayMs?.let { o.copy(delayMs = it) } ?: o }
    /** What the player's audio editor shows: the profile with the title delay applied. */
    val displaySettings: AudioSettings get() = if (titleDelayMs == null) settings else settings.updateForRoute(options, route.profile)
    @Volatile var renderOptions: AudioOptions = options
        private set
    var supportsNight by mutableStateOf(backend != "vlc")
    var warning by mutableStateOf<String?>(null)
    var activeLocalConversion by mutableStateOf<String?>(null)
    var sourceCodec by mutableStateOf<String?>(null)
    var sourceChannels by mutableStateOf<Int?>(null)
    var localEncoderFailed by mutableStateOf(false)
    var forceStereoPcm by mutableStateOf(false)
    /** The output rejected the forced 5.1/7.1 layout; this playback follows the route again. */
    var forcedChannelsFailed by mutableStateOf(false)
    /** Output channel capacity after "Kanäle erzwingen", unless that failed for this playback. */
    val outputPcmChannels: Int get() =
        if (forcedChannelsFailed || forceStereoPcm) route.pcmChannels else options.forcedChannels(route.pcmChannels)
    internal var apply: (AudioOptions, AudioOptions) -> Unit = { _, _ -> }
    internal var routeChanged: (AudioOutputPlan, AudioOutputPlan) -> Unit = { _, _ -> }
    private var routeMonitor: AudioRouteMonitor? = null
    val bitstreamPermitted: Boolean get() = outputPlan().encoded

    fun refreshRoute(context: android.content.Context, codec: String? = sourceCodec) {
        updateRoute(AudioPassthroughPolicy.probeCached(context).copy(mpvAvailable = MpvBackendAvailability.isAvailable))
        if (codec != null) sourceCodec = codec
        logPlanIfChanged()
    }

    private var lastPlanLog: String? = null
    private var lastPlanLogDiagnostics = false

    /** Logs the audio decision under `audio-route` once per change (logcat, diagnostics file). */
    internal fun logPlanIfChanged() {
        val line = runCatching {
            audioPlanLogLine(backend, sourcePassthrough, options, settings.selectedProfile, effectiveRoute,
                sourceCodec, sourceChannels, outputPlan(), forcedChannelsFailed, forceStereoPcm)
        }.getOrNull() ?: return
        val diagnostics = com.ambient.tv.diagnostics.DiagnosticLog.enabled
        if (line == lastPlanLog && diagnostics == lastPlanLogDiagnostics) return
        lastPlanLog = line
        lastPlanLogDiagnostics = diagnostics
        logAudioRouteLine(line)
    }

    fun startRouteMonitoring(context: android.content.Context) {
        routeMonitor?.close()
        routeMonitor = AudioRouteMonitor(context, ::routeDevicesChanged)
    }

    private var outputFallbackResets = 0

    /**
     * Debounced audio device change (HDMI back after a mode switch, receiver switched on). An
     * output failure during the gap (AO init -14) left this playback on the route's channels,
     * stereo PCM or without a bitstream codec. When the settled route offers more than stereo
     * again, those fallbacks are lifted and the output reopens once with the real settings.
     * At most [MAX_OUTPUT_FALLBACK_RESETS] times per playback, so a truly failing output does not
     * cut the sound on every device event.
     */
    internal fun routeDevicesChanged(next: AudioRouteCapabilities) {
        val rejects = AudioCodecRejects.snapshot()
        val reset = audioFallbackResetOnRoute(
            fallbackActive = forceStereoPcm || forcedChannelsFailed || rejects.isNotEmpty(),
            routeOffersMore = next.pcmChannels > 2 || next.passthrough.isNotEmpty(),
            resetsSoFar = outputFallbackResets,
        )
        if (reset) {
            outputFallbackResets++
            runCatching { logAudioRouteLine("Audio route back backend=$backend: retrying output " +
                "(forced_channels_failed=$forcedChannelsFailed stereo=$forceStereoPcm rejected=${rejects.joinToString(",").ifEmpty { "-" }})") }
            forceStereoPcm = false
            forcedChannelsFailed = false
            AudioCodecRejects.clearOutputRejects()
        }
        updateRoute(next, forceReconfigure = reset)
    }

    internal fun updateRoute(next: AudioRouteCapabilities, forceReconfigure: Boolean = false) {
        val oldOptions = options
        val before = outputPlan()
        route = next
        renderOptions = optionsForPcm()
        val after = outputPlan()
        // DSP load is diagnostic metadata, not an output-format change. Reopening a
        // PCM sink just to enable a saved night/EQ profile causes an unnecessary gap.
        if (forceReconfigure || before.copy(heavyAudio = after.heavyAudio) != after) {
            runCatching { logAudioRouteLine("Audio route plan changed backend=$backend " +
                "before=${before.mode}/${before.channels}/${before.spdifCodecs.joinToString(",").ifEmpty { "-" }} " +
                "after=${after.mode}/${after.channels}/${after.spdifCodecs.joinToString(",").ifEmpty { "-" }}") }
            routeChanged(before, after)
        }
        else if (oldOptions != options) apply(oldOptions, options)
        logPlanIfChanged()
    }

    fun release() {
        activeLocalConversion = null
        routeMonitor?.close()
        routeMonitor = null
        routeChanged = { _, _ -> }
        apply = { _, _ -> }
        conversionLocationChanged = { false }
    }

    fun outputPlan(media3CanDecode: Boolean = true, options: AudioOptions = this.options): AudioOutputPlan =
        outputPlanFor(sourceCodec, sourceChannels, media3CanDecode, options)

    /** [outputPlan] for an explicit source, e.g. a format Media3 queries before onTracksChanged. */
    fun outputPlanFor(
        sourceCodec: String?, sourceChannels: Int?,
        media3CanDecode: Boolean = true, options: AudioOptions = this.options,
    ): AudioOutputPlan {
        val base = if (forcedChannelsFailed) options.copy(forceChannels = 0) else options
        val effective = when {
            forceStereoPcm -> base.copy(output = "pcm", channels = "stereo", localFallback = "pcm", localAc3 = false, forceChannels = 0)
            audioOnly -> base.copy(output = "pcm", localFallback = "pcm", localAc3 = false)
            localEncoderFailed -> base.copy(localFallback = "pcm", localAc3 = false)
            else -> base
        }
        return planAudioOutput(
            effective, sourceCodec, effectiveRoute, backend, sourcePassthrough, media3CanDecode,
            AudioCodecRejects.snapshot(), sourceChannels,
            dtsCoreMissing = AudioCodecRejects.dtsCoreMissing,
            media3LocalAvailable = com.ambient.tv.ui.player.audio.LocalAudioBridge.AVAILABLE &&
                com.ambient.tv.ui.player.audio.localDecodableSourceCodec(sourceCodec),
        )
    }

    private var pcmMemo: AudioOptions? = null
    private var pcmMemoOptions: AudioOptions? = null
    private var pcmMemoRoute: AudioRouteCapabilities? = null
    private var pcmMemoCodec: String? = null
    private var pcmMemoChannels: Int? = null
    private var pcmMemoFlags = -1
    private var pcmMemoRejects = -1

    /**
     * [optionsForPcm] for the audio thread, which asks once per buffer. Planning allocates (reject
     * snapshot, normalisation), so the result is reused until one of its inputs changes.
     * Only the playback thread calls this.
     */
    internal fun pcmOptionsForAudioThread(): AudioOptions {
        val options = options
        val route = route
        val codec = sourceCodec
        val channels = sourceChannels
        val flags = (if (forceStereoPcm) 1 else 0) or (if (localEncoderFailed) 2 else 0) or
            (if (audioOnly) 4 else 0) or (if (mpvAllowed) 8 else 0) or (if (forcedChannelsFailed) 16 else 0)
        val rejects = AudioCodecRejects.version
        pcmMemo?.let { memo ->
            if (options == pcmMemoOptions && route == pcmMemoRoute && codec == pcmMemoCodec &&
                channels == pcmMemoChannels && flags == pcmMemoFlags && rejects == pcmMemoRejects) return memo
        }
        return optionsForPcm().also {
            pcmMemo = it
            pcmMemoOptions = options
            pcmMemoRoute = route
            pcmMemoCodec = codec
            pcmMemoChannels = channels
            pcmMemoFlags = flags
            pcmMemoRejects = rejects
        }
    }

    /** PCM DSP follows the actual plan, not the mere wish for passthrough. */
    fun optionsForPcm(media3CanDecode: Boolean = true): AudioOptions {
        val plan = outputPlan(media3CanDecode)
        return options.pcmOptions(sourcePassthrough, encodedOutput = plan.encoded && !plan.applyDsp)
            .copy(channels = plan.channels)
    }

    /** Returns true only when the owner accepted a stream reload. */
    var conversionLocationChanged: (AudioSession) -> Boolean = { false }

    fun update(next: AudioOptions) {
        manuallyEdited = true
        val old = options
        if (titleScoped && next.delayMs != old.delayMs) {
            titleDelayMs = next.delayMs.coerceIn(-2000, 2000)
            commit(settings.updateForRoute(next.copy(delayMs = settings.currentForRoute(route.profile).delayMs), route.profile), old)
        } else {
            // Other changes never write a title delay into the profile.
            commit(settings.updateForRoute(next.copy(delayMs = if (titleDelayMs != null) settings.currentForRoute(route.profile).delayMs else next.delayMs), route.profile), old)
        }
    }

    /** Restores the delay remembered for this title (or clears it) and applies it live. */
    fun restoreTitleDelay(delayMs: Int?) {
        val old = options
        titleDelayMs = delayMs?.coerceIn(-2000, 2000)
        renderOptions = optionsForPcm()
        apply(old, options)
    }
    fun selectProfile(key: String) {
        manuallyEdited = true
        commit(settings.copy(selectedProfile = key).normalized())
    }
    fun trackPreference(next: AudioTrackPreference) {
        manuallyEdited = true
        settings = settings.copy(tracks = next.normalized(), performanceHint = false)
    }

    // Player edits stay in the session; title autosave and explicit shared saves own persistence.
    private fun commit(next: AudioSettings, old: AudioOptions = options) {
        // Never persist Stats-for-Nerds; it is session-scoped via [performanceHint].
        settings = next.copy(performanceHint = false).normalized()
        renderOptions = optionsForPcm()
        warning = null
        if (!audioConversionRequiresReload(old, options) || !conversionLocationChanged(this)) apply(old, options)
        logPlanIfChanged()
    }

    fun diagnosticSummary(): String = buildList {
        val plan = outputPlan()
        add("${settings.effectiveProfile(route.profile)} · ${plan.mode.name.lowercase().replace('_', ' ')}")
        add("A/V ${options.delayMs} ms")
        if (plan.encoded && !plan.applyDsp) add("PCM DSP suspended") else {
            val downmix = audioDownmixLabel(sourceChannels, plan)
            if (downmix != null) add(downmix)
            else if (plan.channels != "auto" && backend != "vlc") add(plan.channels)
            if (options.eqEnabled) add("EQ")
            if (options.dialogue > 0) add("speech EQ +${options.dialogue} dB")
            if (options.night && supportsNight) add("compressor + limiter")
            if (options.normalize && backend != "vlc") add("normalization")
            if (options.replayGain != "off" && backend == "mpv") add("ReplayGain ${options.replayGain}")
            if (plan.mode == AudioOutputMode.LOCAL_AC3) add("local AC3")
        }
    }.joinToString(" · ")
}

internal const val MAX_OUTPUT_FALLBACK_RESETS = 2

/** Whether a settled device change lifts this playback's output fallbacks (see [AudioSession.routeDevicesChanged]). */
internal fun audioFallbackResetOnRoute(fallbackActive: Boolean, routeOffersMore: Boolean, resetsSoFar: Int): Boolean =
    fallbackActive && routeOffersMore && resetsSoFar < MAX_OUTPUT_FALLBACK_RESETS
