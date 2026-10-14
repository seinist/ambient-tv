package com.ambient.tv.data.local

import kotlinx.serialization.Serializable

/** A video preset shared by sources with the same encoded width, regardless of height. */
@Serializable
data class ResolutionVideoProfile(
    val rendererKey: String,
    val audio: AudioSettings? = null,
    val mpv: MpvImageOptions? = null,
    val vlc: VlcImageOptions? = null,
    val media3: Media3ImageOptions? = null,
    val mpvOutput: String? = null,
    val mpvDecoder: String? = null,
    val vlcOutput: String? = null,
    val mpvDeinterlaceMode: String? = null,
    val mpvDeinterlaceFilter: String? = null,
    val vlcDeinterlaceMode: String? = null,
    val vlcDeinterlaceFilter: String? = null,
) {
    fun rendererOrDefault(default: String): String =
        rendererKey.takeIf { it in setOf("auto", "media3", "vlc", "mpv") } ?: default

    /** A running player captures one renderer; keep the other renderers configured in Settings. */
    fun withSnapshot(snapshot: ResolutionVideoProfile): ResolutionVideoProfile = snapshot.copy(
        audio = snapshot.audio ?: audio,
        mpv = snapshot.mpv ?: mpv, vlc = snapshot.vlc ?: vlc, media3 = snapshot.media3 ?: media3,
        mpvOutput = snapshot.mpvOutput ?: mpvOutput, mpvDecoder = snapshot.mpvDecoder ?: mpvDecoder,
        vlcOutput = snapshot.vlcOutput ?: vlcOutput,
        mpvDeinterlaceMode = snapshot.mpvDeinterlaceMode ?: mpvDeinterlaceMode,
        mpvDeinterlaceFilter = snapshot.mpvDeinterlaceFilter ?: mpvDeinterlaceFilter,
        vlcDeinterlaceMode = snapshot.vlcDeinterlaceMode ?: vlcDeinterlaceMode,
        vlcDeinterlaceFilter = snapshot.vlcDeinterlaceFilter ?: vlcDeinterlaceFilter,
    )
}

object VideoResolutionClass {
    // Legacy numeric keys described height. New keys are explicit to avoid confusing 720px with 720p.
    private val legacyStandard = linkedMapOf(
        "480" to (854 to 480), "720" to (1280 to 720), "1080" to (1920 to 1080),
        "1440" to (2560 to 1440), "2160" to (3840 to 2160),
    )
    val keys = listOf(720, 854, 1280, 1920, 2560, 3840, 7680).map { "width:$it" }

    fun key(width: Int?): String? = width?.takeIf { it > 0 }?.let { "width:$it" }

    private fun legacyDimensions(key: String): Pair<Int, Int>? = legacyStandard[key] ?: key.split('x').takeIf { it.size == 2 }?.let {
        val width = it[0].toIntOrNull()?.takeIf { n -> n > 0 } ?: return null
        val height = it[1].toIntOrNull()?.takeIf { n -> n > 0 } ?: return null
        width to height
    }

    fun normalizeKey(key: String): String? = if (key.startsWith("width:"))
        key(key.removePrefix("width:").toIntOrNull()) else legacyDimensions(key)?.let { key(it.first) }

    fun normalizeProfileKey(key: String): String? {
        if (key == "all") return key
        val live = key.startsWith("live:")
        val resolution = normalizeKey(key.removePrefix("live:")) ?: return null
        return if (live) "live:$resolution" else resolution
    }

    fun label(key: String): String = normalizeKey(key)?.removePrefix("width:")?.let { "$it × *" } ?: key

    /** Canonical width entries win; otherwise prefer the old standard format, then largest height.
     * Sort ties as well so imports do not depend on JSON map order. Never combine filter chains. */
    fun migrateProfiles(profiles: Map<String, ResolutionVideoProfile>): Map<String, ResolutionVideoProfile> =
        profiles.entries.sortedWith(compareBy<Map.Entry<String, ResolutionVideoProfile>>(
            { if (it.key.removePrefix("live:").startsWith("width:")) 2
                else if (legacyDimensions(it.key.removePrefix("live:")) in legacyStandard.values) 1 else 0 },
            { legacyDimensions(it.key.removePrefix("live:"))?.second ?: 0 },
            { it.key },
        )).mapNotNull { (key, profile) -> normalizeProfileKey(key)?.let { it to profile } }.toMap()
}

/** Resolve whole profiles: a Live TV override wins without merging in the regular filter chain. */
internal fun resolutionProfileFor(
    profiles: Map<String, ResolutionVideoProfile>, widthKey: String?, live: Boolean,
): ResolutionVideoProfile? = widthKey?.let {
    (if (live) profiles["live:$it"] else null) ?: profiles[it]
}

/** Live defaults must never overwrite the corresponding regular width profile. */
internal fun withLiveResolutionProfile(
    profiles: Map<String, ResolutionVideoProfile>, resolution: String, profile: ResolutionVideoProfile?,
): Map<String, ResolutionVideoProfile> {
    val exact = VideoResolutionClass.normalizeKey(resolution)
    require(exact != null)
    val key = "live:$exact"
    return if (profile == null) profiles - key else profiles + (key to profile)
}
