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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    // An independently moving cursor prevents key repeats from choosing the old stream's
    // neighbor again. Cancelable, debounced requests only open the last selected station.
    private var zapCursor: RadioZapCursor? = null
    private var zapJob: Job? = null
    private var zapVersion = 0
    private var zapRevision by mutableIntStateOf(0)
    /** True while a newer station is selected but its stream is not committed. */
    var switchingStation by mutableStateOf(false)
        private set
    /** Preview the *target* artwork immediately; never replace the whole player with a splash. */
    var pendingStation by mutableStateOf<RadioStation?>(null)
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

    fun hasPrevious(): Boolean {
        zapRevision
        queueRevision
        val snapshot = queue.snapshot()
        return zapCursor?.canMove(snapshot, -1) ?: (queue.previous() != null)
    }

    fun hasNext(): Boolean {
        // The candidate follows the current *requested* station, not the still-playing one.
        // An unresolved next page stays navigable and every press is counted.
        zapRevision
        queueRevision
        val snapshot = queue.snapshot()
        return zapCursor?.canMove(snapshot, 1)
            ?: (queue.next() != null || snapshot.source?.hasMore == true)
    }

    fun isFavorite(stationId: String): Boolean = preferences.isRadioFavorite(stationId)

    fun toggleFavorite() {
        val station = queue.current() ?: return
        val next = !preferences.isRadioFavorite(station.id)
        preferences.setRadioFavorite(station, next)
        favorite = next
    }

    fun previous() = move(-1)

    fun next() = move(1)

    /**
     * Every directional press advances an in-memory target immediately. Neither a pending
     * Radio Browser API page nor an in-flight playback URL request can swallow further keys.
     * Replacing the job cancels obsolete resolution/starts; only the final, settled target
     * reaches RadioPlaybackLauncher and changes the shared queue's committed playback ID.
     */
    private fun move(direction: Int) {
        val snapshot = queue.snapshot()
        val cursor = zapCursor?.takeIf { it.requestedIndex(snapshot) != null }
            ?: RadioZapCursor(snapshot.currentId ?: return)
        if (!cursor.move(snapshot, direction)) return

        zapCursor = cursor
        ++zapVersion
        ++zapRevision
        zapJob?.cancel()

        val target = cursor.target(snapshot)
        if (target?.id == snapshot.currentId) {
            // A quick Next then Previous returned to the playing stream. No new connection.
            zapCursor = null
            zapJob = null
            switchingStation = false
            pendingStation = null
            return
        }

        // The pending target owns the header artwork/labels even before network playback.
        // Null is intentional while seeking beyond the loaded page: the OSD shows Loading,
        // never the outgoing station's ICY metadata.
        pendingStation = target
        switchingStation = true
        val revision = zapVersion
        zapJob = viewModelScope.launch {
            try {
                delay(RADIO_ZAP_SETTLE_MS)
                val resolved = resolveZapTarget(cursor)
                if (resolved != null && resolved.id != queue.snapshot().currentId) {
                    launcher.play(resolved).onFailure {
                        Timber.w(it, "Radio rapid-zap station %s could not start", resolved.id)
                    }
                }
            } finally {
                // A newer key may have canceled this job while the API was still resolving.
                // Never let its stale cleanup clear the newer pending OSD station.
                if (revision == zapVersion) {
                    zapCursor = null
                    zapJob = null
                    pendingStation = null
                    switchingStation = false
                    ++zapRevision
                }
            }
        }
    }

    /** Continue the original browse facet and offset until the requested index exists. */
    private suspend fun resolveZapTarget(cursor: RadioZapCursor): RadioStation? {
        var pages = 0
        while (cursor.needsPage(queue.snapshot()) && pages++ < MAX_RADIO_ZAP_PAGES) {
            val source = queue.snapshot().source?.takeIf { it.hasMore } ?: break
            val page = try {
                repository.search(
                    query = source.query,
                    countryCode = source.countryCode,
                    language = source.language,
                    tag = source.tag,
                    offset = source.nextOffset,
                    limit = RADIO_PAGE_SIZE,
                    order = "name",
                    reverse = source.sortDescending,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Radio Browser next page failed during rapid zapping")
                break
            }
            if (!queue.appendPage(source, page)) break
            ++queueRevision
            // Preserve the requested UUID when this page resolves it: subsequent page sorts
            // can insert rows before it without retargeting playback.
            cursor.stabilize(queue.snapshot())
            pendingStation = cursor.target(queue.snapshot())
        }
        val snapshot = queue.snapshot()
        cursor.stabilize(snapshot)
        if (cursor.target(snapshot) == null) cursor.clampToLoaded(snapshot)
        return cursor.target(snapshot).also { pendingStation = it }
    }

    private companion object {
        const val RADIO_PAGE_SIZE = 48
        const val MAX_RADIO_ZAP_PAGES = 12
    }

    private fun refreshFavorite(mediaId: String?) {
        favorite = mediaId?.let(preferences::isRadioFavorite) == true
    }
}
