package com.ambient.tv.data.local

import kotlinx.serialization.Serializable

object AudioCatalog {
    val profiles = listOf("tv", "avr", "headphones")
    val codecs = listOf("ac3", "eac3", "dts", "dts-hd", "truehd")
    val frequencies = listOf(60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000)
}

/** Engine-neutral intent. Adapters expose and apply only their supported subset. */
@Serializable
data class AudioOptions(
    val output: String = "source",
    val allowedCodecs: List<String> = AudioCatalog.codecs,
    val delayMs: Int = 0,
    val channels: String = "auto",
    val night: Boolean = false,
    val dialogue: Int = 0,
    val normalize: Boolean = false,
    val replayGain: String = "off",
    val eqEnabled: Boolean = false,
    val preampDb: Int = 0,
    val eqDb: List<Int> = List(10) { 0 },
    /** Explicit Dolby Digital transcoding, independently of PCM connection width. */
    val localAc3: Boolean = false,
    /** 0 follows the route; this limits PCM transport, not passthrough or AC3. */
    val pcmChannelLimit: Int = 0,
    /**
     * Counterpart to [pcmChannelLimit]: 6 or 8 treat the output as at least 5.1/7.1 even when
     * Android reports only stereo (TV speakers, ARC, a 2ch EDID). 0 follows the route. The
     * limit still wins when both are set.
     */
    val forceChannels: Int = 0,
    /** Automatic local recovery; legacy localAc3 remains readable in saved profiles. */
    val localFallback: String = "auto",
    val conversionLocation: String = "local",
    val upmix: Boolean = false,
    val maintainDownmixVolume: Boolean = false,
) {
    fun normalized(): AudioOptions {
        val force = forceChannels.takeIf { it in setOf(0, 6, 8) } ?: 0
        val limit = pcmChannelLimit.takeIf { it in setOf(0, 2, 6, 8) } ?: 0
        // Legacy channel mix 5.1/7.1 duplicated the PCM channel setting: it becomes a PCM limit
        // with the same effect (the smaller value wins, as the planner clipped it before).
        val mixLimit = when (channels) { "5.1" -> 6; "7.1" -> 8; else -> 0 }
        return copy(
            pcmChannelLimit = if (mixLimit == 0) limit else if (limit in setOf(2, 6, 8)) minOf(limit, mixLimit) else mixLimit,
            forceChannels = force,
            conversionLocation = conversionLocation.takeIf { it in setOf("local", "server") } ?: "local",
            localFallback = localFallback.takeIf { it in setOf("auto", "pcm", "off") } ?: "auto",
            // Legacy "bitstream" ("Passthrough bevorzugen") equalled "auto"; only together with
            // forced channels did it also force Dolby/DTS. That combination is now "force".
            output = when (output) {
                "bitstream" -> if (force in setOf(6, 8)) "force" else "auto"
                in setOf("source", "auto", "pcm", "force") -> output
                else -> "source"
            },
            allowedCodecs = AudioCatalog.codecs.filter { it in allowedCodecs && (it != "dts-hd" || "dts" in allowedCodecs) },
            delayMs = delayMs.coerceIn(-2000, 2000),
            channels = channels.takeIf { it in setOf("auto", "stereo", "mono", "left", "right") } ?: "auto",
            dialogue = dialogue.coerceIn(0, 6),
            replayGain = replayGain.takeIf { it in setOf("off", "track", "album") } ?: "off",
            preampDb = preampDb.coerceIn(-12, 0),
            eqDb = List(10) { eqDb.getOrElse(it) { 0 }.coerceIn(-12, 12) },
        )
    }

    /** "Passthrough erzwingen": Dolby/DTS are sent even when the output does not report them. */
    fun forcesPassthrough(): Boolean = output == "force" || output == "bitstream" && forceChannels in setOf(6, 8)

    /** Anything the output is asked for beyond what it reports (channels or passthrough). */
    fun forcesOutput(): Boolean = forceChannels in setOf(6, 8) || forcesPassthrough()

    /** Fallback after the output rejected a forced format: back to what it reports. */
    fun withoutForcing(): AudioOptions =
        copy(forceChannels = 0, output = if (forcesPassthrough()) "auto" else output)

    /**
     * Explicit stereo/mono selections require PCM, including when bitstream was preferred.
     * source: inherit the per-source passthrough switch.
     * auto: use confirmed encoded output; saved DSP settings apply only when decoding.
     * force (legacy bitstream): like auto, and Dolby/DTS are sent even when not reported.
     */
    fun permitsBitstream(sourceDefault: Boolean): Boolean = channels !in setOf("stereo", "mono", "left", "right") && when (output) {
        "pcm" -> false
        "force", "bitstream", "auto" -> true
        else -> sourceDefault
    }

    /** Channels the output is treated as accepting: the route, raised by [forceChannels]. */
    fun forcedChannels(routeChannels: Int): Int =
        if (forceChannels in setOf(6, 8)) maxOf(routeChannels, forceChannels) else routeChannels

    fun pcmChannels(routeChannels: Int): Int = forcedChannels(routeChannels).let { channels ->
        if (pcmChannelLimit in setOf(2, 6, 8)) minOf(channels, pcmChannelLimit) else channels
    }

    fun transcodesAc3(sourceDefault: Boolean, sourceChannels: Int?, routeChannels: Int, ac3Supported: Boolean): Boolean =
        localAc3 && localFallback == "auto" && permitsBitstream(sourceDefault) &&
            pcmChannels(routeChannels) <= 2 && (sourceChannels ?: 0) > 2 && ac3Supported

    fun requestsPcmProcessing(): Boolean =
        channels != "auto" || night || dialogue > 0 || normalize || replayGain != "off" ||
            eqEnabled || upmix || maintainDownmixVolume

    /** DSP is opt-in PCM. Encoded passthrough and DTS core leave the saved values untouched. */
    fun pcmOptions(sourceDefault: Boolean, encodedOutput: Boolean = permitsBitstream(sourceDefault)): AudioOptions =
        if (encodedOutput) copy(
            channels = "auto", night = false, dialogue = 0, normalize = false,
            replayGain = "off", eqEnabled = false, preampDb = 0, upmix = false,
        ) else this

    fun preset(key: String): AudioOptions = when (key) {
        "speech" -> copy(eqEnabled = true, preampDb = -4, eqDb = listOf(-3, -2, -1, 0, 2, 3, 2, 0, -1, -2))
        "tv" -> copy(eqEnabled = true, preampDb = -3, eqDb = listOf(-2, -1, 0, 1, 2, 2, 0, 0, -1, -1))
        "headphones" -> copy(eqEnabled = true, preampDb = -3, eqDb = listOf(2, 1, 0, -1, 0, 1, 1, 0, -1, -1))
        else -> copy(eqEnabled = false, preampDb = 0, eqDb = List(10) { 0 })
    }
}

@Serializable
data class AudioTrackPreference(
    val role: String = "auto",
    val channels: String = "auto",
) {
    fun normalized() = copy(
        role = role.takeIf { it in setOf("auto", "main", "original", "commentary", "description") } ?: "auto",
        channels = channels.takeIf { it in setOf("auto", "stereo", "surround") } ?: "auto",
    )
    val enabled: Boolean get() = role != "auto" || channels != "auto"
}

@Serializable
data class AudioSettings(
    val selectedProfile: String = "auto",
    val profiles: Map<String, AudioOptions> = AudioCatalog.profiles.associateWith { AudioOptions() },
    val tracks: AudioTrackPreference = AudioTrackPreference(),
    /** Legacy field; Stats-for-Nerds is session-only on AudioSession and is never persisted. */
    val performanceHint: Boolean = false,
) {
    fun normalized() = copy(
        selectedProfile = selectedProfile.takeIf { it == "auto" || it in AudioCatalog.profiles } ?: "auto",
        profiles = AudioCatalog.profiles.associateWith { (profiles[it] ?: AudioOptions()).normalized() },
        tracks = tracks.normalized(),
    )
    fun effectiveProfile(routeProfile: String): String =
        if (selectedProfile == "auto") routeProfile.takeIf { it in AudioCatalog.profiles } ?: "tv" else selectedProfile
    fun currentForRoute(routeProfile: String): AudioOptions = profiles[effectiveProfile(routeProfile)] ?: AudioOptions()
    fun updateForRoute(options: AudioOptions, routeProfile: String): AudioSettings =
        copy(profiles = profiles + (effectiveProfile(routeProfile) to options.normalized()))
    /** Legacy route-free consumers use TV defaults; playback always supplies the routed profile. */
    val current: AudioOptions get() = currentForRoute("tv")
    fun update(options: AudioOptions) = updateForRoute(options, "tv")
}
