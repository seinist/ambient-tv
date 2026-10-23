package com.ambient.tv.ui.player

import java.util.Locale

/** Compact OSD names, including FFmpeg descriptions and MIME/RFC 6381 identifiers. */
internal fun playerCodecLabel(value: String): String {
    val label = compactKnownCodec(value) ?: value.trim().substringBefore(' ').substringBefore('(')
        .substringAfterLast('/').take(12)
    return label.uppercase(Locale.ROOT)
}

/** Preserve non-codec stream badges such as frame rates and decoder status. */
internal fun compactStreamBadge(value: String): String = compactKnownCodec(value) ?: value

private fun compactKnownCodec(value: String): String? {
    val key = value.trim().lowercase(Locale.ROOT).removePrefix("video/").removePrefix("audio/")
    return codecLabels.firstOrNull { (pattern, _) -> pattern.containsMatchIn(key) }?.second
}

private val codecLabels = listOf(
    "(?:hevc|h[ .]?265|hvc1|hev1|mpeg-h|high efficiency video coding)" to "HEVC",
    "(?:h[ .]?264|avc1?|advanced video coding|mpeg-4 avc)" to "H.264",
    "(?:av01|av1|aomedia video 1)" to "AV1",
    "(?:vp0?9|google vp9)" to "VP9",
    "(?:vp0?8|google vp8)" to "VP8",
    "(?:mpeg2video|mpeg-?2)" to "MPEG-2",
    "(?:mpeg1video|mpeg-?1)" to "MPEG-1",
    "(?:mpeg4|mpeg-4|mp4v-es)" to "MPEG-4",
    "(?:e-?ac-?3|eac3-joc|dolby digital plus)" to "E-AC3",
    "(?:ac-?3|a52|dolby digital)" to "AC3",
    "(?:truehd|true-hd|dolby truehd)" to "TRUEHD",
    "(?:dts-hd ma|dts-hd master audio)" to "DTS-HD MA",
    "(?:dts-hd|vnd\\.dts\\.hd)" to "DTS-HD",
    "(?:dts|dca|vnd\\.dts)" to "DTS",
    "(?:aac|mp4a(?:-latm|\\.[a-z0-9.]+)?|advanced audio coding)" to "AAC",
    "(?:mp2|mpeg audio layer 2)" to "MP2",
    "(?:mp3|mpeg layer 3|mpeg audio layer 3|mpeg)" to "MP3",
    "(?:flac|free lossless audio codec)" to "FLAC",
    "(?:alac|apple lossless audio codec)" to "ALAC",
    "(?:opus)" to "OPUS",
    "(?:vorbis)" to "VORBIS",
    "(?:pcm(?:_[a-z0-9]+)?|raw|linear pcm)" to "PCM",
    "(?:vc-?1|wvc1)" to "VC-1",
).map { (pattern, label) -> Regex("^(?:$pattern)(?=$|[ /(.:-])") to label }
