package com.ambient.tv.car

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Opaque IDs: no server URL, Spotify token or stream credentials enter Android Auto's browse tree. */
internal object CarAudioIds {
    const val ROOT = "car:root"
    const val RADIO_ROOT = "car:radio:root"
    const val RADIO_ALL = "car:radio:all"
    const val RADIO_GENRES = "car:radio:genres"
    const val RADIO_COUNTRIES = "car:radio:countries"
    const val RADIO_LANGUAGES = "car:radio:languages"
    const val LIVE_RADIO_IPTV = "car:live:iptv"
    const val LIVE_RADIO_TVH = "car:live:tvheadend"
    const val STORAGE_ROOT = "car:storage:root"
    const val DLNA_ROOT = "car:storage:dlna"
    const val FILES_ROOT = "car:storage:files"
    const val FILE_FAVORITES = "car:storage:favorites"
    const val FILE_RECENT = "car:storage:recent"

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
    private const val RADIO_FACET = "car:radio:facet:"
    private const val LIVE_SOURCE = "car:live:source:"
    private const val LIVE_GROUPS = "car:live:groups:"
    private const val LIVE_GROUP = "car:live:group:"
    private const val LIVE_AUDIO = "car:live:audio:"
    private const val DLNA_SERVER = "car:dlna:server:"
    private const val DLNA_FOLDER = "car:dlna:folder:"
    private const val FILE_SOURCE = "car:file:source:"
    private const val FILE_FOLDER = "car:file:folder:"
    private const val MORE = "car:more:"


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

    /** A page ID is an opaque bounded cursor over a stable, already supported parent. */
    fun more(parent: String, offset: Int) = MORE + encode("$offset\n$parent")
    fun moreSpec(id: String): Pair<String, Int>? {
        val raw = id.takeIf { it.startsWith(MORE) }?.removePrefix(MORE)?.let(::decode) ?: return null
        val offset = raw.substringBefore('\n').toIntOrNull() ?: return null
        val parent = raw.substringAfter('\n', "")
        return if (offset in 1..1000 && parent.isNotBlank() && !parent.startsWith(MORE) &&
            (isFolderBase(parent) || radioFacetId(parent) != null || liveSourceId(parent) != null ||
                liveGroupsId(parent) != null || liveGroupSpec(parent) != null ||
                dlnaServerId(parent) != null || dlnaFolderId(parent) != null ||
                fileSourceId(parent) != null || fileFolderId(parent) != null)) parent to offset
        else null
    }

    fun radioFacet(kind: String, value: String) = RADIO_FACET + kind + ":" + encode(value)
    fun radioFacetId(id: String): Pair<String, String>? {
        if (!id.startsWith(RADIO_FACET)) return null
        val raw = id.removePrefix(RADIO_FACET)
        val kind = raw.substringBefore(':')
        if (kind !in setOf("tag", "country", "language")) return null
        return decode(raw.substringAfter(':', ""))?.let { kind to it }
    }

    fun liveSource(id: String) = LIVE_SOURCE + encode(id)
    fun liveSourceId(id: String) = id.takeIf { it.startsWith(LIVE_SOURCE) }
        ?.removePrefix(LIVE_SOURCE)?.let(::decode)
    fun liveGroups(id: String) = LIVE_GROUPS + encode(id)
    fun liveGroupsId(id: String) = id.takeIf { it.startsWith(LIVE_GROUPS) }
        ?.removePrefix(LIVE_GROUPS)?.let(::decode)
    fun liveGroup(sourceId: String, group: String) = LIVE_GROUP + encode("$sourceId\n$group")
    fun liveGroupSpec(id: String): Pair<String, String>? =
        id.takeIf { it.startsWith(LIVE_GROUP) }?.removePrefix(LIVE_GROUP)?.let(::decode)
            ?.takeIf { '\n' in it }?.let { it.substringBefore('\n') to it.substringAfter('\n') }
    fun liveAudio(sourceId: String, uuid: String) = LIVE_AUDIO + encode("$sourceId\n$uuid")
    fun liveAudioSpec(id: String): Pair<String, String>? =
        id.takeIf { it.startsWith(LIVE_AUDIO) }?.removePrefix(LIVE_AUDIO)?.let(::decode)
            ?.takeIf { '\n' in it }?.let { it.substringBefore('\n') to it.substringAfter('\n') }

    fun dlnaServer(id: String) = DLNA_SERVER + encode(id)
    fun dlnaServerId(id: String) = id.takeIf { it.startsWith(DLNA_SERVER) }
        ?.removePrefix(DLNA_SERVER)?.let(::decode)
    fun dlnaFolder(key: String) = DLNA_FOLDER + encode(key)
    fun dlnaFolderId(id: String) = id.takeIf { it.startsWith(DLNA_FOLDER) }
        ?.removePrefix(DLNA_FOLDER)?.let(::decode)

    fun fileSource(id: String) = FILE_SOURCE + encode(id)
    fun fileSourceId(id: String) = id.takeIf { it.startsWith(FILE_SOURCE) }
        ?.removePrefix(FILE_SOURCE)?.let(::decode)
    fun fileFolder(key: String) = FILE_FOLDER + encode(key)
    fun fileFolderId(id: String) = id.takeIf { it.startsWith(FILE_FOLDER) }
        ?.removePrefix(FILE_FOLDER)?.let(::decode)

    private fun isFolderBase(id: String): Boolean =
        id in setOf(ROOT, RADIO_ROOT, RADIO_ALL, RADIO_GENRES, RADIO_COUNTRIES, RADIO_LANGUAGES,
            LIVE_RADIO_IPTV, LIVE_RADIO_TVH, STORAGE_ROOT, DLNA_ROOT, FILES_ROOT,
            FILE_FAVORITES, FILE_RECENT, SPOTIFY_ROOT, RADIO_FAVORITES, RADIO_RECENT, RADIO_POPULAR,
            SPOTIFY_HOME, SPOTIFY_LIKED, SPOTIFY_PLAYLISTS, SPOTIFY_ALBUMS,
            SPOTIFY_ARTISTS, MUSIC_LIBRARIES) ||
            musicSectionId(id) != null || musicItemId(id) != null || spotifyCollectionId(id) != null

    fun isFolder(id: String): Boolean =
        isFolderBase(id) || radioFacetId(id) != null || liveSourceId(id) != null ||
        liveGroupsId(id) != null || liveGroupSpec(id) != null ||
        dlnaServerId(id) != null || dlnaFolderId(id) != null ||
        fileSourceId(id) != null || fileFolderId(id) != null || moreSpec(id) != null

    /* Extra folders are resolved only through validated opaque IDs. */
}

/** Android Auto's standard media surface must not expose movies or video-only entries as audio. */
internal fun carAudioPlayableType(type: String) = type.lowercase() in setOf("track", "song")
internal fun carAudioBrowseType(type: String) = type.lowercase() in setOf("music", "artist", "album")
