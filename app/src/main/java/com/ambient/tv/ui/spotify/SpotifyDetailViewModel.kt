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
    val error: String? = null,
    val offline: Boolean = false,
)

@HiltViewModel
class SpotifyDetailViewModel @Inject constructor(saved: SavedStateHandle, private val repo: SpotifyRepository, @ApplicationContext private val context: Context,
    private val companion: com.ambient.tv.companion.CompanionManager,
    private val snapshots: com.ambient.tv.data.browse.DisplaySnapshots,
    private val scopes: com.ambient.tv.data.browse.BrowseScopes,
    private val availability: com.ambient.tv.data.browse.SourceAvailability,
) : ViewModel() {
    internal val type = saved.get<String>("type").orEmpty()
    internal val id = saved.get<String>("id").orEmpty()
    private val _state = MutableStateFlow(SpotifyDetailUiState())
    val state: StateFlow<SpotifyDetailUiState> = _state.asStateFlow()
    init { load() }
    fun retryOffline() { if (_state.value.offline && availability.hasNetwork()) load() }
    fun load() {
        viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        val identity = scopes.forProvider("spotify")
        val namespace = snapshots.namespace("spotify", identity)
        val key = "detail:$type:$id"
        snapshots.read<SpotifyDetailUiState>(namespace, key)?.let {
            if (scopes.forProvider("spotify") == identity) _state.value = it.copy(offline = !availability.hasNetwork())
        }
        suspendResult {
            when (type) {
                "album" -> repo.album(id).let { SpotifyDetailUiState(false, it.name, it.artists.joinToString(", ") { a -> a.name }, it.images.firstOrNull()?.url, it.tracks) }
                "playlist" -> repo.playlist(id).let { SpotifyDetailUiState(false, it.name, listOfNotNull(it.ownerName, it.description).joinToString(" • "), it.images.firstOrNull()?.url, it.tracks) }
                "artist" -> repo.artist(id).let { detail -> SpotifyDetailUiState(false, detail.artist.name, null, detail.artist.images.firstOrNull()?.url, tracks = detail.topTracks, albums = detail.albums) }
                else -> SpotifyDetailUiState(false, com.ambient.tv.ui.common.MediaSystem.SPOTIFY.customName ?: context.getString(com.ambient.tv.R.string.spotify_title), error = context.getString(com.ambient.tv.R.string.spotify_detail_type_unsupported))
            }
        }.onSuccess {
            if (scopes.forProvider("spotify") == identity) {
                snapshots.write("spotify", namespace, key, it)
                _state.value = it
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
