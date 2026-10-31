package com.ambient.tv.car

import android.content.Context
import android.os.SystemClock
import com.ambient.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.radio.RadioBrowserRepository
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.data.radio.radioLanguageDisplayName
import com.ambient.tv.data.dlna.DlnaKeys
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvPlaybackSource
import java.util.Locale
import com.ambient.tv.data.repository.RoutingMediaRepository
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.spotify.SpotifyBrowseItem
import com.ambient.tv.spotify.SpotifyEntityType
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.spotify.SpotifyTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

internal data class CarAudioEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val playable: Boolean = false,
    val radioStation: RadioStation? = null,
    val libraryItem: MediaItem? = null,
)

internal sealed interface CarAudioSelection {
    data class Radio(val station: RadioStation, val queue: List<RadioStation>) : CarAudioSelection
    data class Spotify(val uri: String) : CarAudioSelection
    data class Library(val item: MediaItem, val queue: List<String>) : CarAudioSelection
    data class Live(val channel: LiveTvChannel, val stream: LiveTvPlaybackSource,
        val queue: List<String>, val system: String) : CarAudioSelection
}

/** Read-only, driver-safe browse facade. Existing repositories and playback engines own the data. */
@Singleton
internal class CarAudioCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val radio: RadioBrowserRepository,
    private val spotify: SpotifyRepository,
    private val media: RoutingMediaRepository,
    private val auxiliary: CarAuxiliaryAudio,
) {
    private data class Page(val entries: List<CarAudioEntry>, val loadedAt: Long)
    private val pages = LinkedHashMap<String, Page>()
    private val entriesById = LinkedHashMap<String, CarAudioEntry>()
    private val parents = LinkedHashMap<String, String>()
    private val maxItems = 500
    private val maxPages = 90

    fun invalidate(parentId: String) {
        pages.remove(parentId)
    }

    suspend fun children(parentId: String): List<CarAudioEntry> {
        if (!CarAudioIds.isFolder(parentId)) return emptyList()
        if (parentId == CarAudioIds.ROOT) return root()
        val now = SystemClock.elapsedRealtime()
        pages[parentId]?.takeIf { now - it.loadedAt < 45_000 }?.let { return it.entries }
        val fresh = try {
            withTimeoutOrNull(15_000) { loadCarPage(parentId) } ?: emptyList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Connectivity must not make the Auto UI jump to a temporary empty page.
            return pages[parentId]?.entries.orEmpty()
        }
        if (fresh.isEmpty() && pages[parentId]?.entries?.isNotEmpty() == true) {
            return pages.getValue(parentId).entries
        }
        val result = fresh.distinctBy { it.id }.take(PAGE_SIZE + 1)
        pages[parentId] = Page(result, now)
        while (pages.size > maxPages) pages.remove(pages.keys.first())
        result.forEach { entry ->
            entriesById[entry.id] = entry
            parents[entry.id] = parentId
        }
        while (entriesById.size > maxItems) {
            val oldest = entriesById.keys.first()
            entriesById.remove(oldest)
            parents.remove(oldest)
        }
        return result
    }

    private fun root(): List<CarAudioEntry> = buildList {
        // Android Auto normally allows <= 4 root tabs. Nest providers' categories instead.
        if (preferences.radioEnabled || auxiliary.hasLive("iptv") || auxiliary.hasLive("tvheadend"))
            add(folder(CarAudioIds.RADIO_ROOT, context.getString(R.string.car_audio_radio)))
        if (preferences.spotifyEnabled && spotify.webAuthorized)
            add(folder(CarAudioIds.SPOTIFY_ROOT, context.getString(R.string.car_audio_spotify)))
        add(folder(CarAudioIds.MUSIC_LIBRARIES, context.getString(R.string.car_audio_music_libraries)))
        if (auxiliary.hasStorage())
            add(folder(CarAudioIds.STORAGE_ROOT, context.getString(R.string.car_audio_storage)))
    }

    private fun folder(id: String, title: String, subtitle: String? = null) =
        CarAudioEntry(id, title, subtitle)

    private fun RadioStation.asEntry() = CarAudioEntry(
        id = CarAudioIds.station(id),
        title = name,
        subtitle = country,
        artworkUrl = artworkUrl,
        playable = true,
        radioStation = this,
    )

    private fun MediaItem.asEntry(): CarAudioEntry? {
        val playable = carAudioPlayableType(type)
        if (!playable && !carAudioBrowseType(type)) return null
        return CarAudioEntry(
            id = CarAudioIds.musicItem(ratingKey),
            title = title,
            subtitle = parentTitle ?: grandparentTitle,
            playable = playable,
            libraryItem = this,
        )
    }

    private fun SpotifyBrowseItem.asEntry(): CarAudioEntry? = when (type) {
        SpotifyEntityType.TRACK -> if (uri.startsWith("spotify:track:")) CarAudioEntry(
            CarAudioIds.spotifyTrack(id), title, subtitle, artworkUrl, playable = true,
        ) else null
        SpotifyEntityType.ALBUM -> folder(CarAudioIds.spotifyCollection("album", id), title, subtitle)
        SpotifyEntityType.PLAYLIST -> folder(CarAudioIds.spotifyCollection("playlist", id), title, subtitle)
        SpotifyEntityType.ARTIST -> folder(CarAudioIds.spotifyCollection("artist", id), title, subtitle)
    }

    private fun SpotifyTrack.asEntry() = CarAudioEntry(
        CarAudioIds.spotifyTrack(id), name, artists.joinToString(", ") { it.name },
        album?.images?.firstOrNull()?.url, playable = uri.startsWith("spotify:track:"),
    )

    /** Bounded pages and explicit "More" folders work with the stock Android Auto UI. */
    private suspend fun loadCarPage(requestId: String): List<CarAudioEntry> {
        val (parent, offset) = CarAudioIds.moreSpec(requestId) ?: (requestId to 0)
        val special = auxiliary.children(parent, offset, PAGE_SIZE)
            ?: radioPage(parent, offset)
        if (special != null) {
            val nextOffset = special.nextOffset ?: (offset + PAGE_SIZE)
            return special.items + if (special.hasMore && nextOffset > offset && nextOffset <= MAX_CURSOR)
                listOf(folder(CarAudioIds.more(parent, nextOffset), context.getString(R.string.car_audio_more)))
            else emptyList()
        }
        val base = loadPage(parent)
        val end = (offset + PAGE_SIZE).coerceAtMost(base.size)
        val selected = base.drop(offset).take(PAGE_SIZE)
        return if (end < base.size && end <= MAX_CURSOR) selected +
            folder(CarAudioIds.more(parent, end), context.getString(R.string.car_audio_more))
        else selected
    }

    private suspend fun radioPage(parent: String, offset: Int): CarAudioPage? {
        fun slice(items: List<CarAudioEntry>) =
            CarAudioPage(items.drop(offset).take(PAGE_SIZE), items.size > offset + PAGE_SIZE)
        if (!preferences.radioEnabled) return when {
            parent in setOf(CarAudioIds.RADIO_ALL, CarAudioIds.RADIO_GENRES,
                CarAudioIds.RADIO_COUNTRIES, CarAudioIds.RADIO_LANGUAGES) ||
                CarAudioIds.radioFacetId(parent) != null -> CarAudioPage(emptyList())
            else -> null
        }
        return when (parent) {
            CarAudioIds.RADIO_ALL -> radio.search(offset = offset, limit = PAGE_SIZE,
                order = "name", reverse = false).let { CarAudioPage(it.items.map { station -> station.asEntry() }, it.hasMore) }
            CarAudioIds.RADIO_GENRES -> slice(radio.tags(limit = 150)
                .sortedBy { it.name.lowercase() }
                .map { folder(CarAudioIds.radioFacet("tag", it.id), it.name) })
            CarAudioIds.RADIO_COUNTRIES -> slice(radio.countries(limit = 200)
                .sortedBy { it.name }
                .map { facet ->
                    val label = runCatching { Locale("", facet.id).getDisplayCountry(context.resources.configuration.locales[0]) }
                        .getOrDefault(facet.name).takeIf { it.isNotBlank() } ?: facet.name
                    folder(CarAudioIds.radioFacet("country", facet.id), label)
                })
            CarAudioIds.RADIO_LANGUAGES -> slice(radio.languages(limit = 200)
                .map { facet ->
                    val label = radioLanguageDisplayName(facet.languageCode, facet.name,
                        context.resources.configuration.locales[0]) ?: facet.name
                    folder(CarAudioIds.radioFacet("language", facet.id), label)
                }.sortedBy { it.title.lowercase() })
            else -> CarAudioIds.radioFacetId(parent)?.let { (kind, value) ->
                val page = radio.search(
                    tag = value.takeIf { kind == "tag" },
                    countryCode = value.takeIf { kind == "country" },
                    language = value.takeIf { kind == "language" },
                    offset = offset, limit = PAGE_SIZE, order = "name", reverse = false,
                )
                CarAudioPage(page.items.map { it.asEntry() }, page.hasMore)
            }
        }
    }

    private suspend fun loadPage(parentId: String): List<CarAudioEntry> = when (parentId) {
        CarAudioIds.RADIO_ROOT -> buildList {
            if (preferences.radioEnabled) {
                add(folder(CarAudioIds.RADIO_FAVORITES, context.getString(R.string.car_audio_favorites)))
                add(folder(CarAudioIds.RADIO_RECENT, context.getString(R.string.car_audio_recent)))
                add(folder(CarAudioIds.RADIO_POPULAR, context.getString(R.string.car_audio_popular)))
                add(folder(CarAudioIds.RADIO_ALL, context.getString(R.string.car_audio_all)))
                add(folder(CarAudioIds.RADIO_GENRES, context.getString(R.string.car_audio_genres)))
                add(folder(CarAudioIds.RADIO_COUNTRIES, context.getString(R.string.car_audio_countries)))
                add(folder(CarAudioIds.RADIO_LANGUAGES, context.getString(R.string.car_audio_languages)))
            }
            if (auxiliary.hasLive("iptv"))
                add(folder(CarAudioIds.LIVE_RADIO_IPTV, context.getString(R.string.car_audio_iptv_radio)))
            if (auxiliary.hasLive("tvheadend"))
                add(folder(CarAudioIds.LIVE_RADIO_TVH, context.getString(R.string.car_audio_tvh_radio)))
        }
        CarAudioIds.SPOTIFY_ROOT -> if (spotifyEnabled()) listOf(
            folder(CarAudioIds.SPOTIFY_HOME, context.getString(R.string.car_audio_home)),
            folder(CarAudioIds.SPOTIFY_LIKED, context.getString(R.string.car_audio_liked)),
            folder(CarAudioIds.SPOTIFY_PLAYLISTS, context.getString(R.string.car_audio_playlists)),
            folder(CarAudioIds.SPOTIFY_ALBUMS, context.getString(R.string.car_audio_albums)),
            folder(CarAudioIds.SPOTIFY_ARTISTS, context.getString(R.string.car_audio_artists)),
        ) else emptyList()
        CarAudioIds.RADIO_FAVORITES ->
            if (preferences.radioEnabled) preferences.radioFavorites.map { it.asEntry() } else emptyList()
        CarAudioIds.RADIO_RECENT ->
            if (preferences.radioEnabled) preferences.recentRadioStations.map { it.asEntry() } else emptyList()
        CarAudioIds.RADIO_POPULAR ->
            if (preferences.radioEnabled) radio.popular(limit = 60).map { it.asEntry() } else emptyList()
        CarAudioIds.SPOTIFY_HOME ->
            if (spotifyEnabled()) spotify.home().mapNotNull { it.asEntry() } else emptyList()
        CarAudioIds.SPOTIFY_LIKED, CarAudioIds.SPOTIFY_PLAYLISTS,
        CarAudioIds.SPOTIFY_ALBUMS, CarAudioIds.SPOTIFY_ARTISTS -> {
            if (!spotifyEnabled()) emptyList() else {
                val section = when (parentId) {
                    CarAudioIds.SPOTIFY_LIKED -> "liked"
                    CarAudioIds.SPOTIFY_PLAYLISTS -> "playlists"
                    CarAudioIds.SPOTIFY_ALBUMS -> "albums"
                    else -> "artists"
                }
                spotify.librarySection(section).mapNotNull { it.asEntry() }
            }
        }
        CarAudioIds.MUSIC_LIBRARIES -> {
            // Include all configured backend instances; provider-owned IDs stay opaque.
            listOf(media.plex, media.jellyfin, media.emby)
                .flatMap { provider -> provider.getLibrarySections().getOrDefault(emptyList()) }
                .filter { carAudioBrowseType(it.type) }
                .distinctBy { it.key }
                .map { folder(CarAudioIds.musicSection(it.key), it.title) }
        }
        else -> {
            val spotifyCollection = CarAudioIds.spotifyCollectionId(parentId)
            val musicSection = CarAudioIds.musicSectionId(parentId)
            val musicItem = CarAudioIds.musicItemId(parentId)
            when {
                spotifyCollection != null && spotifyEnabled() -> {
                    val (kind, id) = spotifyCollection
                    when (kind) {
                        "album" -> spotify.album(id).tracks.filter { it.uri.startsWith("spotify:track:") }.map { it.asEntry() }
                        "playlist" -> spotify.playlist(id).tracks.filter { it.uri.startsWith("spotify:track:") }.map { it.asEntry() }
                        "artist" -> spotify.artist(id).topTracks.filter { it.uri.startsWith("spotify:track:") }.map { it.asEntry() }
                        else -> emptyList()
                    }
                }
                musicSection != null -> {
                    val preview = media.getSectionPreview(musicSection, "titleSort", 80).getOrDefault(emptyList())
                    val content = preview.ifEmpty { media.getSectionItems(musicSection).getOrDefault(emptyList()).take(80) }
                    content.mapNotNull { it.asEntry() }
                }
                musicItem != null -> {
                    val selected = entriesById[parentId]?.libraryItem
                        ?: media.getMetadata(musicItem).getOrNull()
                    if (selected != null && carAudioBrowseType(selected.type)) {
                        media.getChildren(selected.ratingKey).getOrDefault(emptyList())
                            .mapNotNull { it.asEntry() }
                    } else emptyList()
                }
                else -> emptyList()
            }
        }
    }

    private fun spotifyEnabled() = preferences.spotifyEnabled && spotify.webAuthorized

    /** A selection is resolved through the authenticated repository, never a URI provided by a car client. */
    suspend fun resolve(id: String): CarAudioSelection? {
        if (CarAudioIds.liveAudioSpec(id) != null) return auxiliary.resolveLive(id)
        CarAudioIds.stationId(id)?.let { stationId ->
            if (!preferences.radioEnabled) return null
            val station = entriesById[id]?.radioStation
                ?: preferences.radioFavorites.firstOrNull { it.id == stationId }
                ?: preferences.recentRadioStations.firstOrNull { it.id == stationId }
                ?: radio.station(stationId) ?: return null
            val siblings = parents[id]?.let(pages::get)?.entries.orEmpty()
                .mapNotNull { it.radioStation }
            return CarAudioSelection.Radio(station, siblings.ifEmpty { listOf(station) })
        }
        CarAudioIds.spotifyTrackId(id)?.let { trackId ->
            if (!spotifyEnabled()) return null
            return CarAudioSelection.Spotify("spotify:track:$trackId")
        }
        CarAudioIds.musicItemId(id)?.let { key ->
            // Browse previews often omit stream URLs; resolve the authoritative track
            // metadata before claiming the item is unavailable to the head unit.
            val preview = entriesById[id]?.libraryItem
            if (preview != null && !carAudioPlayableType(preview.type)) return null
            val item = media.getMetadata(key).getOrNull()
                ?.takeIf { carAudioPlayableType(it.type) && !it.streamUrl.isNullOrBlank() }
                ?: preview?.takeIf { !it.streamUrl.isNullOrBlank() }
                ?: return null
            if (!carAudioPlayableType(item.type)) return null
            val queue = parents[id]?.let(pages::get)?.entries.orEmpty()
                .filter { it.playable }
                .mapNotNull { CarAudioIds.musicItemId(it.id) }
            return CarAudioSelection.Library(item, queue.ifEmpty { listOf(key) })
        }
        return null
    }

    suspend fun entry(id: String): CarAudioEntry? =
        entriesById[id] ?: when (val selection = resolve(id)) {
            is CarAudioSelection.Radio -> selection.station.asEntry()
            is CarAudioSelection.Library -> selection.item.asEntry()
            is CarAudioSelection.Live -> CarAudioEntry(id, selection.channel.name,
                selection.channel.numberLabel, selection.channel.iconUrl, playable = true)
            else -> null
        }

    /** Multiple browse results for official Android Auto search UIs and voice search. */
    suspend fun search(query: String): List<CarAudioEntry> {
        val q = query.trim().take(120)
        if (q.isBlank()) return emptyList()
        val found = mutableListOf<CarAudioEntry>()
        if (preferences.radioEnabled) {
            runCatching { radio.search(query = q, limit = 30).items }
                .getOrDefault(emptyList()).mapTo(found) { it.asEntry() }
        }
        found += auxiliary.searchLive(q, limit = 20)
        found += auxiliary.searchStoredAudio(q, limit = 15)
        // UPnP/SMB have no shared remote full-text API. Search the currently browsed
        // audio folders in addition to saved file favorites/history, never video nodes.
        found += entriesById.values.filter { entry ->
            entry.playable && entry.title.contains(q, ignoreCase = true) &&
                CarAudioIds.musicItemId(entry.id)?.let { DlnaKeys.owns(it) || FileKeys.owns(it) } == true
        }.take(20)
        if (spotifyEnabled()) {
            runCatching { spotify.search(q).mapNotNull { it.asEntry() } }
                .getOrDefault(emptyList()).take(15).let(found::addAll)
        }
        val unique = found.distinctBy { it.id }.take(60)
        unique.forEach { entriesById[it.id] = it }
        while (entriesById.size > maxItems) entriesById.remove(entriesById.keys.first())
        return unique
    }

    suspend fun firstForVoiceSearch(query: String): CarAudioSelection? {
        val safeQuery = query.trim().take(120)
        if (safeQuery.isEmpty()) return null
        return search(safeQuery).firstNotNullOfOrNull { resolve(it.id) }
    }
    private companion object { const val PAGE_SIZE = 40; const val MAX_CURSOR = 1_000_000 }
}
