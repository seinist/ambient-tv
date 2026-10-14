package com.ambient.tv.ui.player.backend

/** VLC 3 exposes a numeric FourCC; VLC 4 exposes a codec string and a separate FourCC. */
internal fun vlcTrackCodec(track: Any): String? {
    fun decode(value: Any?): String? = when (value) {
        is Number -> buildString {
            repeat(4) { index ->
                val c = (value.toInt() ushr (8 * index)) and 255
                if (c in 32..126) append(c.toChar())
            }
        }.trim().takeIf { it.isNotEmpty() }
        is String -> value.trim().takeIf { it.isNotEmpty() }
        else -> null
    }
    return decode(ReflectiveMembers.value(track, "codec"))
        ?: decode(ReflectiveMembers.value(track, "fourcc"))
}

internal fun VlcRuntimeStats.videoSizeLabel(): String? =
    if (videoWidth != null && videoHeight != null) "${videoWidth}×${videoHeight}" else null

internal fun VlcRuntimeStats.audioFormatLabel(): String? = listOfNotNull(
    audioCodec?.uppercase(),
    audioChannels?.let { "${it}ch" },
    audioSampleRate?.let { "$it Hz" },
).joinToString(" · ").takeIf { it.isNotBlank() }
