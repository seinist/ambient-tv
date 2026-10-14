package com.ambient.tv.ui.youtube

import com.ambient.tv.notifications.notifyLogin
import com.ambient.tv.notifications.NoticePhase

import com.ambient.tv.util.suspendResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.MediaSelectionPreferences
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import com.ambient.tv.youtube.YouTubeContentCache
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class YouTubeFeed(val key: String) {
    HOME("home"), SUBSCRIPTIONS("subscriptions"), HISTORY("history"),
    PLAYLISTS("playlists"), SHORTS("shorts"), MUSIC("music"),
    NEWS("news"), GAMING("gaming"), LIVE("live"), KIDS("kids");

    companion object { fun fromKey(key: String) = entries.firstOrNull { it.key == key } }
}

/** YouTube has no poster artwork, so deliberately excludes Plex's poster/grid modes. */
enum class YouTubeViewMode { LANDSCAPE, LIST, INFO_LIST, WALL, CINEMATIC }
enum class YouTubeSort { DEFAULT, TITLE, DURATION }
enum class YouTubeFilter { ALL, UNWATCHED, IN_PROGRESS, LIVE, SHORTS }

data class YouTubeUiState(
    val available: Boolean = true,
    val loading: Boolean = true,
    val authBusy: Boolean = false,
    val signedIn: Boolean = false,
    val accountProfile: com.ambient.tv.youtube.YouTubeAccountProfile? = null,
    val feed: YouTubeFeed = YouTubeFeed.HOME,
    val viewMode: YouTubeViewMode = YouTubeViewMode.LANDSCAPE,
    val sort: YouTubeSort = YouTubeSort.DEFAULT,
    val filter: YouTubeFilter = YouTubeFilter.ALL,
    val gridColumns: Int = 8,
    val listRows: Int = 7,
    val mediaSelectionBehaviorKey: String = MediaSelectionPreferences.PLAY,
    val visibleFeeds: List<YouTubeFeed> = listOf(YouTubeFeed.HOME),
    val query: String = "",
    val rows: List<YouTubeRow> = emptyList(),
    val error: String? = null,
    val signInCode: String? = null,
    /** Incremented only when an inline SmartTube sign-in has actually completed. */
    val completedSignInId: Int = 0,
)

@HiltViewModel
class YouTubeViewModel @Inject constructor(
    private val engine: SmartTubeEngine,
    private val preferences: AppPreferences,
    private val contentCache: YouTubeContentCache,
) : ViewModel() {
    private fun stateKey(feed: YouTubeFeed) = "youtube:${feed.key}"
    fun focusedVideoId(feed: YouTubeFeed): String? = preferences.sectionFocus(stateKey(feed))
    fun focusedRowId(feed: YouTubeFeed): String? = preferences.sectionFocusRow(stateKey(feed))
    fun onItemFocused(feed: YouTubeFeed, rowId: String?, videoId: String) {
        preferences.setSectionFocus(stateKey(feed), videoId)
        preferences.setSectionFocusRow(stateKey(feed), rowId)
    }
    private fun viewMode(feed: YouTubeFeed): YouTubeViewMode = preferences.sectionViewMode(stateKey(feed))
        ?.let { runCatching { YouTubeViewMode.valueOf(it) }.getOrNull() } ?: YouTubeViewMode.LANDSCAPE
    private fun sort(feed: YouTubeFeed): YouTubeSort = preferences.sectionSort(stateKey(feed))
        ?.let { runCatching { YouTubeSort.valueOf(it) }.getOrNull() } ?: YouTubeSort.DEFAULT
    private fun filter(feed: YouTubeFeed): YouTubeFilter = preferences.sectionFilter(stateKey(feed))
        ?.let { runCatching { YouTubeFilter.valueOf(it) }.getOrNull() } ?: YouTubeFilter.ALL
    private val configuredFeeds: List<YouTubeFeed>
        get() = preferences.youtubeFeedKeys.mapNotNull(YouTubeFeed::fromKey).ifEmpty { listOf(YouTubeFeed.HOME) }
    private val _uiState = MutableStateFlow(
        YouTubeUiState(
            available = engine.available,
            visibleFeeds = configuredFeeds,
            viewMode = viewMode(YouTubeFeed.HOME),
            sort = sort(YouTubeFeed.HOME),
            filter = filter(YouTubeFeed.HOME),
            gridColumns = preferences.libraryGridColumns,
            listRows = preferences.libraryListRows,
            mediaSelectionBehaviorKey = preferences.youtubeMediaSelectionBehaviorKey,
        ),
    )
    private var signInWatchJob: Job? = null
    private var contentJob: Job? = null
    private var displayedContentKey: String? = null
    val uiState: StateFlow<YouTubeUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() = load(_uiState.value.feed)
    fun selectFeed(feed: YouTubeFeed) {
        _uiState.value = _uiState.value.copy(
            feed = feed,
            viewMode = viewMode(feed),
            sort = sort(feed),
            filter = filter(feed),
            query = "",
            signInCode = null,
        )
        load(feed)
    }
    fun selectFeedKey(key: String) {
        val feed = YouTubeFeed.fromKey(key) ?: return
        if (feed != _uiState.value.feed || _uiState.value.query.isNotEmpty()) selectFeed(feed)
    }
    fun selectViewMode(mode: YouTubeViewMode) {
        preferences.setSectionViewMode(stateKey(_uiState.value.feed), mode.name)
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }
    fun selectSort(sort: YouTubeSort) {
        preferences.setSectionSort(stateKey(_uiState.value.feed), sort.name)
        _uiState.value = _uiState.value.copy(sort = sort)
    }
    fun selectFilter(filter: YouTubeFilter) {
        preferences.setSectionFilter(stateKey(_uiState.value.feed), filter.name)
        _uiState.value = _uiState.value.copy(filter = filter)
    }
    fun setGridColumns(columns: Int) {
        preferences.libraryGridColumns = columns
        _uiState.value = _uiState.value.copy(gridColumns = preferences.libraryGridColumns)
    }
    fun setListRows(rows: Int) {
        preferences.libraryListRows = rows
        _uiState.value = _uiState.value.copy(listRows = preferences.libraryListRows)
    }
    fun setMediaSelectionBehavior(value: String) {
        preferences.youtubeMediaSelectionBehaviorKey = value
        _uiState.value = _uiState.value.copy(mediaSelectionBehaviorKey = preferences.youtubeMediaSelectionBehaviorKey)
    }
    fun onQueryChanged(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
        if (value.trim().length >= 2) search(value) else load(_uiState.value.feed)
    }

    fun requestSignIn() {
        if (!engine.available || _uiState.value.signedIn || _uiState.value.authBusy) return
        notifyLogin("youtube", NoticePhase.WORKING)
        viewModelScope.launch {
            val feeds = configuredFeeds
            val selected = _uiState.value.feed.takeIf { it in feeds } ?: feeds.first()
            if (selected != _uiState.value.feed || _uiState.value.visibleFeeds != feeds) {
                _uiState.value = _uiState.value.copy(feed = selected, visibleFeeds = feeds)
            }
            _uiState.value = _uiState.value.copy(authBusy = true, error = null)
            suspendResult { engine.requestSignInCode() }.onSuccess { code ->
                val signed = suspendResult { engine.isSignedIn() }.getOrDefault(false)
                if (signed) {
                    completeSignIn()
                } else {
                    _uiState.value = _uiState.value.copy(authBusy = false, signInCode = code, signedIn = false)
                    if (code != null) watchForCompletedSignIn() else notifyLogin("youtube", NoticePhase.ERROR)
                }
            }.onFailure { e ->
                notifyLogin("youtube", NoticePhase.ERROR)
                _uiState.value = _uiState.value.copy(authBusy = false, error = e.userMessage())
            }
        }
    }

    fun signOut() {
        if (!engine.available || !_uiState.value.signedIn || _uiState.value.authBusy) return
        signInWatchJob?.cancel()
        signInWatchJob = null
        contentJob?.cancel()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(authBusy = true, signInCode = null, error = null)
            suspendResult { engine.signOut() }
                .onSuccess {
                    notifyLogin("youtube", NoticePhase.INFO)
                    contentCache.clear()
                    _uiState.value = _uiState.value.copy(
                        authBusy = false,
                        signedIn = false,
                        accountProfile = null,
                        rows = emptyList(),
                        query = "",
                    )
                    load(YouTubeFeed.HOME)
                }
                .onFailure { error ->
                    notifyLogin("youtube", NoticePhase.ERROR)
                    _uiState.value = _uiState.value.copy(authBusy = false, error = error.userMessage())
                }
        }
    }

    private fun watchForCompletedSignIn() {
        signInWatchJob?.cancel()
        signInWatchJob = viewModelScope.launch {
            repeat(300) {
                delay(1_000)
                if (suspendResult { engine.isSignedIn() }.getOrDefault(false)) {
                    completeSignIn()
                    return@launch
                }
            }
            notifyLogin("youtube", NoticePhase.ERROR, message = com.ambient.tv.R.string.notice_login_expired)
            _uiState.value = _uiState.value.copy(signInCode = null, authBusy = false)
        }
    }

    private fun completeSignIn() {
        notifyLogin("youtube", NoticePhase.SUCCESS)
        contentCache.clear()
        val home = YouTubeFeed.HOME
        _uiState.value = _uiState.value.copy(
            authBusy = false,
            signedIn = true,
            feed = home,
            viewMode = viewMode(home),
            sort = sort(home),
            filter = filter(home),
            query = "",
            rows = emptyList(),
            loading = true,
            signInCode = null,
            error = null,
            completedSignInId = _uiState.value.completedSignInId + 1,
        )
        load(home)
    }

    override fun onCleared() {
        com.ambient.tv.notifications.AppNotifications.store.cancelWorking("login:youtube")
        super.onCleared()
    }

    private fun showCachedContent(key: String) {
        val cached = contentCache.get(key)
        val rows = cached ?: _uiState.value.rows.takeIf { displayedContentKey == key }.orEmpty()
        // The cache may have expired while this feed remained visible. Seed it again so the
        // first partial refresh cannot discard still-pending rows from the current screen.
        if (cached == null && rows.isNotEmpty()) contentCache.merge(key, rows)
        displayedContentKey = key
        _uiState.value = _uiState.value.copy(loading = rows.isEmpty(), error = null, rows = rows)
    }

    private suspend fun showRows(key: String, rows: List<YouTubeRow>, revision: Long) = withContext(Dispatchers.Main.immediate) {
        if (displayedContentKey == key && rows.isNotEmpty() && contentCache.revision() == revision) {
            _uiState.value = _uiState.value.copy(loading = false, error = null, rows = contentCache.merge(key, rows, revision))
        }
    }

    private fun load(feed: YouTubeFeed) {
        contentJob?.cancel()
        showCachedContent("feed:${feed.key}")
        contentJob = viewModelScope.launch {
            contentCache.restore()
            val cacheRevision = contentCache.revision()
            if (!engine.available) {
                _uiState.value = _uiState.value.copy(available = false, loading = false, error = null)
                return@launch
            }
            val supported = suspendResult { engine.supportedFeedKeys() }.getOrDefault(setOf("home"))
            val feeds = configuredFeeds.filter { it.key in supported }.ifEmpty { listOf(YouTubeFeed.HOME) }
            val selectedFeed = feed.takeIf { it in feeds } ?: feeds.first()
            if (_uiState.value.visibleFeeds != feeds || _uiState.value.feed != selectedFeed) {
                _uiState.value = _uiState.value.copy(
                    visibleFeeds = feeds,
                    feed = selectedFeed,
                    viewMode = viewMode(selectedFeed),
                    sort = sort(selectedFeed),
                    filter = filter(selectedFeed),
                )
            }
            val cacheKey = "feed:${selectedFeed.key}"
            showCachedContent(cacheKey)
            suspendResult {
                val signed = engine.isSignedIn()
                val profile = if (signed) suspendResult { engine.accountProfile() }.getOrNull() else null
                _uiState.value = _uiState.value.copy(signedIn = signed, accountProfile = profile)
                var rows = engine.feed(selectedFeed.key) { rows -> showRows(cacheKey, rows, cacheRevision) }
                // MediaServiceCore can report an empty Home feed while its services are still
                // warming up on the first app start. A manual refresh then works immediately,
                // so retry only that initial empty Home response for a short bounded window.
                if (selectedFeed == YouTubeFeed.HOME && rows.isEmpty() && contentCache.get(cacheKey) == null) {
                    for (attempt in 0..2) {
                        delay(400L * (attempt + 1))
                        rows = engine.feed(selectedFeed.key) { rows -> showRows(cacheKey, rows, cacheRevision) }
                        if (rows.isNotEmpty()) break
                    }
                }
                signed to rows
            }.onSuccess { (signed, rows) ->
                if (contentCache.revision() != cacheRevision) return@onSuccess
                _uiState.value = _uiState.value.copy(loading = false, signedIn = signed, rows = contentCache.replace(cacheKey, rows, cacheRevision))
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(loading = false, error = e.userMessage().takeIf { _uiState.value.rows.isEmpty() })
            }
        }
    }

    private fun search(query: String) {
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            contentCache.restore()
            val cacheRevision = contentCache.revision()
            delay(350)
            val cacheKey = "search:${query.trim().lowercase()}"
            showCachedContent(cacheKey)
            suspendResult { engine.search(query) { rows -> showRows(cacheKey, rows, cacheRevision) } }
                .onSuccess { rows -> if (_uiState.value.query == query && contentCache.revision() == cacheRevision) _uiState.value = _uiState.value.copy(loading = false, rows = contentCache.replace(cacheKey, rows, cacheRevision)) }
                .onFailure { e -> if (_uiState.value.query == query && _uiState.value.rows.isEmpty()) _uiState.value = _uiState.value.copy(loading = false, error = e.userMessage()) }
        }
    }
}

private fun Throwable.userMessage() = message?.takeIf { it.isNotBlank() } ?: "YouTube konnte nicht geladen werden."

internal fun applyYouTubeViewOptions(
    rows: List<YouTubeRow>,
    sort: YouTubeSort,
    filter: YouTubeFilter,
): List<YouTubeRow> = rows.mapNotNull { row ->
    val filtered = row.items.filter { item ->
        when (filter) {
            YouTubeFilter.ALL -> true
            YouTubeFilter.UNWATCHED -> item.percentWatched <= 0
            YouTubeFilter.IN_PROGRESS -> item.percentWatched in 1..99
            YouTubeFilter.LIVE -> item.isLive || item.isUpcoming
            YouTubeFilter.SHORTS -> item.isShorts
        }
    }
    val sorted = when (sort) {
        YouTubeSort.DEFAULT -> filtered
        YouTubeSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
        YouTubeSort.DURATION -> filtered.sortedWith(compareByDescending<YouTubeItem> { it.durationMs }.thenBy { it.title.lowercase() })
    }
    row.copy(items = sorted).takeIf { it.items.isNotEmpty() }
}
