package com.ambient.tv.livetv

import android.content.Context
import android.media.MediaCodecList
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.ui.player.AudioPassthroughPolicy
import com.ambient.tv.ui.player.AudioRouteCapabilities
import com.ambient.tv.ui.player.backend.MpvBackendAvailability
import kotlinx.serialization.json.*

/** Transport capabilities, before the local output/downmix/AC3 plan is applied. */
internal data class LiveTvAudioProfile(val codecs: List<String>, val maxChannels: Int) {
    fun browserDeviceProfile(): JsonObject = buildJsonObject {
        put("Name", "Ambient TV Live")
        put("MaxStreamingBitrate", 120_000_000)
        putJsonArray("DirectPlayProfiles") { addJsonObject {
            put("Type", "Video"); put("Container", "ts,mpegts,mp4,mkv")
            put("VideoCodec", "h264"); put("AudioCodec", codecs.joinToString(","))
        } }
        putJsonArray("TranscodingProfiles") { addJsonObject {
            put("Type", "Video"); put("Context", "Streaming"); put("Protocol", "hls")
            put("Container", "ts"); put("VideoCodec", "h264"); put("AudioCodec", codecs.joinToString(","))
            put("MaxAudioChannels", maxChannels.toString()); put("MinSegments", 2); put("BreakOnNonKeyFrames", true)
        } }
    }

    fun plexQuery(): Map<String, String> = mapOf(
        "audioCodec" to codecs.joinToString(",") { if (it == "dts") "dca" else it },
        "maxAudioChannels" to maxChannels.toString(),
        "directPlay" to "1", "directStream" to "1",
    )
}

internal fun planLiveTvAudioProfile(options: AudioOptions, sourcePassthrough: Boolean, backend: String, route: AudioRouteCapabilities, decoderChannels: Map<String, Int>): LiveTvAudioProfile {
    val nativeDecode = backend in setOf("mpv", "vlc") || options.localFallback != "off" && route.mpvAvailable
    // Keep all original channels when a native local decoder/fallback can handle them.
    // Media3-only devices advertise their decoder limit instead of assuming stereo or 7.1.
    val codecs = buildList {
        listOf("aac", "ac3", "eac3", "dts", "truehd", "mp3", "mp2", "flac", "opus").filterTo(this) {
            nativeDecode || it in decoderChannels || options.permitsBitstream(sourcePassthrough) && it in route.passthrough && it in options.allowedCodecs
        }
        // DTS core is not DTS-HD. Advertise HD when a native decoder can play it,
        // or when the route and the allowed list both name dts-hd.
        if (nativeDecode || "dts-hd" in route.passthrough && "dts-hd" in options.allowedCodecs) {
            val afterDts = indexOf("dts")
            add(if (afterDts >= 0) afterDts + 1 else size, "dts-hd")
        }
    }
    return LiveTvAudioProfile(codecs.ifEmpty { listOf("aac") }, if (nativeDecode) 8 else (decoderChannels["aac"] ?: 2).coerceIn(2, 8))
}

internal fun liveTvAudioProfile(context: Context, preferences: AppPreferences): LiveTvAudioProfile {
    val route = AudioPassthroughPolicy.probeCached(context).copy(mpvAvailable = MpvBackendAvailability.isAvailable)
    return planLiveTvAudioProfile(preferences.audioSettings.currentForRoute(route.profile), preferences.mediaAudioPassthroughEnabled,
        preferences.tvheadendPlaybackBackendKey, route, platformAudioDecoders)
}

private val platformAudioDecoders: Map<String, Int> by lazy {
    val types = mapOf("aac" to "audio/mp4a-latm", "ac3" to "audio/ac3", "eac3" to "audio/eac3",
        "dts" to "audio/vnd.dts", "truehd" to "audio/true-hd", "mp3" to "audio/mpeg",
        "mp2" to "audio/mpeg-L2", "flac" to "audio/flac", "opus" to "audio/opus")
    val decoders = runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filterNot { it.isEncoder } }.getOrDefault(emptyList())
    buildMap { types.forEach { (codec, mime) ->
        decoders.filter { info -> info.supportedTypes.any { it.equals(mime, true) } }.mapNotNull {
            runCatching { it.getCapabilitiesForType(mime).audioCapabilities?.maxInputChannelCount }.getOrNull()
        }.maxOrNull()?.let { put(codec, it) }
    } }
}
