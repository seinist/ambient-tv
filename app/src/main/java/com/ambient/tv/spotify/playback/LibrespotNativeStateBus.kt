package com.ambient.tv.spotify.playback

import com.ambient.tv.spotify.SpotifyAlbum
import com.ambient.tv.spotify.SpotifyArtist
import com.ambient.tv.spotify.SpotifyImage
import com.ambient.tv.spotify.SpotifyPlaybackState
import com.ambient.tv.spotify.SpotifyTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Native/JNI state sink. Kept inside the librespot adapter boundary. */
internal object LibrespotNativeStateBus {
    private val mutableState = MutableStateFlow(SpotifyPlaybackState())
    val state: StateFlow<SpotifyPlaybackState> = mutableState.asStateFlow()

    @Volatile
    var accessToken: String? = null
        private set

    fun starting(name: String) {
        mutableState.update { it.copy(available = true, deviceName = name, error = null) }
    }

    fun connected(user: String?) {
        mutableState.update { it.copy(available = true, connected = true, error = null) }
    }

    fun disconnected() {
        mutableState.update {
            it.copy(
                connected = false,
                playing = false,
                current = null,
                positionMs = 0,
                durationMs = 0,
                queue = emptyList(),
            )
        }
    }

    fun token(value: String?) {
        accessToken = value?.takeIf { it.isNotBlank() }
    }

    fun error(message: String) {
        mutableState.update { it.copy(error = message) }
    }

    fun playback(
        state: String,
        title: String?,
        artist: String?,
        elapsedMs: Long,
        durationMs: Long,
        queueTitle: String?,
        queueArtist: String?,
        coverUrl: String?,
        trackId: String?,
    ) {
        val current = title?.let {
            SpotifyTrack(
                id = trackId.orEmpty(),
                name = it,
                artists = artist?.takeIf(String::isNotBlank)?.let { name ->
                    listOf(SpotifyArtist("", name))
                }.orEmpty(),
                durationMs = durationMs,
                album = SpotifyAlbum(
                    id = "",
                    name = "",
                    images = coverUrl?.takeIf(String::isNotBlank)?.let { url ->
                        listOf(SpotifyImage(url))
                    }.orEmpty(),
                ),
            )
        }
        val queue = queueTitle?.let {
            listOf(
                SpotifyTrack(
                    id = "",
                    name = it,
                    artists = queueArtist?.takeIf(String::isNotBlank)?.let { name ->
                        listOf(SpotifyArtist("", name))
                    }.orEmpty(),
                ),
            )
        }.orEmpty()
        mutableState.update {
            it.copy(
                available = true,
                connected = true,
                playing = state.equals("PLAYING", true),
                positionMs = elapsedMs,
                durationMs = durationMs,
                current = current,
                queue = queue,
                error = null,
            )
        }
    }
}
