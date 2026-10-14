package com.ambient.tv.youtube

import java.util.Locale

/** One adaptive (video-only or audio-only) format as SmartTube lists it. */
data class YouTubeAdaptiveFormat(
    val mimeType: String?,
    val url: String?,
    val height: Int = 0,
    val bitrate: Long = 0,
    val isOtf: Boolean = false,
    val isDrc: Boolean = false,
    val xtags: String? = null,
    val width: Int = 0,
    val fps: Int = 0,
    val audioTrackId: String? = null,
    val language: String? = null,
)

/**
 * Whether the device plays [family] ("avc", "vp9", "av1") at width×height and fps in hardware;
 * [hdr] ("hdr10", "hlg") additionally needs the 10-bit HDR decoder profile and a matching display.
 */
typealias YouTubeDecodeCheck = (family: String, width: Int, height: Int, fps: Int, hdr: String?) -> Boolean

/** Any SDR stream, no HDR: the default when nothing about the device is known. */
val youtubeDecodeAnything: YouTubeDecodeCheck = { _, _, _, _, hdr -> hdr == null }

const val YOUTUBE_STREAM_AUTO = "auto"

/** A selectable video quality: one resolution/frame rate/codec combination. */
data class YouTubeVideoOption(val key: String, val height: Int, val fps: Int, val family: String, val hdr: String? = null) {
    val label: String get() = "${height}p${if (fps > 30) fps.toString() else ""} · ${youtubeFamilyLabel(family)}" +
        when (hdr) { "hdr10" -> " · HDR10"; "hlg" -> " · HLG"; else -> "" }
}

/** A selectable audio language: the original or a dubbed/described track; [kind] is YouTube's `acont`. */
data class YouTubeAudioOption(val key: String, val language: String?, val original: Boolean, val kind: String? = null)

/** Names for YouTube's track kinds in the panel ("Deutsch (synchronisiert)"). */
data class YouTubeAudioKindNames(val original: String, val dubbed: String, val autoDubbed: String, val descriptive: String)

private val YOUTUBE_HEIGHT_STEPS = listOf(720, 1080, 1440, 2160, 4320)

/**
 * "Automatic" quality for players without adaptive bitrate (VLC, MPV): the display's resolution,
 * read from its short side (a portrait phone is 1080 wide), with 5 % tolerance for odd panels
 * (1080×2340, 1440×3200). Never below 720p.
 */
fun youtubeAutoMaxHeight(displayShortSide: Int): Int =
    YOUTUBE_HEIGHT_STEPS.lastOrNull { it <= displayShortSide * 1.05 } ?: YOUTUBE_HEIGHT_STEPS.first()

internal fun youtubeVideoFamily(mime: String?): String? {
    val value = mime?.lowercase() ?: return null
    return when {
        "avc1" in value -> "avc"
        "vp09" in value || "vp9" in value -> "vp9"
        "av01" in value -> "av1"
        else -> null
    }
}

private fun youtubeFamilyLabel(family: String) = when (family) {
    "avc" -> "H.264"
    "vp9" -> "VP9"
    "av1" -> "AV1"
    else -> family.uppercase(Locale.ROOT)
}

/**
 * HDR kind of a video format from its codec string: "hdr10" (PQ, transfer 16) or "hlg"
 * (transfer 18); null for SDR. VP9: `vp09.PP.LL.DD.CC.cp.tc…` (profile 2 is 10-bit),
 * AV1: `av01.P.LLT.DD.M.CCC.cp.tc…`. A 10-bit stream without transfer field counts as HDR10,
 * YouTube's usual HDR format.
 */
fun youtubeHdrKind(mime: String?): String? {
    val codec = mime?.lowercase()?.let { Regex("""(vp09|av01)(\.[0-9a-z]+)+""").find(it)?.value } ?: return null
    val parts = codec.split('.')
    val (bitDepth, transfer) = when (parts[0]) {
        "vp09" -> parts.getOrNull(3) to parts.getOrNull(6)
        else -> parts.getOrNull(3) to parts.getOrNull(7)
    }
    return when (transfer?.toIntOrNull()) {
        16 -> "hdr10"
        18 -> "hlg"
        null -> if (bitDepth?.toIntOrNull() == 10 || (parts[0] == "vp09" && parts.getOrNull(1) == "02")) "hdr10" else null
        else -> null
    }
}

/**
 * The quality step of a video stream: its short side, as YouTube names it. A vertical 1080×1920
 * Short is "1080p" and must not be capped as if it were 1920 lines high.
 */
internal fun YouTubeAdaptiveFormat.qualityHeight(): Int = if (width > 0 && height > 0) minOf(width, height) else height

private fun YouTubeAdaptiveFormat.isVideo() = mimeType?.startsWith("video/", ignoreCase = true) == true
private fun YouTubeAdaptiveFormat.isAudio() = mimeType?.startsWith("audio/", ignoreCase = true) == true
private fun YouTubeAdaptiveFormat.usable() = !isOtf && url?.startsWith("http", ignoreCase = true) == true

/** Video formats this device can play: known codec, SDR, decodable at their size and frame rate. */
internal fun youtubePlayableVideos(formats: List<YouTubeAdaptiveFormat>, decode: YouTubeDecodeCheck): List<YouTubeAdaptiveFormat> =
    formats.filter { format ->
        val family = youtubeVideoFamily(format.mimeType)
        format.isVideo() && format.usable() && family != null &&
            decode(family, format.width.takeIf { it > 0 } ?: (format.height * 16 / 9), format.height, format.fps, youtubeHdrKind(format.mimeType))

    }

private fun familyRank(family: String?) = when (family) { "avc" -> 0; "vp9" -> 1; "av1" -> 2; else -> 3 }

private fun videoKey(format: YouTubeAdaptiveFormat) =
    "v:${format.qualityHeight()}:${format.fps}:${youtubeVideoFamily(format.mimeType)}:${youtubeHdrKind(format.mimeType) ?: "sdr"}"

/** The qualities offered in the player panel, best first. */
fun youtubeVideoOptions(formats: List<YouTubeAdaptiveFormat>, decode: YouTubeDecodeCheck): List<YouTubeVideoOption> =
    youtubePlayableVideos(formats, decode)
        .map { YouTubeVideoOption(videoKey(it), it.qualityHeight(), it.fps, youtubeVideoFamily(it.mimeType)!!, youtubeHdrKind(it.mimeType)) }
        .distinctBy { it.key }
        .sortedWith(
            compareByDescending<YouTubeVideoOption> { it.height }.thenByDescending { it.fps }
                .thenBy { if (it.hdr != null) 0 else 1 }.thenBy { familyRank(it.family) },
        )

/**
 * The video stream for [key]; [YOUTUBE_STREAM_AUTO] takes the best playable stream up to
 * [maxHeight]: highest resolution, then frame rate, then HDR (only offered when the device shows
 * it), then the codec with the widest hardware support. Nothing at or below the cap: the
 * smallest playable stream.
 */
fun youtubePickVideo(formats: List<YouTubeAdaptiveFormat>, key: String, maxHeight: Int, decode: YouTubeDecodeCheck): YouTubeAdaptiveFormat? {
    val playable = youtubePlayableVideos(formats, decode)
    if (key != YOUTUBE_STREAM_AUTO) playable.filter { videoKey(it) == key }.maxByOrNull { it.bitrate }?.let { return it }
    val fitting = playable.filter { it.qualityHeight() in 1..maxHeight }.ifEmpty { playable.sortedBy { it.qualityHeight() }.take(1) }
    return fitting.minWithOrNull(
        compareByDescending<YouTubeAdaptiveFormat> { it.qualityHeight() }
            .thenByDescending { it.fps }
            .thenBy { if (youtubeHdrKind(it.mimeType) != null) 0 else 1 }
            .thenBy { familyRank(youtubeVideoFamily(it.mimeType)) }
            .thenByDescending { it.bitrate },
    )
}

private val XTAG_LANG = Regex("""(?:^|[;&:])lang=([A-Za-z0-9-]+)""")
private val XTAG_ACONT = Regex("""(?:^|[;&:])acont=([A-Za-z-]+)""")
private val AUDIO_TRACK_NAME = Regex("""^\s*([A-Za-z]{2,3}(?:[-_\u2010][A-Za-z0-9]{2,8})*)\s*(?:\(([^)]+)\))?\s*$""")

/**
 * SmartTube names audio tracks `de‐DE (dubbed)` (a U+2010 hyphen, then YouTube's `acont`); the
 * MPD carries it as `lang`, so Media3 reports it (lower-cased) as the track language.
 * Returns the language tag and the kind; null for anything that is not such a name.
 */
fun youtubeParseAudioTrack(raw: String?): Pair<String, String?>? {
    val match = raw?.let(AUDIO_TRACK_NAME::matchEntire) ?: return null
    val tag = match.groupValues[1].replace('\u2010', '-').replace('_', '-')
    return tag to match.groupValues[2].trim().lowercase(Locale.ROOT).takeIf(String::isNotEmpty)
}

private fun YouTubeAdaptiveFormat.audioLanguage(): String? =
    youtubeParseAudioTrack(language)?.first ?: xtags?.let { XTAG_LANG.find(it)?.groupValues?.get(1) }
        ?: audioTrackId?.substringBefore('.')?.takeIf { it.isNotBlank() }

private fun YouTubeAdaptiveFormat.audioKind(): String? =
    xtags?.let { XTAG_ACONT.find(it)?.groupValues?.get(1)?.lowercase(Locale.ROOT) } ?: youtubeParseAudioTrack(language)?.second

/** The original track says so (xtags or track name); a stream without any track tags is the only (original) one. */
private fun YouTubeAdaptiveFormat.originalAudio(): Boolean =
    audioKind() == "original" || xtags?.contains("original", ignoreCase = true) == true ||
        (xtags.isNullOrBlank() && audioTrackId.isNullOrBlank() && audioKind() == null)

private fun audioKey(format: YouTubeAdaptiveFormat) = "a:" + (format.audioTrackId?.takeIf { it.isNotBlank() } ?: format.audioLanguage() ?: "default")

private fun usableAudio(formats: List<YouTubeAdaptiveFormat>) = formats.filter { it.isAudio() && it.usable() && !it.isDrc }

/** Audio languages offered in the panel, original first; a single track offers no choice. */
fun youtubeAudioOptions(formats: List<YouTubeAdaptiveFormat>): List<YouTubeAudioOption> =
    usableAudio(formats)
        .groupBy(::audioKey)
        .map { (key, group) ->
            YouTubeAudioOption(key, group.firstNotNullOfOrNull { it.audioLanguage() }, group.any { it.originalAudio() },
                group.firstNotNullOfOrNull { it.audioKind() })
        }
        .let(::youtubeSortAudioOptions)

/** Original first, then by language. */
fun youtubeSortAudioOptions(options: List<YouTubeAudioOption>): List<YouTubeAudioOption> =
    options.sortedWith(compareBy<YouTubeAudioOption> { if (it.original) 0 else 1 }.thenBy { it.language.orEmpty() }.thenBy { it.kind.orEmpty() })

/** Display label: the language in the user's locale, its kind (original, dubbed, …) in brackets. */
fun youtubeAudioLabel(option: YouTubeAudioOption, displayLocale: Locale, kinds: YouTubeAudioKindNames, fallback: String): String {
    val name = option.language?.let { runCatching { Locale.forLanguageTag(it).getDisplayLanguage(displayLocale) }.getOrNull() }
        ?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.titlecase(displayLocale) } ?: fallback
    val kind = when {
        option.original -> kinds.original
        option.kind == "dubbed-auto" -> kinds.autoDubbed
        option.kind == "dubbed" -> kinds.dubbed
        option.kind == "descriptive" -> kinds.descriptive
        else -> null
    }
    return if (kind != null) "$name ($kind)" else name
}

/** The audio stream for [key]; [YOUTUBE_STREAM_AUTO] takes the original language. AAC before Opus, then bitrate. */
fun youtubePickAudio(formats: List<YouTubeAdaptiveFormat>, key: String): YouTubeAdaptiveFormat? {
    val audio = usableAudio(formats)
    val candidates = (if (key != YOUTUBE_STREAM_AUTO) audio.filter { audioKey(it) == key } else emptyList())
        .ifEmpty { audio.filter { it.originalAudio() } }
        .ifEmpty { audio }
    return candidates.minWithOrNull(
        compareBy<YouTubeAdaptiveFormat> { if (it.mimeType.orEmpty().contains("mp4a", true)) 0 else 1 }
            .thenByDescending { it.bitrate },
    )
}

/**
 * Best video-only and original audio-only URLs for players that open both side by side.
 * [maxHeight] 0 means 1080p; the engine passes the display-based automatic cap instead.
 */
internal fun youtubeAdaptivePair(
    formats: List<YouTubeAdaptiveFormat>,
    maxHeight: Int,
    decode: YouTubeDecodeCheck = youtubeDecodeAnything,
): Pair<String, String>? {
    val video = youtubePickVideo(formats, YOUTUBE_STREAM_AUTO, maxHeight.takeIf { it > 0 } ?: 1080, decode) ?: return null
    val audio = youtubePickAudio(formats, YOUTUBE_STREAM_AUTO) ?: return null
    return (video.url ?: return null) to (audio.url ?: return null)
}

/** A googlevideo parameter, either as query (`?itag=22`) or as path segment (`/itag/22/`). */
private fun queryParam(url: String, name: String): String? =
    Regex("""[?&]${Regex.escape(name)}=([^&#]*)""").find(url)?.groupValues?.get(1)
        ?: Regex("""/${Regex.escape(name)}/([^/?#]*)""").find(url.substringBefore('?'))?.groupValues?.get(1)

/**
 * The fresh URL for the stream an expired googlevideo URL belonged to: same format (`itag`) and,
 * for dubbed audio, same track (`xtags`). Null when the new formats do not offer it any more.
 */
fun youtubeRenewedUrl(expiredUrl: String, candidates: List<String>): String? {
    val itag = queryParam(expiredUrl, "itag") ?: return null
    val xtags = queryParam(expiredUrl, "xtags")
    val matching = candidates.filter { queryParam(it, "itag") == itag && queryParam(it, "xtags") == xtags }
    // The server may hand back the very same (still valid) link; that is a renewal, not a failure.
    return matching.firstOrNull { it != expiredUrl } ?: matching.firstOrNull()
}
