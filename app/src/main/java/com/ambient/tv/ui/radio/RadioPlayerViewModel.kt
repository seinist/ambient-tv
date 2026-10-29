package com.ambient.tv.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class RadioPlayerViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val launcher: RadioPlaybackLauncher,
    private val queue: RadioPlaybackQueue,
) : ViewModel() {
    val visualizerMode: String get() = preferences.audioVisualizerModeKey
    val projectmPresetIntervalSeconds: Int get() = preferences.projectmPresetIntervalSeconds
    val projectmTransitionSeconds: Int get() = preferences.projectmTransitionSeconds
    val projectmQualityKey: String get() = preferences.projectmQualityKey
    val projectmPresetCategoryKey: String get() = preferences.projectmPresetCategoryKey

    fun hasPrevious(): Boolean = queue.previous() != null
    fun hasNext(): Boolean = queue.next() != null

    fun previous() {
        val station = queue.previous() ?: return
        viewModelScope.launch { launcher.play(station) }
    }

    fun next() {
        val station = queue.next() ?: return
        viewModelScope.launch { launcher.play(station) }
    }
}
