package com.ambient.tv.ui.player

import android.media.AudioDeviceInfo
import com.ambient.tv.data.local.AudioOptions

/*
 * Pure mapping between the stored AudioOptions fields and the merged editor controls
 * ("Audio-Ausgabe", "PCM-Kanäle", "Nicht unterstützter Ton"). Stored values are never
 * rewritten just by showing them, so older profiles and backups keep their behaviour.
 */

/** Output shown in the editor. Legacy "source" follows the former global passthrough switch. */
internal fun displayedAudioOutput(options: AudioOptions, sourcePassthrough: Boolean): String =
    when (options.normalized().output) {
        "source" -> if (sourcePassthrough) "auto" else "pcm"
        "pcm" -> "pcm"
        "force" -> "force"
        else -> "auto"
    }

/** Writing an output choice. "Passthrough erzwingen" resets a stereo-only channel mix. */
internal fun withAudioOutput(options: AudioOptions, key: String): AudioOptions = when (key) {
    "pcm" -> options.copy(output = "pcm")
    "force" -> options.copy(output = "force", channels = "auto")
    else -> options.copy(output = "auto")
}

/** "PCM-Kanäle": 5.1/7.1 also send that layout when the output reports fewer channels. */
internal fun displayedPcmChannels(options: AudioOptions): String = when {
    options.forceChannels in setOf(6, 8) -> options.forceChannels
    options.pcmChannelLimit in setOf(2, 6, 8) -> options.pcmChannelLimit
    else -> 0
}.toString()

internal fun withPcmChannels(options: AudioOptions, key: String): AudioOptions = when (key) {
    "2" -> options.copy(pcmChannelLimit = 2, forceChannels = 0)
    "6" -> options.copy(pcmChannelLimit = 6, forceChannels = 6)
    "8" -> options.copy(pcmChannelLimit = 8, forceChannels = 8)
    else -> options.copy(pcmChannelLimit = 0, forceChannels = 0)
}

/** "Nicht unterstützter Ton": one choice for conversion place, local fallback and AC3. */
internal fun displayedUnsupportedAudio(options: AudioOptions): String {
    val o = options.normalized()
    return when {
        o.conversionLocation == "server" -> "server"
        o.localFallback == "off" -> "off"
        o.localFallback == "pcm" -> "pcm"
        o.localAc3 -> "ac3"
        else -> "auto"
    }
}

internal fun withUnsupportedAudio(options: AudioOptions, key: String): AudioOptions = when (key) {
    "ac3" -> options.copy(conversionLocation = "local", localFallback = "auto", localAc3 = true)
    "pcm" -> options.copy(conversionLocation = "local", localFallback = "pcm", localAc3 = false)
    "off" -> options.copy(conversionLocation = "local", localFallback = "off", localAc3 = false)
    "server" -> options.copy(conversionLocation = "server", localAc3 = false,
        localFallback = options.localFallback.takeIf { it != "off" } ?: "auto")
    else -> options.copy(conversionLocation = "local", localFallback = "auto", localAc3 = false)
}

/** Why the automatic profile chose what it did; shown under "Aktuelle Ausgabe". */
internal enum class AudioProfileReason { HDMI, ARC, SPEAKER, PERSONAL, MIXED }

internal fun audioProfileReason(route: AudioRouteCapabilities): AudioProfileReason {
    val types = route.outputTypes
    return when {
        route.profile == "headphones" -> AudioProfileReason.PERSONAL
        route.profile == "avr" && types.any { it == AudioDeviceInfo.TYPE_HDMI_ARC || it == AudioDeviceInfo.TYPE_HDMI_EARC } ->
            AudioProfileReason.ARC
        route.profile == "avr" -> AudioProfileReason.HDMI
        types.isNotEmpty() && types.all { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } -> AudioProfileReason.SPEAKER
        else -> AudioProfileReason.MIXED
    }
}
