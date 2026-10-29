package com.ambient.tv.ui.radio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltViewModel
class RadioPlayerViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val launcher: RadioPlaybackLauncher,
    private val queue: RadioPlaybackQueue,
) : ViewModel() {
    var visualizerMode by mutableStateOf(preferences.audioVisualizerModeKey)
        private set
    var projectmPresetIntervalSeconds by mutableIntStateOf(preferences.projectmPresetIntervalSeconds)
        private set
    var projectmTransitionSeconds by mutableIntStateOf(preferences.projectmTransitionSeconds)
        private set
    var projectmQualityKey by mutableStateOf(preferences.projectmQualityKey)
        private set
    var projectmPresetCategoryKey by mutableStateOf(preferences.projectmPresetCategoryKey)
        private set
    var favorite by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            BackgroundPlaybackSession.snapshot
                .map { snapshot ->
                    snapshot.metadata?.takeIf { it.source == "radio" }?.mediaId
                }
                .distinctUntilChanged()
                .collect(::refreshFavorite)
        }
        viewModelScope.launch {
            preferences.changes().collect {
                visualizerMode = preferences.audioVisualizerModeKey
                projectmPresetIntervalSeconds = preferences.projectmPresetIntervalSeconds
                projectmTransitionSeconds = preferences.projectmTransitionSeconds
                projectmQualityKey = preferences.projectmQualityKey
                projectmPresetCategoryKey = preferences.projectmPresetCategoryKey
                refreshFavorite(
                    BackgroundPlaybackSession.snapshot.value.metadata
                        ?.takeIf { it.source == "radio" }
                        ?.mediaId,
                )
            }
        }
    }

    fun currentStation(): RadioStation? = queue.current()

    fun hasPrevious(): Boolean = queue.previous() != null
    fun hasNext(): Boolean = queue.next() != null

    fun toggleFavorite() {
        val station = queue.current() ?: return
        val next = !preferences.isRadioFavorite(station.id)
        preferences.setRadioFavorite(station, next)
        favorite = next
    }

    fun previous() {
        val station = queue.previous() ?: return
        viewModelScope.launch { launcher.play(station) }
    }

    fun next() {
        val station = queue.next() ?: return
        viewModelScope.launch { launcher.play(station) }
    }

    private fun refreshFavorite(mediaId: String?) {
        favorite = mediaId?.let(preferences::isRadioFavorite) == true
    }
}
