package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AudioCatalog
import com.ambient.tv.data.local.AudioOptions

/** What the current Android route actually confirmed. Unconfirmed formats are absent. */
data class AudioRouteCapabilities(
    val passthrough: Set<String> = emptySet(),
    val pcmChannels: Int = 2,
    val mpvAvailable: Boolean = false,
    val profile: String = "tv",
)

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
)

/** Maps container codec names onto the five bitstream keys the route probe understands. */
fun normalizeSourceCodec(codec: String?): String? {
    val value = codec?.trim()?.lowercase()?.replace('_', '-') ?: return null
    return when {
        value in setOf("truehd", "mlp") || value.contains("truehd") -> "truehd"
        value in setOf("dts-hd", "dtshd", "dca-ma", "dts-hra") || value.startsWith("dts-hd") -> "dts-hd"
        value in setOf("dts", "dca") -> "dts"
        value.contains("eac3") || value.contains("e-ac-3") || value in setOf("ec3", "ec-3", "atmos") || value.contains("joc") -> "eac3"
        value in setOf("ac3", "ac-3") -> "ac3"
        else -> null
    }
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
        "mono", "left", "right", "stereo", "auto" -> requested
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
): AudioOutputPlan {
    val requested = options.normalized()
    val blocked = rejectedCodecs.mapNotNull(::normalizeSourceCodec).toSet()
    val liveRoute = route.copy(passthrough = route.passthrough - blocked)
    val permitted = requested.permitsBitstream(sourcePassthrough)
    val confirmed = AudioCatalog.codecs.filter { it in requested.allowedCodecs && it in liveRoute.passthrough }
    val source = normalizeSourceCodec(sourceCodec)
    val mpv = backend == "mpv" || liveRoute.mpvAvailable
    val channels = clipChannelMode(requested.channels, liveRoute.pcmChannels)

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
    val corePossible = requested.localFallback == "auto" && permitted && source == "dts-hd" &&
        "dts" in confirmed && "dts-hd" !in confirmed && mpv &&
        (requested.output == "bitstream" || liveRoute.pcmChannels < 6)
    if (corePossible) {
        return AudioOutputPlan(
            mode = AudioOutputMode.DTS_CORE,
            spdifCodecs = confirmed,
            encoded = true,
            needsMpv = backend != "mpv",
            channels = "auto",
            applyDsp = false,
        )
    }

    // PCM preserves the decoded channels without another lossy encode. AC3 is useful for
    // routes such as ARC/SPDIF that accept compressed surround but only stereo PCM.
    val surroundSource = sourceChannels?.let { it > 2 } ?: (source != null)
    val ac3Allowed = requested.localFallback == "auto" && requested.output != "pcm" &&
        requested.channels !in setOf("mono", "left", "right", "stereo")
    val encodePossible = ac3Allowed && "ac3" in confirmed && mpv && surroundSource &&
        (requested.localAc3 || liveRoute.pcmChannels < 6) && (!permitted || source !in confirmed)
    if (encodePossible) {
        return AudioOutputPlan(
            mode = AudioOutputMode.LOCAL_AC3,
            spdifCodecs = emptyList(),
            encoded = true,
            needsMpv = backend != "mpv",
            channels = "5.1",
            applyDsp = true,
            heavyAudio = true,
        )
    }

    val dsp = requested.eqEnabled || requested.dialogue > 0 || requested.night || requested.normalize || requested.upmix
    return AudioOutputPlan(
        mode = AudioOutputMode.PCM,
        channels = channels,
        applyDsp = true,
        needsMpv = localEnabled && backend in setOf("auto", "media3") && !media3CanDecode && liveRoute.mpvAvailable,
        heavyAudio = dsp,
    )
}

fun visibleBitstreamCodecs(route: AudioRouteCapabilities): List<String> =
    AudioCatalog.codecs.filter { it in route.passthrough }

fun visibleChannelModes(route: AudioRouteCapabilities): List<String> = buildList {
    add("auto")
    add("stereo")
    add("mono")
    add("left")
    add("right")
    if (route.pcmChannels >= 6) add("5.1")
    if (route.pcmChannels >= 8) add("7.1")
}

/** Codecs the current playback already saw rejected. Cleared when a new item starts. */
object AudioCodecRejects {
    private val rejected = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Bumped on every change so per-buffer callers can skip re-planning cheaply. */
    @Volatile var version = 0
        private set

    fun clear() {
        rejected.clear()
        version++
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
