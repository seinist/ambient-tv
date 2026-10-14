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
        audioOnly = audioOnly || previous.audioOnly
        restoreProfile(previous.settings, automatic = true)
        restoreTitleDelay(previous.titleDelayMs)
        manuallyEdited = previous.manuallyEdited
        automaticProfile = previous.automaticProfile
        automaticProfileInitialized = previous.automaticProfileInitialized
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
    internal var apply: (AudioOptions, AudioOptions) -> Unit = { _, _ -> }
    internal var routeChanged: (AudioOutputPlan, AudioOutputPlan) -> Unit = { _, _ -> }
    private var routeMonitor: AudioRouteMonitor? = null
    val bitstreamPermitted: Boolean get() = outputPlan().encoded

    fun refreshRoute(context: android.content.Context, codec: String? = sourceCodec) {
        updateRoute(AudioPassthroughPolicy.probeCached(context).copy(mpvAvailable = MpvBackendAvailability.isAvailable))
        if (codec != null) sourceCodec = codec
    }

    fun startRouteMonitoring(context: android.content.Context) {
        routeMonitor?.close()
        routeMonitor = AudioRouteMonitor(context, ::updateRoute)
    }

    internal fun updateRoute(next: AudioRouteCapabilities) {
        val oldOptions = options
        val before = outputPlan()
        route = next
        renderOptions = optionsForPcm()
        val after = outputPlan()
        // DSP load is diagnostic metadata, not an output-format change. Reopening a
        // PCM sink just to enable a saved night/EQ profile causes an unnecessary gap.
        if (before.copy(heavyAudio = after.heavyAudio) != after) routeChanged(before, after)
        else if (oldOptions != options) apply(oldOptions, options)
    }

    fun release() {
        activeLocalConversion = null
        routeMonitor?.close()
        routeMonitor = null
        routeChanged = { _, _ -> }
        apply = { _, _ -> }
        conversionLocationChanged = {}
    }

    fun outputPlan(media3CanDecode: Boolean = true, options: AudioOptions = this.options): AudioOutputPlan {
        val effective = when {
            forceStereoPcm -> options.copy(output = "pcm", channels = "stereo", localFallback = "pcm", localAc3 = false)
            audioOnly -> options.copy(output = "pcm", localFallback = "pcm", localAc3 = false)
            localEncoderFailed -> options.copy(localFallback = "pcm", localAc3 = false)
            else -> options
        }
        return planAudioOutput(effective,
        sourceCodec, route, backend, sourcePassthrough, media3CanDecode, AudioCodecRejects.snapshot(), sourceChannels,
    ).let { plan ->
        if (backend != "media3" || !com.ambient.tv.ui.player.audio.LocalAudioBridge.AVAILABLE ||
            normalizeSourceCodec(sourceCodec) == null || options.localFallback == "off") plan
        else if (plan.mode == AudioOutputMode.PASSTHROUGH) plan
        else if (!localEncoderFailed && !audioOnly && !forceStereoPcm &&
            com.ambient.tv.ui.player.audio.localAc3Eligible(options.localFallback, options.output, options.channels,
                sourceChannels ?: 0, "ac3" in route.passthrough && "ac3" in options.allowedCodecs))
            AudioOutputPlan(AudioOutputMode.LOCAL_AC3, encoded = true, channels = "5.1", applyDsp = false, heavyAudio = true)
        // Local FFmpeg decode keeps the planned PCM layout (stereo only when forced or chosen).
        else AudioOutputPlan(AudioOutputMode.PCM, channels = localDecodePcmChannelMode(effective.normalized().channels, route.pcmChannels))
    }
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
        val flags = (if (forceStereoPcm) 1 else 0) or (if (localEncoderFailed) 2 else 0) or (if (audioOnly) 4 else 0)
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

    var conversionLocationChanged: (String) -> Unit = {}

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
        if (old.conversionLocation != options.conversionLocation) conversionLocationChanged(options.conversionLocation)
        else apply(old, options)
    }

    fun diagnosticSummary(): String = buildList {
        val plan = outputPlan()
        add("${settings.effectiveProfile(route.profile)} · ${plan.mode.name.lowercase().replace('_', ' ')}")
        add("A/V ${options.delayMs} ms")
        if (plan.encoded && !plan.applyDsp) add("PCM DSP suspended") else {
            if (plan.channels != "auto" && backend != "vlc") add(plan.channels)
            if (options.eqEnabled) add("EQ")
            if (options.dialogue > 0) add("speech EQ +${options.dialogue} dB")
            if (options.night && supportsNight) add("compressor + limiter")
            if (options.normalize && backend != "vlc") add("normalization")
            if (options.replayGain != "off" && backend == "mpv") add("ReplayGain ${options.replayGain}")
            if (plan.mode == AudioOutputMode.LOCAL_AC3) add("local AC3")
        }
    }.joinToString(" · ")
}
