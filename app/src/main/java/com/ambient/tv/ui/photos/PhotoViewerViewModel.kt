package com.ambient.tv.ui.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.photos.PhotoEntry
import com.ambient.tv.data.photos.PhotoExif
import com.ambient.tv.data.photos.PhotoKind
import com.ambient.tv.data.photos.PhotoRepository
import com.ambient.tv.data.photos.PhotoViewerSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhotoViewerUiState(
    val title: String = "",
    val entries: List<PhotoEntry> = emptyList(),
    /** Playback order as indices into [entries]; shuffled when shuffle is on. */
    val order: List<Int> = emptyList(),
    val position: Int = 0,
    val slideshow: Boolean = false,
    val shuffle: Boolean = false,
    val showInfo: Boolean = false,
    val exif: PhotoExif? = null,
    /** Manual rotation per entry id, in quarter turns. */
    val rotations: Map<String, Int> = emptyMap(),
    val zoom: Float = 1f,
    /** Pan offset as a fraction of the (zoomed) overflow, -1..1 per axis. */
    val panX: Float = 0f,
    val panY: Float = 0f,
    /** +1 forward, -1 backward: drives the slide transition direction. */
    val direction: Int = 1,
    val loadingSubfolders: Boolean = false,
    val intervalSeconds: Int = 6,
    val transitionKey: String = "fade",
    val kenBurns: Boolean = false,
) {
    val current: PhotoEntry? get() = order.getOrNull(position)?.let(entries::getOrNull)
    val count: Int get() = order.size
    val quarterTurns: Int get() = current?.let { rotations[it.id] } ?: 0
    fun neighbour(delta: Int): PhotoEntry? =
        if (order.isEmpty()) null else entries.getOrNull(order[Math.floorMod(position + delta, order.size)])
}

@HiltViewModel
class PhotoViewerViewModel internal constructor(
    private val session: PhotoViewerSession,
    private val preferences: AppPreferences,
    private val listRecursive: suspend (String) -> Result<List<PhotoEntry>>,
    private val detailsResult: suspend (PhotoEntry) -> Result<PhotoExif?>,
) : ViewModel() {
    @Inject constructor(session: PhotoViewerSession, repository: PhotoRepository, preferences: AppPreferences) :
        this(session, preferences, { repository.listRecursive(it) }, repository::detailsResult)

    private val request = session.request
    private val _state = MutableStateFlow(PhotoViewerUiState())
    val state: StateFlow<PhotoViewerUiState> = _state.asStateFlow()
    private var slideshowJob: Job? = null
    private var subfoldersJob: Job? = null
    private var subfoldersRevision = 0L
    private var exifJob: Job? = null
    private val exifCache = mutableMapOf<String, PhotoExif?>()

    /** False when the process was recreated and the hand-off list is gone. */
    val valid: Boolean = request != null && (request.entries.isNotEmpty() || request.startSlideshow && request.albumKey != null)

    /** Album the viewer was opened from; enables "include subfolders". Null for timeline/home lists. */
    val albumKey: String? = request?.albumKey
    var includeSubfolders: Boolean
        get() = preferences.photoIncludeSubfolders
        private set(value) { preferences.photoIncludeSubfolders = value }

    fun setIncludeSubfolders(enabled: Boolean, albumKey: String) {
        subfoldersJob?.cancel()
        subfoldersRevision++
        includeSubfolders = enabled
        if (enabled) expandSubfolders(albumKey, startSlideshow = _state.value.slideshow)
        else request?.let { req ->
            // Back to the album's own photos, keeping the current one when it is part of it.
            _state.update { state ->
                val currentId = state.current?.id
                val base = req.entries.indices.toList()
                val start = req.entries.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                val order = if (state.shuffle) shuffledOrder(base, start) else base
                state.copy(entries = req.entries, order = order, position = order.indexOf(start).coerceAtLeast(0), loadingSubfolders = false)
            }
            loadExif()
        }
    }

    init {
        val req = request
        if (req != null) {
            val shuffle = preferences.photoShuffle && req.startSlideshow
            val base = req.entries.indices.toList()
            _state.value = PhotoViewerUiState(
                title = req.title,
                entries = req.entries,
                order = if (shuffle) shuffledOrder(base, req.startIndex) else base,
                position = if (shuffle) 0 else req.startIndex.coerceIn(0, (req.entries.size - 1).coerceAtLeast(0)),
                shuffle = shuffle,
                showInfo = preferences.photoShowInfo,
                intervalSeconds = preferences.photoSlideshowIntervalSeconds,
                transitionKey = preferences.photoTransitionKey,
                kenBurns = preferences.photoKenBurns,
            )
            if (req.startSlideshow && preferences.photoIncludeSubfolders && req.albumKey != null) expandSubfolders(req.albumKey, startSlideshow = true)
            else if (req.startSlideshow) startSlideshow()
            loadExif()
        }
    }

    fun next() = move(1)
    fun previous() = move(-1)

    private fun move(delta: Int, fromSlideshow: Boolean = false) {
        val state = _state.value
        if (state.order.isEmpty()) return
        _state.update {
            it.copy(position = Math.floorMod(it.position + delta, it.order.size), direction = delta.coerceIn(-1, 1), zoom = 1f, panX = 0f, panY = 0f)
        }
        // Manual navigation restarts the timer so the new photo gets its full interval.
        if (!fromSlideshow && state.slideshow) restartTimer()
        loadExif()
    }

    fun toggleSlideshow() = if (_state.value.slideshow) stopSlideshow() else startSlideshow()

    fun startSlideshow() {
        _state.update { it.copy(slideshow = true, zoom = 1f, panX = 0f, panY = 0f) }
        restartTimer()
    }

    fun stopSlideshow() {
        slideshowJob?.cancel()
        _state.update { it.copy(slideshow = false) }
    }

    private fun restartTimer() {
        slideshowJob?.cancel()
        slideshowJob = viewModelScope.launch {
            while (true) {
                delay(_state.value.intervalSeconds * 1000L)
                // Videos are skipped by the slideshow; they only play on explicit request.
                var guard = 0
                do { move(1, fromSlideshow = true) } while (_state.value.current?.kind == PhotoKind.VIDEO && ++guard < _state.value.count)
            }
        }
    }

    fun toggleShuffle() {
        val state = _state.value
        val currentIndex = state.order.getOrNull(state.position) ?: return
        val shuffle = !state.shuffle
        preferences.photoShuffle = shuffle
        val base = state.entries.indices.toList()
        val order = if (shuffle) shuffledOrder(base, currentIndex) else base
        _state.update { it.copy(shuffle = shuffle, order = order, position = order.indexOf(currentIndex).coerceAtLeast(0)) }
    }

    fun toggleInfo() {
        val show = !_state.value.showInfo
        preferences.photoShowInfo = show
        _state.update { it.copy(showInfo = show) }
        loadExif()
    }

    fun rotate(quarterTurns: Int) {
        val id = _state.value.current?.id ?: return
        _state.update { it.copy(rotations = it.rotations + (id to Math.floorMod((it.rotations[id] ?: 0) + quarterTurns, 4))) }
    }

    fun zoomIn() = _state.update { if (it.current?.kind != PhotoKind.PHOTO) it else it.copy(zoom = (it.zoom * 2).coerceAtMost(MAX_ZOOM)) }
    fun zoomOut() = _state.update {
        val zoom = (it.zoom / 2).coerceAtLeast(1f)
        it.copy(zoom = zoom, panX = if (zoom == 1f) 0f else it.panX, panY = if (zoom == 1f) 0f else it.panY)
    }
    fun resetZoom() = _state.update { it.copy(zoom = 1f, panX = 0f, panY = 0f) }

    /** Pinch/drag input expressed in fractions of the viewport; television D-pad state is unchanged. */
    fun transform(zoomChange: Float, panFractionX: Float, panFractionY: Float) = _state.update {
        if (it.current?.kind != PhotoKind.PHOTO) return@update it
        val zoom = (it.zoom * zoomChange.takeIf(Float::isFinite).let { change -> change ?: 1f })
            .coerceIn(1f, MAX_ZOOM)
        if (zoom <= 1.01f) return@update it.copy(zoom = 1f, panX = 0f, panY = 0f)
        val overflow = (zoom - 1f).coerceAtLeast(0.01f)
        it.copy(
            zoom = zoom,
            panX = (it.panX + panFractionX / overflow).coerceIn(-1f, 1f),
            panY = (it.panY + panFractionY / overflow).coerceIn(-1f, 1f),
        )
    }

    /** D-pad panning while zoomed; each step moves a quarter of the overflow. */
    fun pan(dx: Int, dy: Int) = _state.update {
        it.copy(panX = (it.panX + dx * PAN_STEP).coerceIn(-1f, 1f), panY = (it.panY + dy * PAN_STEP).coerceIn(-1f, 1f))
    }

    fun expandSubfolders(albumKey: String, startSlideshow: Boolean) {
        subfoldersJob?.cancel()
        val revision = ++subfoldersRevision
        _state.update { it.copy(loadingSubfolders = true, slideshow = startSlideshow || it.slideshow) }
        subfoldersJob = viewModelScope.launch {
            val result = listRecursive(albumKey)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val photos = result.getOrElse {
                // A failed listing would otherwise look like empty subfolders.
                com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
                    "photos:subfolders:$albumKey", com.ambient.tv.R.string.notice_subfolders_failed,
                    phase = com.ambient.tv.notifications.NoticePhase.ERROR,
                    kind = com.ambient.tv.notifications.NoticeKind.LIBRARY, interruptPlayback = false,
                ))
                emptyList()
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (revision != subfoldersRevision) return@launch
            _state.update { state ->
                if (photos.isEmpty()) return@update state.copy(loadingSubfolders = false)
                val currentId = state.current?.id
                val base = photos.indices.toList()
                val start = photos.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                val order = if (state.shuffle) shuffledOrder(base, start) else base
                state.copy(entries = photos, order = order, position = order.indexOf(start).coerceAtLeast(0), loadingSubfolders = false)
            }
            if (_state.value.slideshow) restartTimer()
            loadExif()
        }
    }

    private fun loadExif() {
        val entry = _state.value.current ?: return
        session.lastViewedId = entry.id
        if (!_state.value.showInfo) { _state.update { it.copy(exif = entry.exif) }; return }
        if (exifCache.containsKey(entry.id)) { _state.update { it.copy(exif = exifCache[entry.id]) }; return }
        _state.update { it.copy(exif = entry.exif) }
        exifJob?.cancel()
        exifJob = viewModelScope.launch {
            val result = if (entry.kind == PhotoKind.PHOTO) detailsResult(entry) else Result.success(entry.exif)
            // Only a successful read is cached; a failed one is retried the next time.
            val exif = result.getOrNull() ?: entry.exif
            if (result.isSuccess) exifCache[entry.id] = exif
            if (_state.value.current?.id == entry.id) _state.update { it.copy(exif = exif) }
        }
    }

    private fun shuffledOrder(indices: List<Int>, first: Int): List<Int> =
        listOf(first) + indices.filter { it != first }.shuffled()

    private companion object {
        const val MAX_ZOOM = 4f
        const val PAN_STEP = 0.25f
    }
}
