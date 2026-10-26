package com.ambient.tv.data.remote

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.network.NetworkEndpoint
import javax.inject.Inject
import java.util.UUID
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrl

@Singleton
class PlexUrlBuilder @Inject constructor(
    private val appPreferences: AppPreferences,
    private val tokenStore: TokenStore,
    private val performanceProfileResolver: com.ambient.tv.ui.performance.PerformanceProfileResolver,
) {
    fun absoluteUrl(relativePath: String?): String? {
        if (relativePath.isNullOrBlank()) return null
        // Cast/actor thumbnails (and some other agent-sourced artwork) come back as full URLs
        // already, e.g. to Plex's own metadata CDN - not as a path on the local server. Treating
        // one of those as a relative path prepended it with the local host, producing a broken
        // URL like "http://<local-host>:<port>/https://metadata-static.plex.tv/...".
        if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) return relativePath
        val host = allowedHost() ?: return null
        val path = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
        val token = tokenStore.token?.takeIf { it.isNotBlank() }
        val tokenParam = token?.let { "${if (path.contains('?')) "&" else "?"}X-Plex-Token=$it" }.orEmpty()
        return "${scheme()}://${NetworkEndpoint.authorityHost(host)}:${appPreferences.port}$path$tokenParam"
    }


    fun artworkUrl(relativePath: String?, width: Int, height: Int): String? {
        if (relativePath.isNullOrBlank()) return null
        if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) return relativePath
        val host = allowedHost() ?: return null
        val path = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
        val token = tokenStore.token?.takeIf { it.isNotBlank() }
        return "${scheme()}://${NetworkEndpoint.authorityHost(host)}:${appPreferences.port}/photo/:/transcode"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("width", width.coerceAtLeast(64).toString())
            .addQueryParameter("height", height.coerceAtLeast(64).toString())
            .addQueryParameter("minSize", "1")
            .addQueryParameter("upscale", "1")
            .addQueryParameter("url", path)
            .apply { if (token != null) addQueryParameter("X-Plex-Token", token) }
            .build()
            .toString()
    }

    /**
     * Photo for full-screen display: fitted inside width x height and never enlarged (artworkUrl
     * covers the box and upscales, which makes portrait photos far larger than the screen).
     * The transcoder also converts HEIC/RAW and applies the EXIF orientation.
     */
    fun photoUrl(relativePath: String?, width: Int, height: Int): String? {
        if (relativePath.isNullOrBlank()) return null
        if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) return relativePath
        val host = allowedHost() ?: return null
        val path = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
        val token = tokenStore.token?.takeIf { it.isNotBlank() }
        return "${scheme()}://${NetworkEndpoint.authorityHost(host)}:${appPreferences.port}/photo/:/transcode"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("width", width.coerceAtLeast(64).toString())
            .addQueryParameter("height", height.coerceAtLeast(64).toString())
            .addQueryParameter("minSize", "0")
            .addQueryParameter("upscale", "0")
            .addQueryParameter("quality", "90")
            .addQueryParameter("url", path)
            .apply { if (token != null) addQueryParameter("X-Plex-Token", token) }
            .build()
            .toString()
    }

    fun posterUrl(path: String?): String? = if (performanceProfileResolver.effectiveHighQualityAssets()) {
        artworkUrl(path, 1080, 1620)
    } else {
        artworkUrl(path, 360, 540)
    }

    fun backdropUrl(path: String?): String? = if (performanceProfileResolver.effectiveHighQualityAssets()) {
        // The UI window, not the panel mode, decides what is visible: a 4K backdrop on a 1080p
        // window costs four times the decode memory for no extra detail.
        val (width, height) = plexBackdropSize(android.content.res.Resources.getSystem().displayMetrics.let {
            maxOf(it.widthPixels, it.heightPixels)
        })
        artworkUrl(path, width, height)
    } else {
        artworkUrl(path, 1280, 720)
    }

    fun avatarUrl(path: String?): String? = if (performanceProfileResolver.effectiveHighQualityAssets()) {
        artworkUrl(path, 768, 768)
    } else {
        artworkUrl(path, 256, 256)
    }

    fun mediaThumbUrl(path: String?, type: String?): String? {
        val highQuality = performanceProfileResolver.effectiveHighQualityAssets()
        return when (type) {
            "episode", "clip" -> if (highQuality) artworkUrl(path, 1920, 1080) else artworkUrl(path, 480, 270)
            "artist", "album", "track", "audio" -> if (highQuality) artworkUrl(path, 1080, 1080) else artworkUrl(path, 360, 360)
            "photo" -> if (highQuality) artworkUrl(path, 1600, 1200) else artworkUrl(path, 480, 360)
            else -> posterUrl(path)
        }
    }

    /**
     * Plex Universal Transcoder URL for the narrow fallback we want on TV: keep the video
     * eligible for Direct Stream, but do not copy the source audio. The target audio codec is AC3
     * (5.1 kept) when the device can play AC3, otherwise AAC, so incompatible DTS/TrueHD/E-AC3
     * on a device/emulator does not force a video re-encode as well.
     *
     * Plex's universal-transcoder API is not a public stable API, so this is intentionally kept
     * in the Plex data adapter rather than leaking into UI/domain code.
     */
    fun audioFallbackUrl(
        ratingKey: String,
        mediaIndex: Int,
        startPositionMs: Long,
        audioStreamId: Long? = null,
        forcedSubtitleStreamId: Long? = null,
        audioCodec: String = "aac",
        audioChannels: Int = if (audioCodec == "aac") 2 else 6,
    ): String? {
        val host = allowedHost() ?: return null
        val token = tokenStore.token?.takeIf { it.isNotBlank() }
        val targetAudio = if (audioCodec == "ac3" || audioCodec == "eac3") audioCodec else "aac"
        val channelLimit = serverAudioChannelLimit(targetAudio, audioChannels)
        // An HTTP/Matroska direct-stream target lets Plex copy HEVC/H.264 video unchanged while
        // converting only the unsupported audio track. The Generic HLS target rejects some HEVC
        // sources outright (HTTP 400); Plex's own TV/Kodi clients use the Chrome profile and the
        // universal start.mkv endpoint for this same remux path. The Chrome profile caps
        // transcoded audio at stereo; lift it to the output width (AAC up to 7.1, AC3/E-AC3 5.1).
        val profileExtra =
            "add-transcode-target(type=videoProfile&context=streaming&protocol=http&container=mkv&videoCodec=hevc,h264&audioCodec=$targetAudio&replace=true)" +
                if (channelLimit > 2) "+add-limitation(scope=videoAudioCodec&scopeName=$targetAudio&type=upperBound&name=audio.channels&value=$channelLimit&replace=true)" else ""

        return "${scheme()}://${NetworkEndpoint.authorityHost(host)}:${appPreferences.port}/video/:/transcode/universal/start.mkv"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("hasMDE", "1")
            .addQueryParameter("path", "/library/metadata/$ratingKey")
            .addQueryParameter("mediaIndex", mediaIndex.toString())
            .addQueryParameter("partIndex", "0")
            .addQueryParameter("protocol", "http")
            .addQueryParameter("directPlay", "0")
            .addQueryParameter("directStream", "1")
            // Keep video eligible for stream-copy but force an audio conversion target.
            .addQueryParameter("directStreamAudio", "0")
            .addQueryParameter("fastSeek", "1")
            .addQueryParameter("copyts", "1")
            .addQueryParameter("subtitles", if (forcedSubtitleStreamId != null) "auto" else "none")
            .apply {
                if (audioStreamId != null) addQueryParameter("audioStreamID", audioStreamId.toString())
                if (forcedSubtitleStreamId != null) addQueryParameter("subtitleStreamID", forcedSubtitleStreamId.toString())
            }
            // Plex validates videoQuality as a 0..99 value even when the video is only
            // stream-copied. Sending 100 makes the universal-transcoder endpoint reject the
            // complete request with HTTP 400 before it can create the audio-only fallback.
            .addQueryParameter("videoQuality", "99")
            .addQueryParameter("maxVideoBitrate", "200000")
            .addQueryParameter("location", transcodeLocation(host))
            .addQueryParameter("audioBoost", "100")
            .addQueryParameter("mediaBufferSize", "102400")
            .addQueryParameter("offset", (startPositionMs.coerceAtLeast(0L) / 1000.0).toString())
            .addQueryParameter("session", UUID.randomUUID().toString())
            .addQueryParameter("X-Plex-Client-Profile-Name", "Chrome")
            .addQueryParameter("X-Plex-Client-Profile-Extra", profileExtra)
            .addQueryParameter("X-Plex-Client-Identifier", appPreferences.clientIdentifier)
            .addQueryParameter("X-Plex-Product", com.ambient.tv.branding.AppBrand.name)
            .addQueryParameter("X-Plex-Platform", "Android TV")
            .apply { if (token != null) addQueryParameter("X-Plex-Token", token) }
            .build()
            .toString()
    }

    /** Full compatibility fallback: H.264 video + AAC audio over HLS. */
    fun videoFallbackUrl(
        ratingKey: String,
        mediaIndex: Int,
        startPositionMs: Long,
        audioStreamId: Long? = null,
        forcedSubtitleStreamId: Long? = null,
    ): String? {
        val host = allowedHost() ?: return null
        val token = tokenStore.token?.takeIf { it.isNotBlank() }
        val profileExtra = listOf(
            "append-transcode-target-codec(type=videoProfile&context=streaming&protocol=hls&videoCodec=h264&audioCodec=aac)",
            "append-transcode-target-video-codec(type=videoProfile&context=streaming&protocol=hls&videoCodec=h264)",
            "append-transcode-target-audio-codec(type=videoProfile&context=streaming&protocol=hls&audioCodec=aac)",
        ).joinToString("+")

        return "${scheme()}://${NetworkEndpoint.authorityHost(host)}:${appPreferences.port}/video/:/transcode/universal/start.m3u8"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("hasMDE", "1")
            .addQueryParameter("path", "/library/metadata/$ratingKey")
            .addQueryParameter("mediaIndex", mediaIndex.toString())
            .addQueryParameter("partIndex", "0")
            .addQueryParameter("protocol", "hls")
            .addQueryParameter("directPlay", "0")
            .addQueryParameter("directStream", "0")
            .addQueryParameter("fastSeek", "1")
            .addQueryParameter("copyts", "1")
            .addQueryParameter("subtitles", if (forcedSubtitleStreamId != null) "auto" else "none")
            .apply {
                if (audioStreamId != null) addQueryParameter("audioStreamID", audioStreamId.toString())
                if (forcedSubtitleStreamId != null) addQueryParameter("subtitleStreamID", forcedSubtitleStreamId.toString())
            }
            .addQueryParameter("videoQuality", "99")
            .addQueryParameter("maxVideoBitrate", "200000")
            .addQueryParameter("location", transcodeLocation(host))
            .addQueryParameter("audioBoost", "100")
            .addQueryParameter("mediaBufferSize", "102400")
            .addQueryParameter("offset", (startPositionMs.coerceAtLeast(0L) / 1000.0).toString())
            .addQueryParameter("session", UUID.randomUUID().toString())
            .addQueryParameter("videoResolution", "3840x2160")
            .addQueryParameter("X-Plex-Client-Profile-Name", "generic")
            .addQueryParameter("X-Plex-Client-Profile-Extra", profileExtra)
            .addQueryParameter("X-Plex-Client-Identifier", appPreferences.clientIdentifier)
            .addQueryParameter("X-Plex-Product", com.ambient.tv.branding.AppBrand.name)
            .addQueryParameter("X-Plex-Platform", "Android TV")
            .apply { if (token != null) addQueryParameter("X-Plex-Token", token) }
            .build()
            .toString()
    }

    private fun scheme(): String = if (appPreferences.plexUseHttps) "https" else "http"

    private fun allowedHost(): String? {
        val host = NetworkEndpoint.extractHost(appPreferences.host)
        if (host.isBlank()) return null
        if (appPreferences.localNetworkOnly && !LocalNetworkPolicy.isLocalHost(host)) return null
        return host
    }


    /** Lets the server apply its remote-streaming limits to a server outside the home network. */
    private fun transcodeLocation(host: String): String =
        if (com.ambient.tv.network.isHomeNetworkHost(host)) "lan" else "wan"
}

/** 16:9 backdrop for a window whose long side is [windowLongSide], between 1280 and 3840 wide. */
internal fun plexBackdropSize(windowLongSide: Int): Pair<Int, Int> {
    val width = when {
        windowLongSide <= 0 -> 1920
        windowLongSide <= 1280 -> 1280
        windowLongSide <= 1920 -> 1920
        windowLongSide <= 2560 -> 2560
        else -> 3840
    }
    return width to width * 9 / 16
}
