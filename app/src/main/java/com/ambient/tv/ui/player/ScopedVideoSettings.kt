package com.ambient.tv.ui.player

import com.ambient.tv.data.local.ResolutionVideoProfile
import com.ambient.tv.data.local.TitleVideoSettings

/** Where a player change can be remembered, narrowest first. */
enum class VideoSettingsScope { TITLE, SEASON, SERIES, COLLECTION }

/** One storable scope of the playing title; [label] is the season/series/collection name if known. */
data class VideoScopeLayer(val scope: VideoSettingsScope, val key: String, val label: String? = null)

/** Origin of an effective setting, shown in the player: scope, width profile or the general default. */
enum class VideoSettingOrigin { TITLE, SEASON, SERIES, COLLECTION, RESOLUTION, DEFAULT }

data class VideoSettingOrigins(
    val renderer: VideoSettingOrigin,
    val picture: VideoSettingOrigin,
    val deinterlace: VideoSettingOrigin,
    val output: VideoSettingOrigin,
    val audio: VideoSettingOrigin,
    val delay: VideoSettingOrigin,
)

/** Length-delimited server identity: IDs may contain ':' (e.g. "j:…"), servers must not collide. */
internal fun scopedVideoKey(kind: String, source: String, serverId: String?, id: String): String {
    val server = serverId?.takeIf { it.isNotBlank() }.orEmpty()
    return "$kind:$source:${server.length}:$server:$id"
}

/**
 * Narrow to broad: title, season, series, then collections (most recently saved first, so with
 * several collections the newest saved one wins).
 */
internal fun videoScopeLayers(
    titleKey: String?, source: String, scope: PlaybackVideoScope?, scoped: Map<String, TitleVideoSettings>,
): List<VideoScopeLayer> = buildList {
    titleKey ?: return@buildList
    add(VideoScopeLayer(VideoSettingsScope.TITLE, titleKey))
    scope ?: return@buildList
    scope.seasonId?.takeIf { it.isNotBlank() && scope.episode }?.let {
        add(VideoScopeLayer(VideoSettingsScope.SEASON, scopedVideoKey("season", source, scope.serverId, it), scope.seasonLabel))
    }
    scope.seriesId?.takeIf { it.isNotBlank() && scope.episode }?.let {
        add(VideoScopeLayer(VideoSettingsScope.SERIES, scopedVideoKey("series", source, scope.serverId, it), scope.seriesLabel))
    }
    scope.collections.mapNotNull { tag ->
        val id = tag.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        VideoScopeLayer(VideoSettingsScope.COLLECTION, scopedVideoKey("collection", source, scope.serverId, id), tag.name)
    }.distinctBy { it.key }.sortedByDescending { scoped[it.key]?.savedAt ?: Long.MIN_VALUE }.forEach(::add)
}

/** Field-wise merge of [values] (narrow to broad); null when no scope holds anything. */
internal fun mergeVideoScopes(values: List<TitleVideoSettings?>): TitleVideoSettings? =
    values.filterNotNull().reduceOrNull { narrow, broad -> narrow.inheritFrom(broad) }

internal fun videoSettingOrigins(
    layers: List<Pair<VideoScopeLayer, TitleVideoSettings?>>, resolution: ResolutionVideoProfile?,
): VideoSettingOrigins {
    fun origin(inLayer: (TitleVideoSettings) -> Boolean, inResolution: (ResolutionVideoProfile) -> Boolean): VideoSettingOrigin {
        val layer = layers.firstOrNull { (_, value) -> value != null && inLayer(value) }?.first
        if (layer != null) return when (layer.scope) {
            VideoSettingsScope.TITLE -> VideoSettingOrigin.TITLE
            VideoSettingsScope.SEASON -> VideoSettingOrigin.SEASON
            VideoSettingsScope.SERIES -> VideoSettingOrigin.SERIES
            VideoSettingsScope.COLLECTION -> VideoSettingOrigin.COLLECTION
        }
        return if (resolution != null && inResolution(resolution)) VideoSettingOrigin.RESOLUTION else VideoSettingOrigin.DEFAULT
    }
    return VideoSettingOrigins(
        renderer = origin({ it.rendererKey != null }, { true }),
        picture = origin({ it.mpv != null || it.vlc != null || it.media3 != null }, { it.mpv != null || it.vlc != null || it.media3 != null }),
        deinterlace = origin(
            { it.mpvDeinterlaceMode != null || it.mpvDeinterlaceFilter != null || it.vlcDeinterlaceMode != null || it.vlcDeinterlaceFilter != null },
            { it.mpvDeinterlaceMode != null || it.mpvDeinterlaceFilter != null || it.vlcDeinterlaceMode != null || it.vlcDeinterlaceFilter != null }),
        output = origin({ it.mpvOutput != null || it.mpvDecoder != null || it.vlcOutput != null },
            { it.mpvOutput != null || it.mpvDecoder != null || it.vlcOutput != null }),
        audio = origin({ it.audio != null }, { it.audio != null }),
        delay = origin({ it.audioDelayMs != null || it.subtitleDelayMs != null }, { false }),
    )
}
