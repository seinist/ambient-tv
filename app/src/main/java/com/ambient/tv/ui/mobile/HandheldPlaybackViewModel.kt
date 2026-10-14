package com.ambient.tv.ui.mobile

import androidx.lifecycle.ViewModel
import com.ambient.tv.spotify.SpotifyPlaybackState
import com.ambient.tv.spotify.SpotifyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Passive miniplayer: observing state never starts Spotify or polls its queue. */
@HiltViewModel
class HandheldPlaybackViewModel @Inject constructor(
    private val spotify: SpotifyRepository,
) : ViewModel() {
    val spotifyPlayback: StateFlow<SpotifyPlaybackState> = spotify.playback

    fun toggleSpotify() {
        if (spotifyPlayback.value.playing) spotify.pause() else spotify.play()
    }

    fun stopSpotify() = spotify.stop()
}
