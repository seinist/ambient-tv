package com.ambient.tv.ui.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.photos.PhotoEntry
import com.ambient.tv.data.photos.PhotoKind
import com.ambient.tv.data.photos.PhotoRepository
import com.ambient.tv.data.photos.PhotoSort
import com.ambient.tv.data.photos.PhotoViewerSession
import com.ambient.tv.data.photos.sortedForDisplay
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhotoAlbumLevel(val key: String, val title: String)

/** A month bucket of the timeline; photos without a date go into [month] = null. */
data class PhotoTimelineGroup(val month: YearMonth?, val entries: List<PhotoEntry>)

data class PhotoBrowserUiState(
    val stack: List<PhotoAlbumLevel> = emptyList(),
    val entries: List<PhotoEntry> = emptyList(),
    val loading: Boolean = true,
    /** A request is still running even while the previous album remains visible. */
    val refreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val failed: Boolean = false,
    val sort: PhotoSort = PhotoSort.DATE_DESC,
    val timeline: Boolean = false,
    val timelineGroups: List<PhotoTimelineGroup> = emptyList(),
    /** Entry id to focus after returning from a sub-album or the viewer. */
    val focusId: String? = null,
) {
    val current: PhotoAlbumLevel? get() = stack.lastOrNull()
    val atRoot: Boolean get() = stack.size <= 1
    val photos: List<PhotoEntry> get() = entries.filter { it.kind != PhotoKind.FOLDER }
}

@HiltViewModel
class PhotoBrowserViewModel internal constructor(
    private val session: PhotoViewerSession,
    private val preferences: AppPreferences,
    private val listAlbum: suspend (String) -> Result<List<PhotoEntry>>,
    private val listTimeline: suspend (String) -> Result<List<PhotoEntry>>,
) : ViewModel() {
    @Inject constructor(repository: PhotoRepository, session: PhotoViewerSession, preferences: AppPreferences) :
        this(session, preferences, repository::list, { repository.listRecursive(it) })

    private val _state = MutableStateFlow(PhotoBrowserUiState(sort = PhotoSort.fromKey(preferences.photoSortKey)))
    val state: StateFlow<PhotoBrowserUiState> = _state.asStateFlow()
    private var job: Job? = null
    private var loadGeneration = 0L
    /** Focus to restore per album when stepping back up. */
    private val focusByAlbum = mutableMapOf<String, String>()

    fun enter(rootKey: String, title: String) {
        if (_state.value.stack.firstOrNull()?.key != rootKey) {
            _state.value = PhotoBrowserUiState(stack = listOf(PhotoAlbumLevel(rootKey, title)), sort = _state.value.sort)
        }
        applyViewerReturn()
        refresh()
    }

    fun openFolder(entry: PhotoEntry) {
        val current = _state.value.current ?: return
        focusByAlbum[current.key] = entry.id
        _state.update { it.copy(stack = it.stack + PhotoAlbumLevel(entry.id, entry.title), timeline = false, focusId = null) }
        load()
    }

    /** Back inside the browser; false when already at the library root. */
    fun back(): Boolean {
        val state = _state.value
        if (state.timeline) { leaveTimeline(); return true }
        if (state.atRoot) return false
        val parent = state.stack.dropLast(1)
        _state.update { it.copy(stack = parent, focusId = focusByAlbum[parent.last().key]) }
        load()
        return true
    }

    fun retry() { if (_state.value.timeline) loadTimeline() else load() }
    fun refresh() { if (_state.value.timeline) loadTimeline(silent = true) else load(silent = true) }
    fun reload() {
        if (_state.value.refreshing || job?.isActive == true) return
        if (_state.value.timeline) loadTimeline(silent = true, manual = true)
        else load(silent = true, manual = true)
    }

    fun setSort(sort: PhotoSort) {
        preferences.photoSortKey = sort.key
        _state.update { it.copy(sort = sort, entries = it.entries.sortedForDisplay(sort)) }
    }

    /** Date starts at newest, name at A–Z. Staying on the same field keeps the direction. */
    fun setSortField(byDate: Boolean) {
        val current = _state.value.sort
        if (current.byDate == byDate) return
        setSort(PhotoSort.of(byDate, descending = byDate))
    }

    fun toggleSortOrder() {
        val current = _state.value.sort
        setSort(PhotoSort.of(current.byDate, !current.descending))
    }

    fun toggleTimeline() {
        val enable = !_state.value.timeline
        if (!enable) { leaveTimeline(); return }
        _state.update { it.copy(timeline = true) }
        loadTimeline()
    }

    /** Back to the album grid: a timeline error must not stay on it, and a cancelled album load is redone. */
    private fun leaveTimeline() {
        val reload = _state.value.loading || _state.value.failed
        ++loadGeneration
        job?.cancel()
        _state.update { it.copy(timeline = false, failed = false, refreshing = false, refreshFailed = false) }
        if (reload) load()
    }

    fun rememberFocus(id: String) {
        _state.value.current?.let { focusByAlbum[it.key] = id }
        _state.update { it.copy(focusId = id) }
    }

    /** After the viewer: focus the photo that was shown last, when it belongs to this album. */
    fun applyViewerReturn() {
        val id = session.lastViewedId ?: return
        session.lastViewedId = null
        val state = _state.value
        val visible = if (state.timeline) state.timelineGroups.flatMap { it.entries } else state.entries
        if (visible.any { it.id == id }) rememberFocus(id)
    }

    /** Prepares the viewer for [entry] within the visible photo list. */
    fun prepareViewer(entry: PhotoEntry, slideshow: Boolean = false) {
        val state = _state.value
        val list = if (state.timeline) state.timelineGroups.flatMap { it.entries } else state.photos
        rememberFocus(entry.id)
        session.request = PhotoViewerSession.Request(
            title = state.current?.title.orEmpty(),
            entries = list,
            startIndex = list.indexOf(entry).coerceAtLeast(0),
            startSlideshow = slideshow,
            albumKey = if (state.timeline) null else state.current?.key,
        )
    }

    /** Slideshow over the current album, from the first photo. */
    fun prepareSlideshow(): Boolean {
        val state = _state.value
        val list = if (state.timeline) state.timelineGroups.flatMap { it.entries } else state.photos
        if (list.isEmpty() && !preferences.photoIncludeSubfolders) return false
        session.request = PhotoViewerSession.Request(
            title = state.current?.title.orEmpty(),
            entries = list,
            startIndex = 0,
            startSlideshow = true,
            // The timeline already spans all subfolders; it is not the album's own photo list.
            albumKey = if (state.timeline) null else state.current?.key,
        )
        return true
    }

    private fun load(silent: Boolean = false, manual: Boolean = false) {
        val key = _state.value.current?.key ?: return
        val generation = ++loadGeneration
        job?.cancel()
        _state.update { it.copy(loading = !silent || it.loading, failed = false, refreshing = true, refreshFailed = false) }
        job = viewModelScope.launch {
            try {
                val result = listAlbum(key)
                currentCoroutineContext().ensureActive()
                if (generation != loadGeneration) return@launch
                result
                    .onSuccess { entries -> _state.update { it.copy(loading = false, entries = entries.sortedForDisplay(it.sort)) } }
                    .onFailure { _state.update { it.copy(loading = false,
                        failed = !silent || it.entries.isEmpty(), entries = if (silent) it.entries else emptyList(),
                        refreshFailed = manual && silent && it.entries.isNotEmpty()) } }
            } finally {
                if (generation == loadGeneration) _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun loadTimeline(silent: Boolean = false, manual: Boolean = false) {
        val key = _state.value.current?.key ?: return
        val generation = ++loadGeneration
        job?.cancel()
        _state.update { it.copy(loading = !silent || it.loading, failed = false, refreshing = true, refreshFailed = false) }
        job = viewModelScope.launch {
            try {
                val result = listTimeline(key)
                currentCoroutineContext().ensureActive()
                if (generation != loadGeneration) return@launch
                result
                    .onSuccess { photos ->
                        val zone = ZoneId.systemDefault()
                        val groups = photos
                            .sortedByDescending { it.takenAt ?: it.addedAt ?: Long.MIN_VALUE }
                            .groupBy { entry ->
                                (entry.takenAt ?: entry.addedAt)?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(zone)) }
                            }
                            .map { (month, entries) -> PhotoTimelineGroup(month, entries) }
                        _state.update { it.copy(loading = false, timelineGroups = groups) }
                    }
                    .onFailure { _state.update { it.copy(loading = false, failed = !silent || it.timelineGroups.isEmpty(),
                        refreshFailed = manual && silent && it.timelineGroups.isNotEmpty()) } }
            } finally {
                if (generation == loadGeneration) _state.update { it.copy(refreshing = false) }
            }
        }
    }
}
