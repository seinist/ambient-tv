package com.ambient.tv.spotify

/** Web-API-derived control state layered over the authoritative native engine session. */
internal data class SpotifyWebPlaybackOverlay(
    val queue: List<SpotifyTrack>? = null,
    val positionMs: Long? = null,
    val volume: Int? = null,
    val shuffle: Boolean? = null,
    val repeatMode: Int? = null,
    val error: String? = null,
    val supportsVolume: Boolean? = null,
)

internal fun projectSpotifyPlaybackState(
    engine: SpotifyPlaybackState,
    web: SpotifyWebPlaybackOverlay,
): SpotifyPlaybackState = engine.copy(
    queue = web.queue ?: engine.queue,
    positionMs = web.positionMs ?: engine.positionMs,
    volume = web.volume ?: engine.volume,
    supportsVolume = web.supportsVolume ?: engine.supportsVolume,
    shuffle = web.shuffle ?: engine.shuffle,
    repeatMode = web.repeatMode ?: engine.repeatMode,
    error = web.error ?: engine.error,
)
