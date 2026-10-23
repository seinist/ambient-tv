package com.ambient.tv.ui.player

import java.util.Locale

internal fun audioChannelLabel(count: Int): String = when (count) {
    8 -> "7.1"
    7 -> "6.1"
    6 -> "5.1"
    2 -> "2.0"
    1 -> "1.0"
    else -> "${count}ch"
}

internal fun audioCodecLabel(codec: String): String = when (val key = codec.lowercase(Locale.ROOT)) {
    "eac3", "ec-3", "e-ac-3", "audio/eac3", "e-ac3" -> "E-AC3"
    "eac3-joc", "audio/eac3-joc" -> "E-AC3 Atmos"
    "ac3", "ac-3", "audio/ac3", "a52" -> "AC3"
    "truehd", "mlp", "audio/true-hd" -> "TrueHD"
    "dca", "dts", "dts ", "audio/vnd.dts" -> "DTS"
    "dts-hd", "audio/vnd.dts.hd" -> "DTS-HD"
    "aac", "mp4a", "audio/mp4a-latm" -> "AAC"
    "audio/raw", "pcm" -> "PCM"
    "mp2", "audio/mpeg-l2" -> "MP2"
    "mp1", "audio/mpeg-l1" -> "MP1"
    "mp3", "audio/mpeg" -> "MP3"
    else -> if (key.startsWith("mp4a.")) "AAC" else codec.uppercase(Locale.ROOT).substringAfterLast('/')
}

internal fun audioTrackLabel(title: String?, language: String?, channels: Int?, codec: String?, fallback: String = "Audio"): String {
    val languageName = language?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { audioLanguageDisplayName(it, Locale.getDefault()) ?: it }
    val parts = mutableListOf<String>()
    title?.takeIf { it.isNotBlank() }?.let { parts.add(expandAudioLanguageCodes(it, Locale.getDefault())) }
    languageName?.takeIf { name -> parts.none { it.contains(name, ignoreCase = true) } }?.let(parts::add)
    channels?.takeIf { it > 0 }?.let(::audioChannelLabel)?.let { layout ->
        if (parts.none { it.contains(layout) }) parts.add(layout)
    }
    codec?.takeIf { it.isNotBlank() }?.let(::audioCodecLabel)?.let { name ->
        if (parts.none { it.equals(name, ignoreCase = true) || it.endsWith(" · $name", ignoreCase = true) }) parts.add(name)
    }
    return parts.joinToString(" · ").ifBlank { fallback }
}

/** Compact output conversion, without duplicating codec or processing descriptions. */
internal fun audioDownmixLabel(sourceChannels: Int?, plan: AudioOutputPlan): String? {
    if (plan.encoded || sourceChannels == null || sourceChannels <= 2) return null
    val target = when (plan.channels) {
        "stereo", "left", "right" -> "Stereo"
        "mono" -> "Mono"
        "5.1" -> if (sourceChannels > 6) "5.1" else return null
        else -> return null
    }
    return "${audioChannelLabel(sourceChannels)} → $target"
}
