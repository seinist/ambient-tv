package com.ambient.tv.ui.spotify

import com.ambient.tv.util.suspendResult
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.spotify.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@kotlinx.serialization.Serializable
data class SpotifyDetailUiState(
    val loading: Boolean = true,
    val title: String = "",
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val tracks: List<SpotifyTrack> = emptyList(),
    val albums: List<SpotifyAlbum> = emptyList(),
    /** Playlist description (the subtitle is the artist / owner). */
    val description: String? = null,
    val releaseDate: String? = null,
    /** A wide image for the backdrop (artists only, from a media server or fanart.tv). */
    val backdropUrl: String? = null,
    val error: String? = null,
    val offline: Boolean = false,
)

@HiltViewModel
class SpotifyDetailViewModel @Inject constructor(saved: SavedStateHandle, private val repo: SpotifyRepository, @ApplicationContext private val context: Context,
    private val companion: com.ambient.tv.companion.CompanionManager,
    private val snapshots: com.ambient.tv.data.browse.DisplaySnapshots,
    private val scopes: com.ambient.tv.data.browse.BrowseScopes,
    private val availability: com.ambient.tv.data.browse.SourceAvailability,
    private val artistBackdrops: com.ambient.tv.spotify.ArtistBackdrops,
) : ViewModel() {
    internal val type = saved.get<String>("type").orEmpty()
    internal val id = saved.get<String>("id").orEmpty()
    private val _state = MutableStateFlow(SpotifyDetailUiState())
    val state: StateFlow<SpotifyDetailUiState> = _state.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null
    fun retryOffline() { if (_state.value.offline && availability.hasNetwork() && loadJob?.isActive != true) load() }
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
        _state.value = _state.value.copy(loading = _state.value.title.isBlank(), error = null)
        val identity = scopes.forProvider("spotify")
        val namespace = snapshots.namespace("spotify", identity)
        val key = "detail:$type:$id"
        if (_state.value.title.isBlank()) snapshots.read<SpotifyDetailUiState>(namespace, key)?.let {
            if (scopes.forProvider("spotify") == identity) _state.value = it.copy(offline = !availability.hasNetwork())
        }
        suspendResult {
            when (type) {
                "album" -> repo.album(id).let { SpotifyDetailUiState(false, it.name, it.artists.joinToString(", ") { a -> a.name }, it.images.firstOrNull()?.url, it.tracks, releaseDate = it.releaseDate) }
                "playlist" -> repo.playlist(id).let { SpotifyDetailUiState(false, it.name, it.ownerName, it.images.firstOrNull()?.url, it.tracks, description = it.description?.let(::plainPlaylistDescription)) }
                "artist" -> repo.artist(id).let { detail -> SpotifyDetailUiState(false, detail.artist.name, null, detail.artist.images.firstOrNull()?.url, tracks = detail.topTracks, albums = detail.albums) }
                else -> SpotifyDetailUiState(false, com.ambient.tv.ui.common.MediaSystem.SPOTIFY.customName ?: context.getString(com.ambient.tv.R.string.spotify_title), error = context.getString(com.ambient.tv.R.string.spotify_detail_type_unsupported))
            }
        }.onSuccess {
            if (scopes.forProvider("spotify") == identity) {
                // Keep a wide backdrop found earlier while the new one is looked up.
                val loaded = it.copy(backdropUrl = it.backdropUrl ?: _state.value.backdropUrl)
                snapshots.write("spotify", namespace, key, loaded)
                _state.value = loaded
                if (type == "artist" && loaded.backdropUrl == null) {
                    artistBackdrops.find(loaded.title)?.let { wide ->
                        val withBackdrop = _state.value.copy(backdropUrl = wide)
                        _state.value = withBackdrop
                        snapshots.write("spotify", namespace, key, withBackdrop)
                    }
                }
            }
        }.onFailure {
            _state.value = _state.value.copy(loading = false, offline = true, error = it.message.takeIf { _state.value.title.isBlank() })
        }
        }
    }
    fun playContext(onLocalStarted: () -> Unit = {}) {
        companion.requestPlayback(com.ambient.tv.companion.CompanionMedia("spotify:$type", id), state.value.title) {
            repo.playUri("spotify:$type:$id")
            onLocalStarted()
        }
    }
    fun playTrack(track: SpotifyTrack, onLocalStarted: () -> Unit = {}) {
        val media = if (type == "artist" || companion.state.value.enqueueSelection)
            com.ambient.tv.companion.CompanionMedia("spotify:track", track.id)
        else com.ambient.tv.companion.CompanionMedia("spotify:$type", id, offsetId = track.id)
        companion.requestPlayback(media, track.name) {
            if (type == "artist") repo.playUri(track.uri)
            else repo.playUri("spotify:$type:$id", offsetUri = track.uri)
            onLocalStarted()
        }
    }
}

/** Spotify playlist descriptions may contain HTML links and entities. */
internal fun plainPlaylistDescription(raw: String): String? =
    androidx.core.text.HtmlCompat.fromHtml(raw, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
        .toString().trim().takeIf(String::isNotBlank)
