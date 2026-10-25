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

/**
 * A selectable video quality: one resolution/frame rate/codec combination. [hardware] false: no
 * hardware decoder reports this size and frame rate; VLC/MPV may still decode it in software.
 */
data class YouTubeVideoOption(val key: String, val height: Int, val fps: Int, val family: String, val hdr: String? = null, val hardware: Boolean = true) {
    val label: String get() = "${height}p${if (fps > 0 && fps != 30) fps.toString() else ""} · ${youtubeFamilyLabel(family)}" +
        when (hdr) { "hdr10" -> " · HDR10"; "hlg" -> " · HLG"; else -> "" } + if (hardware) "" else " · SW"
}

/**
 * SmartTube's video format presets: the target resolution, frame rate and codec. Automatic
 * selection takes the best stream within the target and prefers its codec.
 */
data class YouTubeVideoPreset(val height: Int, val fps: Int, val family: String) {
    val key: String get() = "$height:$fps:$family"
    val label: String get() = "${height}p$fps · ${youtubeFamilyLabel(family)}"
}

const val YOUTUBE_PRESET_AUTO = "auto"

val YOUTUBE_VIDEO_PRESETS = listOf(
    YouTubeVideoPreset(720, 30, "avc"),
    YouTubeVideoPreset(1080, 30, "avc"),
    YouTubeVideoPreset(1080, 60, "avc"),
    YouTubeVideoPreset(1080, 60, "vp9"),
    YouTubeVideoPreset(1440, 60, "vp9"),
    YouTubeVideoPreset(2160, 60, "vp9"),
    YouTubeVideoPreset(2160, 60, "av1"),
    YouTubeVideoPreset(4320, 60, "av1"),
)

fun youtubeVideoPreset(key: String?): YouTubeVideoPreset? = YOUTUBE_VIDEO_PRESETS.firstOrNull { it.key == key }

/** SmartTube's per-device default (PlayerData.getDefaultVideoFormat): VP9 where the hardware has it. */
fun youtubeDefaultVideoPreset(decode: YouTubeDecodeCheck): YouTubeVideoPreset = when {
    decode("vp9", 3840, 2160, 0, null) -> YouTubeVideoPreset(2160, 60, "vp9")
    decode("vp9", 1920, 1080, 0, null) -> YouTubeVideoPreset(1080, 60, "vp9")
    else -> YouTubeVideoPreset(720, 30, "avc")
}

/**
 * The target for automatic selection: a chosen preset as is, "automatic" SmartTube's device
 * default; both limited by [maxHeight] (the user's cap or the display, 0 = none).
 */
fun youtubeVideoTarget(presetKey: String?, maxHeight: Int, decode: YouTubeDecodeCheck, maxFps: Int = 0): YouTubeVideoPreset {
    val preset = youtubeVideoPreset(presetKey) ?: youtubeDefaultVideoPreset(decode)
    val capped = if (maxHeight > 0 && maxHeight < preset.height) preset.copy(height = maxHeight) else preset
    return if (maxFps > 0 && maxFps < capped.fps) capped.copy(fps = maxFps) else capped
}

/**
 * What the display can show for YouTube: the quality step of its resolution ([youtubeAutoMaxHeight],
 * short side) and the highest frame rate (60 only with a mode of at least 50 Hz at that size).
 */
data class YouTubeDisplayLimit(val height: Int, val maxFps: Int)

/**
 * The display limit under the display-mode policy: the automatic policies switch up to the largest
 * permitted mode ([whitelist], else all), a fixed mode key pins that mode, "system" keeps [current].
 * [canSwitchRate] false (adjustment off) leaves only the reference mode's own refresh rate.
 * Null when no mode is known.
 */
fun youtubeDisplayLimit(
    modes: List<com.ambient.tv.ui.player.SupportedDisplayMode>,
    current: com.ambient.tv.ui.player.SupportedDisplayMode?,
    preferredModeKey: String,
    whitelist: Set<String> = emptySet(),
    canSwitchRate: Boolean = true,
): YouTubeDisplayLimit? {
    val allowed = modes.filter { whitelist.isEmpty() || it.key in whitelist }.ifEmpty { modes }
    val automatic = preferredModeKey == com.ambient.tv.data.local.AppPreferences.DISPLAY_MODE_AUTO_CONTENT ||
        preferredModeKey == com.ambient.tv.data.local.AppPreferences.DISPLAY_MODE_AUTO_DISPLAY
    val reference = when {
        automatic -> allowed.maxWithOrNull(compareBy<com.ambient.tv.ui.player.SupportedDisplayMode>({ it.width.toLong() * it.height }, { it.refreshRate }))
        else -> modes.firstOrNull { it.key == preferredModeKey }
    } ?: current ?: return null
    val rates = if (canSwitchRate || automatic) {
        (allowed.filter { it.width == reference.width && it.height == reference.height } + reference)
    } else listOf(reference)
    val highFps = rates.any { it.refreshRate >= 49.5f }
    return YouTubeDisplayLimit(youtubeAutoMaxHeight(minOf(reference.width, reference.height)), if (highFps) 60 else 30)
}

/**
 * Media3 has no short-side limit: a square box of the [maxQualityHeight]'s 16:9 width (+5 %)
 * keeps landscape, cinemascope and portrait streams of that quality step and drops larger ones.
 */
fun youtubeMedia3MaxVideoSize(maxQualityHeight: Int): Int = (maxQualityHeight * 16 / 9) * 105 / 100

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
 * The quality step of a video stream as YouTube labels it (SmartTube's real height): the short
 * side, or the long side's 16:9 equivalent for wider pictures. A vertical 1080×1920 Short is
 * "1080p", a 2560×1072 cinemascope film "1440p".
 */
internal fun YouTubeAdaptiveFormat.qualityHeight(): Int {
    if (width <= 0 || height <= 0) return height
    val short = minOf(width, height)
    val long = maxOf(width, height)
    return maxOf(short, (long * 9 + 8) / 16)
}

private fun YouTubeAdaptiveFormat.isVideo() = mimeType?.startsWith("video/", ignoreCase = true) == true
private fun YouTubeAdaptiveFormat.isAudio() = mimeType?.startsWith("audio/", ignoreCase = true) == true
private fun YouTubeAdaptiveFormat.usable() = !isOtf && url?.startsWith("http", ignoreCase = true) == true

private fun YouTubeAdaptiveFormat.decodable(decode: YouTubeDecodeCheck): Boolean =
    decode(youtubeVideoFamily(mimeType) ?: "", width.takeIf { it > 0 } ?: (height * 16 / 9), height, fps, youtubeHdrKind(mimeType))

private fun YouTubeAdaptiveFormat.knownVideo() = isVideo() && usable() && youtubeVideoFamily(mimeType) != null

/** Video formats this device can play: known codec, decodable in hardware at their size and frame rate. */
internal fun youtubePlayableVideos(formats: List<YouTubeAdaptiveFormat>, decode: YouTubeDecodeCheck): List<YouTubeAdaptiveFormat> =
    formats.filter { it.knownVideo() && it.decodable(decode) }

/**
 * Formats offered in the player panel: like SmartTube every SDR stream of a known codec, also
 * those no hardware decoder reports (VLC/MPV fall back to software); HDR only where [decode]
 * allows it, since an unconverted HDR picture on an SDR output is wrong.
 */
private fun youtubeListedVideos(formats: List<YouTubeAdaptiveFormat>, decode: YouTubeDecodeCheck): List<YouTubeAdaptiveFormat> =
    formats.filter { it.knownVideo() && (youtubeHdrKind(it.mimeType) == null || it.decodable(decode)) }

/** SmartTube's codec weights (MediaTrack): VP9 before H.264 before AV1. */
private fun codecWeight(family: String?) = when (family) { "vp9" -> 31; "avc" -> 28; "av1" -> 14; else -> 0 }

private fun videoKey(format: YouTubeAdaptiveFormat) =
    "v:${format.qualityHeight()}:${format.fps}:${youtubeVideoFamily(format.mimeType)}:${youtubeHdrKind(format.mimeType) ?: "sdr"}"

/** The qualities offered in the player panel, best first. */
fun youtubeVideoOptions(formats: List<YouTubeAdaptiveFormat>, decode: YouTubeDecodeCheck, limit: YouTubeDisplayLimit? = null): List<YouTubeVideoOption> =
    youtubeListedVideos(formats, decode).filter { it.fits(limit) }
        .map { YouTubeVideoOption(videoKey(it), it.qualityHeight(), it.fps, youtubeVideoFamily(it.mimeType)!!, youtubeHdrKind(it.mimeType), it.decodable(decode)) }
        .groupBy { it.key }.map { (_, same) -> same.firstOrNull { it.hardware } ?: same.first() }
        .sortedWith(
            compareByDescending<YouTubeVideoOption> { it.height }.thenByDescending { it.fps }
                .thenBy { if (it.hdr != null) 0 else 1 }.thenByDescending { codecWeight(it.family) },
        )

// SmartTube's VideoTrack comparison: 5 % size and 10 fps tolerance, frame rates below 10 count as 30.
private fun sizeEquals(a: Int, b: Int) = kotlin.math.abs(a - b) < a / 100 * 5
private fun sizeLess(a: Int, b: Int) = !sizeEquals(a, b) && a < b
private fun effectiveFps(fps: Int) = if (fps < 10) 30 else fps
private fun fpsLess(a: Int, b: Int) = kotlin.math.abs(a - b) >= 10 && a < b

/** Within the target: not larger (5 %) and not faster (10 fps) than it. */
private fun YouTubeAdaptiveFormat.within(target: YouTubeVideoPreset) =
    !sizeLess(target.height, qualityHeight()) && !fpsLess(target.fps, effectiveFps(fps))

/** Within the display's [limit] (null = no limit). */
private fun YouTubeAdaptiveFormat.fits(limit: YouTubeDisplayLimit?) =
    limit == null || (!sizeLess(limit.height, qualityHeight()) && !fpsLess(limit.maxFps, effectiveFps(fps)))

/** HDR 3 points, frame rate 2, resolution 1; a tie within one codec goes to the higher bitrate. */
private fun compareVideo(a: YouTubeAdaptiveFormat, b: YouTubeAdaptiveFormat): Int {
    var score = 0
    val hdrA = youtubeHdrKind(a.mimeType) != null
    val hdrB = youtubeHdrKind(b.mimeType) != null
    if (hdrA != hdrB) score += if (hdrA) 3 else -3
    if (fpsLess(effectiveFps(b.fps), effectiveFps(a.fps))) score += 2 else if (fpsLess(effectiveFps(a.fps), effectiveFps(b.fps))) score -= 2
    if (sizeLess(b.qualityHeight(), a.qualityHeight())) score += 1 else if (sizeLess(a.qualityHeight(), b.qualityHeight())) score -= 1
    if (score == 0 && youtubeVideoFamily(a.mimeType) == youtubeVideoFamily(b.mimeType)) return a.bitrate.compareTo(b.bitrate)
    return score
}

/**
 * The video stream for [key]; [YOUTUBE_STREAM_AUTO] works like SmartTube's track selector: among
 * the hardware-decodable streams within [target], the target's codec if it has any; the best
 * of them by HDR, frame rate and resolution; equal ones by codec (VP9, H.264, AV1). Nothing
 * within the target: the smallest playable stream.
 */
fun youtubePickVideo(
    formats: List<YouTubeAdaptiveFormat>, key: String, target: YouTubeVideoPreset, decode: YouTubeDecodeCheck,
    limit: YouTubeDisplayLimit? = null,
): YouTubeAdaptiveFormat? {
    // An explicit choice above the display's limit (a remembered 4K pick on a 1080p display) falls back to automatic.
    if (key != YOUTUBE_STREAM_AUTO) {
        youtubeListedVideos(formats, decode).filter { videoKey(it) == key && it.fits(limit) }
            .maxWithOrNull(compareBy<YouTubeAdaptiveFormat> { it.decodable(decode) }.thenBy { it.bitrate })?.let { return it }
    }
    val playable = youtubePlayableVideos(formats, decode)
    val within = playable.filter { it.within(target) }
    if (within.isEmpty()) return playable.minWithOrNull(compareBy<YouTubeAdaptiveFormat> { it.qualityHeight() }.thenByDescending { codecWeight(youtubeVideoFamily(it.mimeType)) })
    val candidates = within.filter { youtubeVideoFamily(it.mimeType) == target.family }.ifEmpty { within }
    return candidates.reduce { best, next ->
        val compared = compareVideo(next, best)
        if (compared > 0 || (compared == 0 && codecWeight(youtubeVideoFamily(next.mimeType)) > codecWeight(youtubeVideoFamily(best.mimeType)))) next else best
    }
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
 * Best video-only and original audio-only URLs for players that open both side by side, for the
 * SmartTube-style [target] (resolution, frame rate, codec).
 */
internal fun youtubeAdaptivePair(
    formats: List<YouTubeAdaptiveFormat>,
    target: YouTubeVideoPreset,
    decode: YouTubeDecodeCheck = youtubeDecodeAnything,
): Pair<String, String>? {
    val video = youtubePickVideo(formats, YOUTUBE_STREAM_AUTO, target, decode) ?: return null
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
