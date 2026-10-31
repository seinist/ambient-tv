package com.ambient.tv.car

import android.os.SystemClock
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.radio.RadioBrowserRepository
import com.ambient.tv.data.radio.RadioStation
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
}

/** Read-only, driver-safe browse facade. Existing repositories and playback engines own the data. */
@Singleton
internal class CarAudioCatalog @Inject constructor(
    private val preferences: AppPreferences,
    private val radio: RadioBrowserRepository,
    private val spotify: SpotifyRepository,
    private val media: RoutingMediaRepository,
) {
    private data class Page(val entries: List<CarAudioEntry>, val loadedAt: Long)
    private val pages = LinkedHashMap<String, Page>()
    private val entriesById = LinkedHashMap<String, CarAudioEntry>()
    private val parents = LinkedHashMap<String, String>()
    private val maxItems = 500

    suspend fun children(parentId: String): List<CarAudioEntry> {
        if (!CarAudioIds.isFolder(parentId)) return emptyList()
        if (parentId == CarAudioIds.ROOT) return root()
        val now = SystemClock.elapsedRealtime()
        pages[parentId]?.takeIf { now - it.loadedAt < 45_000 }?.let { return it.entries }
        val fresh = try {
            withTimeoutOrNull(9_000) { loadPage(parentId) } ?: emptyList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Connectivity must not make the Auto UI jump to a temporary empty page.
            return pages[parentId]?.entries.orEmpty()
        }
        if (fresh.isEmpty() && pages[parentId]?.entries?.isNotEmpty() == true) {
            return pages.getValue(parentId).entries
        }
        val result = fresh.distinctBy { it.id }.take(80)
        pages[parentId] = Page(result, now)
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
        if (preferences.radioEnabled) {
            add(folder(CarAudioIds.RADIO_FAVORITES, "Radio-Favoriten"))
            add(folder(CarAudioIds.RADIO_RECENT, "Zuletzt gehörte Radios"))
            add(folder(CarAudioIds.RADIO_POPULAR, "Beliebte Radios"))
        }
        if (preferences.spotifyEnabled && spotify.webAuthorized) {
            add(folder(CarAudioIds.SPOTIFY_HOME, "Spotify"))
            add(folder(CarAudioIds.SPOTIFY_LIKED, "Lieblingssongs"))
            add(folder(CarAudioIds.SPOTIFY_PLAYLISTS, "Spotify-Playlists"))
            add(folder(CarAudioIds.SPOTIFY_ALBUMS, "Spotify-Alben"))
            add(folder(CarAudioIds.SPOTIFY_ARTISTS, "Spotify-Künstler"))
        }
        add(folder(CarAudioIds.MUSIC_LIBRARIES, "Musikbibliotheken"))
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

    private suspend fun loadPage(parentId: String): List<CarAudioEntry> = when (parentId) {
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
        CarAudioIds.stationId(id)?.let { stationId ->
            if (!preferences.radioEnabled) return null
            val station = entriesById[id]?.radioStation
                ?: preferences.radioFavorites.firstOrNull { it.id == stationId }
                ?: preferences.recentRadioStations.firstOrNull { it.id == stationId }
                ?: radio.station(stationId) ?: return null
            val siblings = pages[parents[id]]?.entries.orEmpty()
                .mapNotNull { it.radioStation }
            return CarAudioSelection.Radio(station, siblings.ifEmpty { listOf(station) })
        }
        CarAudioIds.spotifyTrackId(id)?.let { trackId ->
            if (!spotifyEnabled()) return null
            return CarAudioSelection.Spotify("spotify:track:$trackId")
        }
        CarAudioIds.musicItemId(id)?.let { key ->
            val item = entriesById[id]?.libraryItem
                ?: media.getMetadata(key).getOrNull() ?: return null
            if (!carAudioPlayableType(item.type) || item.streamUrl.isNullOrBlank()) return null
            val queue = pages[parents[id]]?.entries.orEmpty().filter { it.playable && it.libraryItem != null }
                .mapNotNull { it.libraryItem?.ratingKey }
            return CarAudioSelection.Library(item, queue.ifEmpty { listOf(key) })
        }
        return null
    }

    suspend fun entry(id: String): CarAudioEntry? =
        entriesById[id] ?: when (val selection = resolve(id)) {
            is CarAudioSelection.Radio -> selection.station.asEntry()
            is CarAudioSelection.Library -> selection.item.asEntry()
            else -> null
        }

    suspend fun firstForVoiceSearch(query: String): CarAudioSelection? {
        val safeQuery = query.trim().take(120)
        if (safeQuery.isEmpty()) return null
        if (preferences.radioEnabled) {
            val found = radio.search(query = safeQuery, limit = 15).items.firstOrNull()
            if (found != null) return CarAudioSelection.Radio(found, listOf(found))
        }
        if (spotifyEnabled()) {
            spotify.search(safeQuery).firstOrNull {
                it.type == SpotifyEntityType.TRACK && it.uri.startsWith("spotify:track:")
            }?.let { return CarAudioSelection.Spotify("spotify:track:${it.id}") }
        }
        return null
    }
}
