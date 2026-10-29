package com.ambient.tv.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class RadioNowPlayingViewModel @Inject constructor(
    private val queue: RadioPlaybackQueue,
    private val launcher: RadioPlaybackLauncher,
) : ViewModel() {
    fun hasPrevious(): Boolean = queue.previous() != null
    fun hasNext(): Boolean = queue.next() != null

    fun previous() = play(queue.previous())
    fun next() = play(queue.next())

    private fun play(station: com.ambient.tv.data.radio.RadioStation?) {
        station ?: return
        viewModelScope.launch { launcher.play(station) }
    }
}
