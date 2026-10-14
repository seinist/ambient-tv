package com.ambient.tv.spotify

enum class SpotifyEntityType { TRACK, ALBUM, ARTIST, PLAYLIST }

@kotlinx.serialization.Serializable
data class SpotifyImage(val url: String, val width: Int? = null, val height: Int? = null)

@kotlinx.serialization.Serializable
data class SpotifyArtist(val id: String, val name: String, val images: List<SpotifyImage> = emptyList())

@kotlinx.serialization.Serializable
data class SpotifyAlbum(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val images: List<SpotifyImage> = emptyList(),
    val releaseDate: String? = null,
    val tracks: List<SpotifyTrack> = emptyList(),
)

@kotlinx.serialization.Serializable
data class SpotifyTrack(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbum? = null,
    val durationMs: Long = 0,
    val uri: String = "spotify:track:$id",
    val explicit: Boolean = false,
)

@kotlinx.serialization.Serializable
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val description: String? = null,
    val ownerName: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    val tracks: List<SpotifyTrack> = emptyList(),
)

@kotlinx.serialization.Serializable
data class SpotifyBrowseItem(
    val id: String,
    val type: SpotifyEntityType,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val uri: String,
)


@kotlinx.serialization.Serializable
data class SpotifyRemotePlaybackState(
    val positionMs: Long? = null,
    val volume: Int? = null,
    val shuffle: Boolean? = null,
    val repeatMode: Int? = null,
    val supportsVolume: Boolean? = null,
)

@kotlinx.serialization.Serializable
data class SpotifyPlaybackState(
    val available: Boolean = false,
    val connected: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 100,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val current: SpotifyTrack? = null,
    val queue: List<SpotifyTrack> = emptyList(),
    val deviceName: String = com.ambient.tv.branding.AppBrand.name,
    val error: String? = null,
    val supportsVolume: Boolean? = null,
)


@kotlinx.serialization.Serializable
data class SpotifyArtistDetail(val artist: SpotifyArtist, val topTracks: List<SpotifyTrack> = emptyList(), val albums: List<SpotifyAlbum> = emptyList())


@kotlinx.serialization.Serializable
data class SpotifyAccount(val id: String, val displayName: String? = null, val product: String? = null, val images: List<SpotifyImage> = emptyList())
