package com.ambient.tv.car

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Opaque IDs: no server URL, Spotify token or stream credentials enter Android Auto's browse tree. */
internal object CarAudioIds {
    const val ROOT = "car:root"
    const val RADIO_ROOT = "car:radio:root"
    const val SPOTIFY_ROOT = "car:spotify:root"
    const val RADIO_FAVORITES = "car:radio:favorites"
    const val RADIO_RECENT = "car:radio:recent"
    const val RADIO_POPULAR = "car:radio:popular"
    const val SPOTIFY_HOME = "car:spotify:home"
    const val SPOTIFY_LIKED = "car:spotify:liked"
    const val SPOTIFY_PLAYLISTS = "car:spotify:playlists"
    const val SPOTIFY_ALBUMS = "car:spotify:albums"
    const val SPOTIFY_ARTISTS = "car:spotify:artists"
    const val MUSIC_LIBRARIES = "car:music:libraries"

    private const val STATION = "car:station:"
    private const val SPOTIFY_TRACK = "car:spotify:track:"
    private const val SPOTIFY_COLLECTION = "car:spotify:collection:"
    private const val MUSIC_SECTION = "car:music:section:"
    private const val MUSIC_ITEM = "car:music:item:"

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
            .takeIf { it.isNotBlank() && it.length <= 512 }
    }.getOrNull()

    fun station(id: String) = STATION + encode(id)
    fun stationId(id: String): String? = id.takeIf { it.startsWith(STATION) }
        ?.removePrefix(STATION)?.let(::decode)

    fun spotifyTrack(id: String) = SPOTIFY_TRACK + encode(id)
    fun spotifyTrackId(id: String): String? = id.takeIf { it.startsWith(SPOTIFY_TRACK) }
        ?.removePrefix(SPOTIFY_TRACK)?.let(::decode)

    fun spotifyCollection(kind: String, id: String) = SPOTIFY_COLLECTION + kind + ":" + encode(id)
    fun spotifyCollectionId(id: String): Pair<String, String>? {
        if (!id.startsWith(SPOTIFY_COLLECTION)) return null
        val raw = id.removePrefix(SPOTIFY_COLLECTION)
        val kind = raw.substringBefore(':')
        if (kind !in setOf("album", "playlist", "artist")) return null
        return decode(raw.substringAfter(':', ""))?.let { kind to it }
    }

    fun musicSection(key: String) = MUSIC_SECTION + encode(key)
    fun musicSectionId(id: String) = id.takeIf { it.startsWith(MUSIC_SECTION) }
        ?.removePrefix(MUSIC_SECTION)?.let(::decode)

    fun musicItem(key: String) = MUSIC_ITEM + encode(key)
    fun musicItemId(id: String) = id.takeIf { it.startsWith(MUSIC_ITEM) }
        ?.removePrefix(MUSIC_ITEM)?.let(::decode)

    fun isFolder(id: String): Boolean =
        id in setOf(ROOT, RADIO_ROOT, SPOTIFY_ROOT, RADIO_FAVORITES, RADIO_RECENT, RADIO_POPULAR, SPOTIFY_HOME,
            SPOTIFY_LIKED, SPOTIFY_PLAYLISTS, SPOTIFY_ALBUMS, SPOTIFY_ARTISTS, MUSIC_LIBRARIES) ||
            musicSectionId(id) != null || musicItemId(id) != null ||
            spotifyCollectionId(id) != null
}

/** Android Auto's standard media surface must not expose movies or video-only entries as audio. */
internal fun carAudioPlayableType(type: String) = type.lowercase() in setOf("track", "song")
internal fun carAudioBrowseType(type: String) = type.lowercase() in setOf("music", "artist", "album")
