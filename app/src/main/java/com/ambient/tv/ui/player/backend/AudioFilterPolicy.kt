package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.AudioCatalog
import com.ambient.tv.data.local.AudioOptions

/** Values are validated before reaching native filter parsers. No arbitrary filter strings. */
internal fun mpvAudioGraph(options: AudioOptions, sampleRate: Int = 48000, sourceChannels: Int? = null): String {
    val o = options.normalized()
    return buildList {
        when {
            o.channels == "5.1" || o.channels == "7.1" -> Unit
            o.upmix && o.channels == "auto" && sourceChannels == 2 ->
                add("pan=5.1|c0=c0|c1=c1|c2=0.5*c0+0.5*c1|c3=0*c0|c4=0.7*c0|c5=0.7*c1")
            o.channels != "auto" -> {
                // libswresample uses the input channel layout, including center and surrounds.
                // Float output preserves peaks until the final linked limiter.
                add("aresample=ochl=stereo:osf=flt:rematrix_maxval=${if (o.maintainDownmixVolume) 1000 else 1}:center_mix_level=0.70710678:surround_mix_level=0.70710678:lfe_mix_level=0")
                when (o.channels) {
                    "mono" -> add("pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1")
                    "left" -> add("pan=stereo|c0=c0|c1=c0")
                    "right" -> add("pan=stereo|c0=c1|c1=c1")
                }
            }
        }
        if (o.eqEnabled) {
            if (o.preampDb != 0) add("volume=${o.preampDb}dB")
            o.eqDb.forEachIndexed { index, db ->
                if (db != 0 && AudioCatalog.frequencies[index] < sampleRate * 0.45) add("equalizer=f=${AudioCatalog.frequencies[index]}:t=o:w=1:g=$db")
            }
        }
        if (o.dialogue > 0) add("equalizer=f=2000:t=o:w=1.5:g=${o.dialogue}")
        if (o.normalize) add("dynaudnorm=f=150:g=15:p=0.9:m=2")
        if (o.night) add("acompressor=threshold=0.125:ratio=4:attack=10:release=250:makeup=1.5")
        if (o.eqEnabled || o.dialogue > 0 || o.normalize || o.night ||
            (o.maintainDownmixVolume && o.channels in setOf("stereo", "mono", "left", "right"))) add("alimiter=limit=0.95:level=0:latency=1")
    }.joinToString(",")
}

/** The planner resolves stereo routes before this adapter. Unrestricted surround
 * keeps auto-safe; a stereo upmix keeps 5.1 so the output does not undo its filter. */
internal fun mpvAudioChannels(planChannels: String, encoded: Boolean, upmix: Boolean, applyDsp: Boolean): String =
    when (planChannels) {
        "5.1" -> "5.1"
        "7.1" -> "7.1"
        "auto" -> if (!encoded && upmix && applyDsp) "5.1" else "auto-safe"
        else -> "stereo"
    }

internal const val MPV_LOCAL_AC3_FILTER = "lavcac3enc=bitrate=640:tospdif=yes"

/** Nerd-stats text for mpv's audio output: format, layout name and channel count. */
internal fun mpvAudioOutputText(format: String, channels: String?, channelCount: Int): String = listOfNotNull(
    format,
    channels?.takeIf { it.isNotBlank() && !format.startsWith("spdif", ignoreCase = true) },
    channelCount.takeIf { it > 0 }?.let { "$it ch" },
).joinToString(" · ")
