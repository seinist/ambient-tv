package com.ambient.tv.ui.spotify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ambient.tv.spotify.SpotifyAudioAnalysis
import com.ambient.tv.spotify.SpotifyPlaybackState
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.player.backend.AudioSpectrumAnalyzer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpotifyPlayerViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: SpotifyRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    val state: StateFlow<SpotifyPlaybackState> = repo.playback
    val feedback = repo.feedback
    val operationFeedback = repo.operationFeedback
    val analyzer: AudioSpectrumAnalyzer get() = SpotifyAudioAnalysis.analyzer
    val visualizerMode: String get() = preferences.spotifyVisualizerModeKey
    val projectmPresetIntervalSeconds: Int get() = preferences.projectmPresetIntervalSeconds
    val projectmTransitionSeconds: Int get() = preferences.projectmTransitionSeconds
    val projectmQualityKey: String get() = preferences.projectmQualityKey
    val projectmPresetCategoryKey: String get() = preferences.projectmPresetCategoryKey
    val seekJumpMs: Long get() = preferences.bigSeekJumpSeconds * 1000L

    init {
        repo.start()
        // The route argument survives process death; start it only once, not again on restore.
        if (saved.get<Boolean>(URI_STARTED_KEY) != true) {
            saved.get<String>("uri")?.takeIf { it.isNotBlank() }?.let(repo::playUri)
            saved[URI_STARTED_KEY] = true
        }
        viewModelScope.launch {
            var cycle = 0
            while (true) {
                repo.refreshPlaybackState()
                if (cycle % 2 == 0) repo.refreshQueue()
                cycle++
                delay(15_000)
            }
        }
    }

    fun setAudioReactiveVisualsEnabled(enabled: Boolean): Boolean = repo.setAudioReactiveVisualsEnabled(enabled)
    fun setVisualizerMode(mode: String) {
        preferences.spotifyVisualizerModeKey = mode.takeIf { it in setOf("cover", "spectrum", "projectm", "off") } ?: "cover"
    }
    fun toggle() { if (state.value.playing) repo.pause() else repo.play() }
    fun play() = repo.play()
    fun pause() = repo.pause()
    fun stop() = repo.stop()
    fun next() = repo.next()
    fun previous() = repo.previous()
    fun seek(deltaMs: Long) = repo.seek((state.value.positionMs + deltaMs).coerceIn(0, state.value.durationMs.coerceAtLeast(0)))
    fun shuffle() = repo.setShuffle(!state.value.shuffle)
    fun repeat() = repo.setRepeatMode((state.value.repeatMode + 1) % 3)
    fun volume(delta: Int) = repo.setVolume((state.value.volume + delta).coerceIn(0, 100))
}

private const val URI_STARTED_KEY = "spotify_uri_started"
