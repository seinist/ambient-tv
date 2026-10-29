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
import com.ambient.tv.youtube.YouTubeRowPaging
import com.ambient.tv.youtube.refreshYouTubeRowExtents
import com.ambient.tv.data.browse.BrowseWorkBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
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
    HOME("home"), SUBSCRIPTIONS("subscriptions"), CHANNELS("channels"), HISTORY("history"),
    PLAYLISTS("playlists"), SHORTS("shorts"), MUSIC("music"),
    NEWS("news"), GAMING("gaming"), LIVE("live"), KIDS("kids");

    companion object { fun fromKey(key: String) = entries.firstOrNull { it.key == key } }
}

/** YouTube has no poster artwork, so deliberately excludes Plex's poster/grid modes. */
enum class YouTubeViewMode { LANDSCAPE, LIST, INFO_LIST, WALL, CINEMATIC }

data class YouTubeUiState(
    val available: Boolean = true,
    val loading: Boolean = true,
    val authBusy: Boolean = false,
    val signedIn: Boolean = false,
    val accountProfile: com.ambient.tv.youtube.YouTubeAccountProfile? = null,
    val feed: YouTubeFeed = YouTubeFeed.HOME,
    val viewMode: YouTubeViewMode = YouTubeViewMode.LANDSCAPE,
    val sort: YouTubeSort = YouTubeSort.DEFAULT,
    val sortDescending: Boolean = false,
    val filter: YouTubeFilter = YouTubeFilter.ALL,
    val gridColumns: Int = 8,
    val listRows: Int = 7,
    val mediaSelectionBehaviorKey: String = MediaSelectionPreferences.PLAY,
    val visibleFeeds: List<YouTubeFeed> = listOf(YouTubeFeed.HOME),
    val query: String = "",
    val rows: List<YouTubeRow> = emptyList(),
    /** Rows whose next page loads because focus neared their end (end-of-row placeholders). */
    val loadingRows: Set<String> = emptySet(),
    val error: String? = null,
    val signInCode: String? = null,
    /** Incremented only when an inline SmartTube sign-in has actually completed. */
    val completedSignInId: Int = 0,
    /** Channel page: header of the channel whose rows are shown instead of a feed. */
    val channel: com.ambient.tv.youtube.YouTubeChannelInfo? = null,
)

@HiltViewModel
class YouTubeViewModel @Inject constructor(
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val engine: SmartTubeEngine,
    private val preferences: AppPreferences,
    private val contentCache: YouTubeContentCache,
) : ViewModel() {
    /** Set on the channel page route: the screen shows that channel instead of a feed. */
    private val channelId: String? = savedStateHandle.get<String>("channelId")?.takeIf(String::isNotBlank)
    // All channel pages share one view setup instead of one per visited channel.
    private fun stateKey(feed: YouTubeFeed) = if (channelId != null) "youtube:channel" else "youtube:${feed.key}"
    fun focusedVideoId(feed: YouTubeFeed): String? = preferences.sectionFocus(stateKey(feed))
    fun focusedRowId(feed: YouTubeFeed): String? = preferences.sectionFocusRow(stateKey(feed))
    fun onItemFocused(feed: YouTubeFeed, rowId: String?, videoId: String) {
        preferences.setSectionFocusSnapshot(stateKey(feed), videoId, rowId)
        scheduleRowContinuation(rowId, videoId)
    }

    // --- Row continuation (SmartTube loads rows page by page, like its BrowsePresenter) -------

    private val paging = YouTubeRowPaging(android.os.SystemClock::uptimeMillis)
    private var pagingRevision = 0L
    private var focusedRowId: String? = null
    private var focusLoadRowId: String? = null
    private var focusLoadJob: Job? = null
    private var initialPagingJob: Job? = null
    private val rowLoadJobs = HashMap<String, Job>()

    /** Discards every row continuation state; [session] arms paging for that feed/search key. */
    private fun resetPaging(session: String?, revision: Long = pagingRevision) {
        focusLoadJob?.cancel()
        initialPagingJob?.cancel()
        rowLoadJobs.values.forEach { it.cancel() }
        rowLoadJobs.clear()
        paging.reset(session)
        pagingRevision = revision
        if (_uiState.value.loadingRows.isNotEmpty()) _uiState.value = _uiState.value.copy(loadingRows = emptySet())
    }

    /** After a completed load: rows below SmartTube's minimum continue once, two at a time, while the user rests. */
    private fun armPaging(session: String, revision: Long) {
        resetPaging(session, revision)
        val candidates = paging.initialCandidates(_uiState.value.rows)
        if (candidates.isEmpty()) return
        initialPagingJob = viewModelScope.launch {
            val permits = Semaphore(YouTubeRowPaging.MAX_INITIAL_PARALLEL)
            coroutineScope {
                candidates.forEach { rowId ->
                    launch { permits.withPermit { awaitQuietInput(); continueRow(rowId, showPlaceholder = false) } }
                }
            }
        }
    }

    private fun singleRowId(): String? {
        val state = _uiState.value
        return applyYouTubeViewOptions(state.rows, state.sort, state.filter, state.sortDescending).singleOrNull()?.id
    }

    private fun scheduleRowContinuation(rowId: String?, videoId: String) {
        val grid = rowId == null
        val target = rowId ?: singleRowId()
        focusedRowId = target
        if (target == null || paging.session == null) { focusLoadJob?.cancel(); return }
        // Horizontal moves inside the pending row keep its timer; leaving the row cancels it,
        // so passing rows vertically never starts a request.
        if (focusLoadJob?.isActive == true && focusLoadRowId == target) return
        focusLoadJob?.cancel()
        val state = _uiState.value
        val row = state.rows.firstOrNull { it.id == target } ?: return
        val visible = applyYouTubeViewOptions(listOf(row), state.sort, state.filter, state.sortDescending).firstOrNull()?.items ?: return
        val index = visible.indexOfFirst { it.videoId == videoId }
        if (!paging.shouldLoadOnFocus(target, index, visible.size, grid) || target in rowLoadJobs) return
        focusLoadRowId = target
        focusLoadJob = viewModelScope.launch {
            delay(YouTubeRowPaging.FOCUS_SETTLE_MS)
            while (BrowseWorkBudget.scrolling.get() > 0) delay(50)
            if (focusedRowId != target || target in rowLoadJobs) return@launch
            rowLoadJobs[target] = viewModelScope.launch {
                try { continueRow(target, showPlaceholder = true) } finally { rowLoadJobs.remove(target) }
            }
        }
    }

    private suspend fun awaitQuietInput(maxWaitMs: Long = Long.MAX_VALUE) {
        var waited = 0L
        while (BrowseWorkBudget.recentlyInteracting() && waited < maxWaitMs) { delay(100); waited += 100 }
    }

    private suspend fun continueRow(rowId: String, showPlaceholder: Boolean) {
        val session = paging.session ?: return
        val row = _uiState.value.rows.firstOrNull { it.id == rowId } ?: return
        if (!paging.begin(rowId, row.items.size)) return
        val token = paging.token
        if (showPlaceholder) _uiState.value = _uiState.value.copy(loadingRows = _uiState.value.loadingRows + rowId)
        var finished = false
        try {
            // Pages of only duplicates or unplayable entries (shorts shelves, playlists) still
            // carry a next page key; skip a few of them instead of ending the row early.
            val known = row.items.mapTo(HashSet()) { it.videoId }
            var page = engine.continueRow(session, rowId)
            var skipped = 0
            while (page != null && page.hasMore && page.items.none { it.videoId !in known } && skipped < YouTubeRowPaging.MAX_EMPTY_PAGES) {
                if (paging.token != token) return
                skipped++
                page = engine.continueRow(session, rowId)
            }
            if (paging.token != token) return
            // A page for a row the user already left lands once input settles, not mid-scroll.
            if (focusedRowId != rowId) awaitQuietInput(maxWaitMs = 3_000L)
            if (paging.token != token || displayedContentKey != session) return
            val before = _uiState.value.rows.firstOrNull { it.id == rowId }?.items?.size ?: 0
            val rows = page?.items?.takeIf { it.isNotEmpty() }
                ?.let { contentCache.appendItems(session, rowId, it, pagingRevision) }
                ?: _uiState.value.rows
            val after = rows.firstOrNull { it.id == rowId }?.items?.size ?: 0
            paging.finish(rowId, token, page?.takeIf { after > before }, after)
            finished = true
            Timber.tag("youtube-rows").d("Row %s continued: +%d items (total %d, more=%s)", rowId, after - before, after, page?.hasMore)
            // One state update: appended items and removed placeholders arrive together.
            _uiState.value = _uiState.value.copy(rows = rows, loadingRows = _uiState.value.loadingRows - rowId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            paging.fail(rowId, token)
            finished = true
            Timber.tag("youtube-rows").w(error, "Row %s continuation failed; retry after %d ms", rowId, YouTubeRowPaging.RETRY_AFTER_MS)
        } finally {
            if (!finished) paging.abort(rowId, token)
            if (rowId in _uiState.value.loadingRows) _uiState.value = _uiState.value.copy(loadingRows = _uiState.value.loadingRows - rowId)
        }
    }
    private fun viewMode(feed: YouTubeFeed): YouTubeViewMode = preferences.sectionViewMode(stateKey(feed))
        ?.let { runCatching { YouTubeViewMode.valueOf(it) }.getOrNull() } ?: YouTubeViewMode.LANDSCAPE
    private fun sort(feed: YouTubeFeed): YouTubeSort = preferences.sectionSort(stateKey(feed))
        ?.let { runCatching { YouTubeSort.valueOf(it) }.getOrNull() } ?: YouTubeSort.DEFAULT
    private fun storedDescending(feed: YouTubeFeed, sort: YouTubeSort): Boolean = when (preferences.sectionSortOrder(stateKey(feed))) {
        "DESCENDING" -> true
        "ASCENDING" -> false
        else -> sort == YouTubeSort.DURATION
    }
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
            sortDescending = storedDescending(YouTubeFeed.HOME, sort(YouTubeFeed.HOME)),
            filter = filter(YouTubeFeed.HOME),
            gridColumns = preferences.libraryGridColumns,
            listRows = preferences.libraryListRows,
            mediaSelectionBehaviorKey = preferences.youtubeMediaSelectionBehaviorKey,
            channel = channelId?.let {
                com.ambient.tv.youtube.YouTubeChannelInfo(
                    channelId = it,
                    title = savedStateHandle.get<String>("title")?.takeIf(String::isNotBlank),
                    avatarUrl = savedStateHandle.get<String>("avatar")?.takeIf(String::isNotBlank),
                    subscribed = engine.subscriptionOverride(it),
                )
            },
        ),
    )
    private var signInWatchJob: Job? = null
    private var contentJob: Job? = null
    private var displayedContentKey: String? = null
    private var entered = false
    val uiState: StateFlow<YouTubeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            engine.subscriptionChanges.collect { changes ->
                val channel = _uiState.value.channel ?: return@collect
                val updated = channel.withSubscriptionChanges(changes)
                if (updated != channel) _uiState.value = _uiState.value.copy(channel = updated)
            }
        }
    }

    fun enter(initialFeedKey: String?) {
        if (!entered) {
            entered = true
            val feed = initialFeedKey?.let(YouTubeFeed::fromKey)
            if (feed != null && feed != _uiState.value.feed) { selectFeed(feed); return }
        }
        refresh()
    }

    fun refresh() {
        val current = _uiState.value
        if (current.query.trim().length >= 2) search(current.query, preserveExtent = true)
        else load(current.feed, preserveExtent = true)
    }
    fun selectFeed(feed: YouTubeFeed) {
        val nextSort = sort(feed)
        _uiState.value = _uiState.value.copy(
            feed = feed,
            viewMode = viewMode(feed),
            sort = nextSort,
            sortDescending = storedDescending(feed, nextSort),
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
        val descending = sort == YouTubeSort.DURATION
        val key = stateKey(_uiState.value.feed)
        preferences.setSectionSort(key, sort.name)
        preferences.setSectionSortOrder(key, if (descending) "DESCENDING" else "ASCENDING")
        _uiState.value = _uiState.value.copy(sort = sort, sortDescending = descending)
    }
    fun selectSortDescending(descending: Boolean) {
        preferences.setSectionSortOrder(stateKey(_uiState.value.feed), if (descending) "DESCENDING" else "ASCENDING")
        _uiState.value = _uiState.value.copy(sortDescending = descending)
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
    fun toggleSubscription() {
        val channel = _uiState.value.channel?.takeIf { it.subscribed != null } ?: return
        if (!_uiState.value.signedIn) return
        viewModelScope.launch {
            engine.toggleSubscription(channel) { subscribed ->
                _uiState.value = _uiState.value.copy(channel = _uiState.value.channel?.copy(subscribed = subscribed))
            }
        }
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

    private fun showCachedContent(key: String, retainedRows: List<YouTubeRow> = emptyList()) {
        val cached = contentCache.get(key)
        val rows = retainedRows.takeIf { it.isNotEmpty() }
            ?: cached ?: _uiState.value.rows.takeIf { displayedContentKey == key }.orEmpty()
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

    private fun refreshSnapshot(key: String, preserveExtent: Boolean): List<YouTubeRow> =
        _uiState.value.rows.takeIf { preserveExtent && displayedContentKey == key }.orEmpty()

    private fun load(feed: YouTubeFeed, preserveExtent: Boolean = false) {
        if (channelId != null) { loadChannel(channelId, preserveExtent); return }
        val preserveFeedSelection = preserveExtent && displayedContentKey == "feed:${feed.key}"
        val previousRows = refreshSnapshot("feed:${feed.key}", preserveExtent)
        contentJob?.cancel()
        resetPaging(null)
        showCachedContent("feed:${feed.key}", previousRows)
        contentJob = viewModelScope.launch {
            contentCache.restore()
            val cacheRevision = contentCache.revision()
            if (!engine.available) {
                _uiState.value = _uiState.value.copy(available = false, loading = false, error = null)
                return@launch
            }
            val supported = suspendResult { engine.supportedFeedKeys() }.getOrDefault(setOf("home"))
            // A transient capability/auth probe during recovery must not change the open tab.
            val feeds = configuredFeeds.filter { it.key in supported || (preserveFeedSelection && it == feed) }
                .ifEmpty { listOf(YouTubeFeed.HOME) }
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
            val refreshRows = previousRows.takeIf { selectedFeed == feed }.orEmpty()
            showCachedContent(cacheKey, refreshRows)
            suspendResult {
                val signed = engine.isSignedIn()
                val profile = if (signed) suspendResult { engine.accountProfile() }.getOrNull() else null
                _uiState.value = _uiState.value.copy(signedIn = signed, accountProfile = profile)
                var rows = engine.feed(selectedFeed.key) { rows ->
                    if (refreshRows.isEmpty()) showRows(cacheKey, rows, cacheRevision)
                }
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
                signed to refreshYouTubeRowExtents(rows, refreshRows) { rowId -> engine.continueRow(cacheKey, rowId) }
            }.onSuccess { (signed, rows) ->
                if (contentCache.revision() != cacheRevision) return@onSuccess
                _uiState.value = _uiState.value.copy(loading = false, signedIn = signed, rows = contentCache.replace(cacheKey, rows, cacheRevision))
                if (displayedContentKey == cacheKey) armPaging(cacheKey, cacheRevision)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(loading = false, error = e.userMessage().takeIf { _uiState.value.rows.isEmpty() })
            }
        }
    }

    /** Channel page: its rows (Uploads, Live, Shorts, ...) take the place of a feed. */
    private fun loadChannel(channelId: String, preserveExtent: Boolean = false) {
        val cacheKey = "channel:$channelId"
        val previousRows = refreshSnapshot(cacheKey, preserveExtent)
        contentJob?.cancel()
        resetPaging(null)
        showCachedContent(cacheKey, previousRows)
        contentJob = viewModelScope.launch {
            contentCache.restore()
            val cacheRevision = contentCache.revision()
            if (!engine.available) {
                _uiState.value = _uiState.value.copy(available = false, loading = false, error = null)
                return@launch
            }
            suspendResult {
                val signed = engine.isSignedIn()
                _uiState.value = _uiState.value.copy(signedIn = signed, visibleFeeds = emptyList())
                val rows = engine.channel(channelId, cacheKey) { rows ->
                    if (previousRows.isEmpty()) showRows(cacheKey, rows, cacheRevision)
                }
                refreshYouTubeRowExtents(rows, previousRows) { rowId -> engine.continueRow(cacheKey, rowId) }
            }.onSuccess { rows ->
                if (contentCache.revision() != cacheRevision) return@onSuccess
                _uiState.value = _uiState.value.copy(loading = false, rows = contentCache.replace(cacheKey, rows, cacheRevision))
                if (displayedContentKey == cacheKey) armPaging(cacheKey, cacheRevision)
                resolveChannelHeader(channelId, rows)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(loading = false, error = e.userMessage().takeIf { _uiState.value.rows.isEmpty() })
            }
        }
    }

    /**
     * YouTube reports the subscription only with a video's metadata, so the channel's first upload
     * supplies it (and name and avatar when the route came without them, e.g. from the player).
     */
    private suspend fun resolveChannelHeader(channelId: String, rows: List<YouTubeRow>) {
        val current = _uiState.value.channel ?: return
        if (current.subscribed != null && current.title != null && current.avatarUrl != null) return
        // Own uploads first; a featured video of another channel cannot tell this channel's state.
        val videos = rows.asSequence().flatMap { it.items }.filterNot { it.isChannel }
        val candidates = (videos.filter { it.channelId == channelId } + videos).map { it.videoId }.distinct().take(3)
        val channel = candidates.firstNotNullOfOrNull { videoId ->
            suspendResult { engine.metadata(videoId) }.getOrNull()?.let(engine::channelOf)?.takeIf { it.channelId == channelId }
        } ?: return
        val latest = _uiState.value.channel ?: return
        _uiState.value = _uiState.value.copy(channel = latest.copy(
            title = latest.title ?: channel.title,
            avatarUrl = latest.avatarUrl ?: channel.avatarUrl,
            subscribed = engine.subscriptionOverride(channelId) ?: latest.subscribed ?: channel.subscribed.takeIf { _uiState.value.signedIn },
        ))
    }

    private fun search(query: String, preserveExtent: Boolean = false) {
        val cacheKey = channelId?.let { "channel:$it:search:${query.trim().lowercase()}" }
            ?: "search:${query.trim().lowercase()}"
        val previousRows = refreshSnapshot(cacheKey, preserveExtent)
        contentJob?.cancel()
        resetPaging(null)
        contentJob = viewModelScope.launch {
            contentCache.restore()
            val cacheRevision = contentCache.revision()
            delay(350)
            showCachedContent(cacheKey, previousRows)
            suspendResult {
                val onRows: suspend (List<YouTubeRow>) -> Unit = { rows ->
                    if (previousRows.isEmpty()) showRows(cacheKey, rows, cacheRevision)
                }
                val rows = if (channelId != null) engine.channelSearch(channelId, query, cacheKey, onRows)
                    else engine.search(query, cacheKey, onRows)
                refreshYouTubeRowExtents(rows, previousRows) { rowId -> engine.continueRow(cacheKey, rowId) }
            }
                .onSuccess { rows ->
                    if (_uiState.value.query == query && contentCache.revision() == cacheRevision) {
                        _uiState.value = _uiState.value.copy(loading = false, rows = contentCache.replace(cacheKey, rows, cacheRevision))
                        if (displayedContentKey == cacheKey) armPaging(cacheKey, cacheRevision)
                    }
                }
                .onFailure { e -> if (_uiState.value.query == query && _uiState.value.rows.isEmpty()) _uiState.value = _uiState.value.copy(loading = false, error = e.userMessage()) }
        }
    }
}

private fun Throwable.userMessage() = message?.takeIf { it.isNotBlank() } ?: "YouTube konnte nicht geladen werden."
