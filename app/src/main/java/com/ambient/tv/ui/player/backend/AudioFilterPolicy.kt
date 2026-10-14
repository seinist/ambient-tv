package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.AudioCatalog
import com.ambient.tv.data.local.AudioOptions

/** Values are validated before reaching native filter parsers. No arbitrary filter strings. */
internal fun mpvAudioGraph(options: AudioOptions, sampleRate: Int = 48000): String {
    val o = options.normalized()
    return buildList {
        when {
            o.channels == "5.1" || o.channels == "7.1" -> Unit
            o.upmix && (o.channels == "auto" || o.channels == "stereo") ->
                add("pan=5.1|c0=c0|c1=c1|c2=0.5*c0+0.5*c1|c3=0*c0|c4=0.7*c0|c5=0.7*c1")
            o.channels != "auto" -> {
                add("aformat=channel_layouts=stereo")
                when (o.channels) {
                    "mono" -> add("pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1")
                    "left" -> add("pan=stereo|c0=c0|c1=c0")
                    "right" -> add("pan=stereo|c0=c1|c1=c1")
                }
                if (o.maintainDownmixVolume) add("volume=3dB")
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
        if (o.eqEnabled || o.dialogue > 0 || o.normalize || o.night) add("alimiter=limit=0.95:level=0:latency=1")
    }.joinToString(",")
}

/** Automatic MPV layouts stay `auto-safe` so the audio API keeps surround PCM.
 * Forcing `stereo` (or a layout list that ends in stereo) software-downmixes 5.1
 * and plays quieter than Media3. Explicit stereo, mono, left, and right still map
 * to stereo; upmix keeps a 5.1 layout so the pan filter is not undone. */
internal fun mpvAudioChannels(planChannels: String, encoded: Boolean, upmix: Boolean, applyDsp: Boolean): String =
    when (planChannels) {
        "5.1" -> "5.1"
        "7.1" -> "7.1"
        "auto" -> if (!encoded && upmix && applyDsp) "5.1" else "auto-safe"
        else -> "stereo"
    }

internal const val MPV_LOCAL_AC3_FILTER = "lavcac3enc=bitrate=640:tospdif=yes"
