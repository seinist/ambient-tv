package com.ambient.tv.ui.radio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.data.radio.RadioBrowserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
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
    private val repository: RadioBrowserRepository,
) : ViewModel() {
    private val nextMutex = Mutex()
    /** Hide the outgoing station's information until its replacement has settled. */
    var switchingStation by mutableStateOf(false)
        private set
    private var queueRevision by mutableIntStateOf(0)
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
    fun hasNext(): Boolean {
        // Observe pages fetched while this screen is open, even before the next stream starts.
        queueRevision
        return queue.next() != null || queue.snapshot().source?.hasMore == true
    }

    fun toggleFavorite() {
        val station = queue.current() ?: return
        val next = !preferences.isRadioFavorite(station.id)
        preferences.setRadioFavorite(station, next)
        favorite = next
    }

    fun previous() {
        if (switchingStation) return
        val station = queue.previous() ?: return
        switchingStation = true
        viewModelScope.launch {
            try { launcher.play(station) } finally { switchingStation = false }
        }
    }

    fun next() {
        if (switchingStation || (queue.next() == null && queue.snapshot().source?.hasMore != true)) return
        switchingStation = true
        viewModelScope.launch {
            try {
            nextMutex.withLock {
                var station = queue.next()
                var attempts = 0
                // The loaded page ending is not the API's ending. Keep the original facet,
                // search and raw offset; never substitute Popular or a different queue.
                while (station == null && attempts++ < MAX_EMPTY_RADIO_PAGES) {
                    val source = queue.snapshot().source?.takeIf { it.hasMore } ?: break
                    val page = try {
                        repository.search(
                            query = source.query,
                            countryCode = source.countryCode,
                            language = source.language,
                            tag = source.tag,
                            offset = source.nextOffset,
                            limit = RADIO_PAGE_SIZE,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Timber.w(error, "Radio Browser continuation failed in original category")
                        break // Keep hasMore and the cursor, so the user can retry Next.
                    }
                    if (!queue.appendPage(source, page)) break
                    queueRevision++
                    station = queue.next()
                }
                station?.let { launcher.play(it) }
            }
            } finally {
                switchingStation = false
            }
        }
    }

    private companion object {
        const val RADIO_PAGE_SIZE = 48
        const val MAX_EMPTY_RADIO_PAGES = 6
    }

    private fun refreshFavorite(mediaId: String?) {
        favorite = mediaId?.let(preferences::isRadioFavorite) == true
    }
}
