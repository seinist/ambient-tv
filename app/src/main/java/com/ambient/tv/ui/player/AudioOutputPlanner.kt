package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AudioCatalog
import com.ambient.tv.data.local.AudioOptions

/** What the current Android route actually confirmed. Unconfirmed formats are absent. */
data class AudioRouteCapabilities(
    val passthrough: Set<String> = emptySet(),
    val pcmChannels: Int = 2,
    val mpvAvailable: Boolean = false,
    val profile: String = "tv",
    /** AudioDeviceInfo types behind this route, routed device first; display and logs only. */
    val outputTypes: List<Int> = emptyList(),
)

/** Bitstream formats "Passthrough erzwingen" may try without the output confirming them. Lossless
 * formats (TrueHD, DTS-HD MA) are excluded: they need HDMI bandwidth a forced guess cannot assume. */
internal val FORCED_BITSTREAM_CODECS = setOf("ac3", "eac3", "dts")

/**
 * The route as the user's forcing settings see it: PCM channels 5.1/7.1 raise the reported PCM
 * width, "Passthrough erzwingen" adds the common Dolby/DTS formats. Otherwise unchanged.
 */
fun AudioRouteCapabilities.forcedBy(options: AudioOptions): AudioRouteCapabilities {
    val forcedPassthrough = if (options.forcesPassthrough()) FORCED_BITSTREAM_CODECS.filter { it in options.allowedCodecs } else emptyList()
    val channels = options.forcedChannels(pcmChannels)
    if (forcedPassthrough.all { it in passthrough } && channels == pcmChannels) return this
    return copy(pcmChannels = channels, passthrough = passthrough + forcedPassthrough.filterNot { it in passthrough })
}

/** With a forced layout, "auto" becomes an explicit layout so MPV's auto-safe cannot fall back
 * to the reported stereo. Never invents channels the source does not have. */
internal fun forcedPcmLayout(sourceChannels: Int?, channels: Int): String = when {
    (sourceChannels ?: 0) >= 7 && channels >= 8 -> "7.1"
    (sourceChannels ?: 0) > 2 && channels >= 6 -> "5.1"
    else -> "auto"
}

enum class AudioOutputMode {
    PASSTHROUGH,
    DTS_CORE,
    LOCAL_AC3,
    PCM,
}

data class AudioOutputPlan(
    val mode: AudioOutputMode,
    val spdifCodecs: List<String> = emptyList(),
    val encoded: Boolean = false,
    val needsMpv: Boolean = false,
    val channels: String = "auto",
    val applyDsp: Boolean = true,
    val heavyAudio: Boolean = false,
    val pcmLimitFallback: Boolean = false,
)

/**
 * Maps container codec names onto the five bitstream keys the route probe understands.
 * Includes VLC's FourCCs ("a52 ", "eac3", "dts ", "trhd", "mlp ") and its descriptions
 * ("A52 Audio (aka AC3)", "A/52 B Audio (aka E-AC3)", "DTS Audio", "TrueHD Audio").
 */
fun normalizeSourceCodec(codec: String?): String? {
    val raw = codec?.trim()?.lowercase()?.replace('_', '-') ?: return null
    val value = vlcCodecAlias(raw) ?: raw
    return when {
        value in setOf("truehd", "mlp") || value.contains("truehd") -> "truehd"
        value in setOf("dts-hd", "dtshd", "dca-ma", "dts-hra") || value.startsWith("dts-hd") -> "dts-hd"
        value in setOf("dts", "dca") -> "dts"
        value.contains("eac3") || value.contains("e-ac-3") || value in setOf("ec3", "ec-3", "atmos") || value.contains("joc") -> "eac3"
        value in setOf("ac3", "ac-3") -> "ac3"
        else -> null
    }
}

/** VLC names the Dolby/DTS formats differently from FFmpeg and the media servers. */
private fun vlcCodecAlias(value: String): String? = when {
    value == "trhd" || value.startsWith("truehd audio") -> "truehd"
    value == "mlp" || value.startsWith("mlp audio") -> "mlp"
    value.contains("aka e-ac3") || value.startsWith("a/52 b") || value.startsWith("a52 b") -> "eac3"
    value == "a52" || value == "a/52" || value.contains("aka ac3") || value.startsWith("a52 audio") ||
        value.startsWith("a/52 audio") -> "ac3"
    value == "dts audio" -> "dts"
    else -> null
}

fun clipChannelMode(requested: String, pcmChannels: Int): String {
    val limit = pcmChannels.coerceAtLeast(2)
    return when (requested) {
        "7.1" -> when {
            limit >= 8 -> "7.1"
            limit >= 6 -> "5.1"
            else -> "stereo"
        }
        "5.1" -> if (limit >= 6) "5.1" else "stereo"
        "auto" -> if (limit < 6) "stereo" else "auto"
        "mono", "left", "right", "stereo" -> requested
        else -> "auto"
    }
}

/** PCM channel mode for Media3's local FFmpeg decode path, clipped to the route. */
fun localDecodePcmChannelMode(requested: String, pcmChannels: Int): String = clipChannelMode(requested, pcmChannels)

/**
 * Picks one local output. Passthrough, DTS core and local AC3 are exclusive.
 * Codecs missing from [route] are neither selected nor left in the SPDIF list.
 * The media URL is not involved.
 */
fun planAudioOutput(
    options: AudioOptions,
    sourceCodec: String?,
    route: AudioRouteCapabilities,
    backend: String,
    sourcePassthrough: Boolean,
    media3CanDecode: Boolean = true,
    rejectedCodecs: Set<String> = emptySet(),
    sourceChannels: Int? = null,
    /** The current DTS-HD stream has no core frame (lossless only); it cannot become DTS core. */
    dtsCoreMissing: Boolean = false,
    media3LocalAvailable: Boolean = false,
): AudioOutputPlan {
    val requested = options.normalized()
    val blocked = rejectedCodecs.mapNotNull(::normalizeSourceCodec).toSet()
    val forcedRoute = route.forcedBy(requested)
    // MPV sends bitstreams as IEC 61937. A TV's own speakers accept Dolby only as a direct
    // E-AC3/AC3 track (Media3), so MPV's packets stall the output: picture frozen, no sound.
    val iecUnsupported = backend == "mpv" && !requested.forcesPassthrough() &&
        route.outputTypes.isNotEmpty() && route.outputTypes.all { it == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    val liveRoute = forcedRoute.copy(passthrough = if (iecUnsupported) emptySet() else forcedRoute.passthrough - blocked)
    val forcing = forcedRoute.pcmChannels > route.pcmChannels
    val permitted = requested.permitsBitstream(sourcePassthrough)
    val confirmed = AudioCatalog.codecs.filter { it in requested.allowedCodecs && it in liveRoute.passthrough }
    val source = normalizeSourceCodec(sourceCodec)
    val mpv = backend == "mpv" || liveRoute.mpvAvailable
    // Media3 sends the DTS core itself (LocalAudioRenderer strips the DTS-HD extension).
    val media3Core = backend in setOf("auto", "media3")
    val pcmMode = if (requested.channels == "auto") when (requested.pcmChannelLimit) {
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "auto"
    } else requested.channels
    val clipped = clipChannelMode(pcmMode, requested.pcmChannels(liveRoute.pcmChannels))
    val channels = if (forcing && clipped == "auto") forcedPcmLayout(sourceChannels, requested.pcmChannels(liveRoute.pcmChannels)) else clipped

    if (permitted && source != null && source in confirmed) {
        return AudioOutputPlan(
            mode = AudioOutputMode.PASSTHROUGH,
            spdifCodecs = confirmed,
            encoded = true,
            channels = "auto",
            applyDsp = false,
        )
    }

    val localEnabled = requested.localFallback != "off"
    val corePossible = requested.localFallback == "auto" && permitted && source == "dts-hd" && !dtsCoreMissing &&
        "dts" in confirmed && "dts-hd" !in confirmed && (mpv || media3Core)
    if (corePossible) {
        return AudioOutputPlan(
            mode = AudioOutputMode.DTS_CORE,
            spdifCodecs = confirmed,
            encoded = true,
            needsMpv = backend != "mpv" && !media3Core,
            channels = "auto",
            applyDsp = false,
        )
    }

    // PCM preserves the decoded channels without another lossy encode. AC3 is useful for
    // routes such as ARC/SPDIF that accept compressed surround but only stereo PCM.
    val nativeAc3 = media3Core && media3LocalAvailable
    val encodePossible = requested.transcodesAc3(sourcePassthrough, sourceChannels,
        liveRoute.pcmChannels, "ac3" in confirmed) && (mpv || nativeAc3)
    if (encodePossible) {
        return AudioOutputPlan(
            mode = AudioOutputMode.LOCAL_AC3,
            spdifCodecs = emptyList(),
            encoded = true,
            needsMpv = backend != "mpv" && !nativeAc3,
            channels = "5.1",
            applyDsp = !nativeAc3,
            heavyAudio = true,
        )
    }

    // VLC's Android sink offers only unrestricted PCM or stereo, not a 5.1 cap.
    // Honour an inherited cap safely instead of advertising a limit the sink ignores.
    val vlcPcmLimit = backend == "vlc" && channels == "5.1" && liveRoute.pcmChannels > 6
    val dsp = requested.eqEnabled || requested.dialogue > 0 || requested.night || requested.normalize || requested.upmix
    return AudioOutputPlan(
        mode = AudioOutputMode.PCM,
        channels = if (vlcPcmLimit) "stereo" else channels,
        pcmLimitFallback = vlcPcmLimit,
        applyDsp = true,
        needsMpv = localEnabled && backend in setOf("auto", "media3") && !media3CanDecode &&
            !media3LocalAvailable && liveRoute.mpvAvailable,
        heavyAudio = dsp,
    )
}

fun visibleBitstreamCodecs(route: AudioRouteCapabilities): List<String> =
    AudioCatalog.codecs.filter { it in route.passthrough }

/** Channel mix choices. 5.1/7.1 live in "PCM-Kanäle" only (one setting per purpose). */
@Suppress("UNUSED_PARAMETER")
fun visibleChannelModes(route: AudioRouteCapabilities): List<String> = listOf("auto", "stereo", "mono", "left", "right")

/** Codecs the current playback already saw rejected. Cleared when a new item starts. */
object AudioCodecRejects {
    private val rejected = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Bumped on every change so per-buffer callers can skip re-planning cheaply. */
    @Volatile var version = 0
        private set

    /** Set when a DTS-HD stream of the current item turned out to carry no DTS core. */
    @Volatile var dtsCoreMissing = false
        private set

    fun clear() {
        rejected.clear()
        dtsCoreMissing = false
        version++
    }

    /** The output is back: codecs it rejected may be tried again. A missing DTS core is a source fact and stays. */
    fun clearOutputRejects() {
        if (rejected.isEmpty()) return
        rejected.clear()
        version++
    }

    fun markDtsCoreMissing() {
        if (!dtsCoreMissing) { dtsCoreMissing = true; version++ }
    }

    fun reject(codec: String?) {
        normalizeSourceCodec(codec)?.let { if (rejected.add(it)) version++ }
    }

    fun snapshot(): Set<String> = rejected.toSet()
}

/** One automatic MPV hand-off per stream, so a failed MPV init cannot bounce forever. */
object AudioEngineRedirectGuard {
    private var consumedKey: Any? = null

    fun reset() { consumedKey = null }

    fun once(key: Any): Boolean {
        if (consumedKey == key) return false
        consumedKey = key
        return true
    }
}
