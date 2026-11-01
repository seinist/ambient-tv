package com.ambient.tv.ui.telegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.telegram.TelegramChannel
import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.data.telegram.TelegramRepository
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.telegram.toMediaItem
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.ContentRowData
import com.ambient.tv.ui.common.MediaTileStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val TELEGRAM_CHANNEL_ITEM_PREFIX = "telegram-channel:"

data class TelegramBrowseState(
    val section: String = "recent",
    val available: Boolean = false,
    val loading: Boolean = false,
    val rows: List<ContentRowData> = emptyList(),
    val media: Map<String, TelegramMedia> = emptyMap(),
    val error: String? = null,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val pageError: String? = null,
)

fun TelegramChannel.toBrowseItem(): MediaItem = MediaItem(
    ratingKey = TELEGRAM_CHANNEL_ITEM_PREFIX + chatId,
    title = title,
    type = "collection",
    index = null,
    thumbUrl = thumbnailUrl,
    artUrl = thumbnailUrl,
    year = null,
    summary = description,
    tagline = null,
    durationMs = null,
    viewOffsetMs = null,
    parentTitle = null,
    parentRatingKey = null,
    grandparentTitle = null,
    grandparentRatingKey = null,
    contentRating = null,
    rating = null,
    studio = null,
)

internal fun telegramRows(section: String, entries: List<TelegramMedia>): List<ContentRowData> =
    entries.asSequence().filter { entry ->
        when (section) {
            "videos" -> entry.kind == TelegramMediaKind.VIDEO
            "youtube" -> entry.kind == TelegramMediaKind.YOUTUBE
            "audio" -> entry.kind == TelegramMediaKind.AUDIO
            "photos" -> entry.kind == TelegramMediaKind.PHOTO
            else -> true
        }
    }.distinctBy(TelegramMedia::mediaKey)
        .groupBy { it.chatId }
        .map { (chatId, media) ->
            ContentRowData(
                id = "telegram.$section.$chatId",
                title = media.first().channel,
                style = MediaTileStyle.LANDSCAPE,
                items = media.map(TelegramMedia::toMediaItem),
            )
        }

@HiltViewModel
class TelegramViewModel @Inject constructor(
    private val repository: TelegramRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    val visibleSections: List<String> get() = preferences.telegramSubmenuKeys
    fun viewMode(section: String): MonogramBrowseLayout =
        runCatching { MonogramBrowseLayout.valueOf(preferences.sectionViewMode("monogram.$section") ?: "ROWS") }
            .getOrDefault(MonogramBrowseLayout.ROWS)
    fun changeViewMode(section: String, mode: MonogramBrowseLayout) =
        preferences.setSectionViewMode("monogram.$section", mode.name)
    val gridColumns get() = preferences.libraryGridColumns
    val listRows get() = preferences.libraryListRows
    fun setGridColumns(columns: Int) { preferences.libraryGridColumns = columns }
    fun setListRows(rows: Int) { preferences.libraryListRows = rows }

    private val mutableState = MutableStateFlow(TelegramBrowseState())
    val state: StateFlow<TelegramBrowseState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    private var pageGeneration = 0

    init {
        // A restored session is asynchronous; repaint without re-entering the route.
        viewModelScope.launch {
            repository.authorized.collect { signedIn ->
                if (signedIn) open(mutableState.value.section, refresh = true)
                else {
                    loadJob?.cancel()
                    pageGeneration++
                    mutableState.value = TelegramBrowseState(section = mutableState.value.section)
                }
            }
        }
    }

    fun open(section: String, refresh: Boolean = false) {
        val key = section.takeIf { it in setOf("recent", "channels", "videos", "youtube", "audio", "photos") ||
            (it.startsWith("channel:") && it.substringAfter(':').toLongOrNull() != null) } ?: "recent"
        val current = mutableState.value
        if (!refresh && current.section == key && current.available && current.rows.isNotEmpty()) return
        loadJob?.cancel()
        pageGeneration++
        mutableState.value = current.copy(
            section = key, loading = repository.available,
            loadingMore = false, nextCursor = null, pageError = null,
            available = repository.available,
            rows = if (current.section == key) current.rows else emptyList(),
            media = if (current.section == key) current.media else emptyMap(),
            error = null,
        )
        if (!repository.available) return
        // Paint last authorized-account snapshot first; a live refresh replaces it.
        val generation = pageGeneration
        viewModelScope.launch {
            val cached = if (key == "channels") repository.cachedChannels().let { list ->
                if (list.isEmpty()) emptyList() else listOf(ContentRowData(
                    id = "telegram.channels", title = "Monogram",
                    style = MediaTileStyle.POSTER,
                    items = list.map(TelegramChannel::toBrowseItem)))
            } else telegramRows(key, repository.cachedBrowse(key))
            if (generation == pageGeneration && mutableState.value.section == key &&
                mutableState.value.rows.isEmpty() && cached.isNotEmpty()) {
                mutableState.value = mutableState.value.copy(rows = cached)
            }
        }
        fetchPage(key, cursor = null, generation = generation, invalidate = refresh)
    }

    fun loadMore() {
        val current = mutableState.value
        val next = current.nextCursor ?: return
        if (!current.available || current.loading || current.loadingMore || current.error != null) return
        mutableState.value = current.copy(loadingMore = true, pageError = null)
        fetchPage(current.section, cursor = next, generation = pageGeneration)
    }

    private fun fetchPage(section: String, cursor: String?, generation: Int, invalidate: Boolean = false) {
        loadJob = viewModelScope.launch {
            try {
                if (invalidate) repository.refreshCatalog()
                // An audio/YouTube filter may encounter many consecutive text-only
                // pages. Follow bounded empty continuations without publishing a
                // misleading "no results" panel while content still exists.
                var requestCursor = cursor
                var pageRows = emptyList<ContentRowData>()
                var pageMedia = emptyList<TelegramMedia>()
                var next: String? = null
                val seen = mutableSetOf<String?>()
                for (attempt in 0 until 5) {
                    if (generation != pageGeneration || mutableState.value.section != section) return@launch
                    if (!seen.add(requestCursor)) break
                    if (section == "channels") {
                        val page = repository.channelsPage(requestCursor)
                        pageRows = if (page.items.isEmpty()) emptyList() else listOf(ContentRowData(
                            id = "telegram.channels",
                            title = "Monogram",
                            style = MediaTileStyle.POSTER,
                            items = page.items.distinctBy(TelegramChannel::chatId).map(TelegramChannel::toBrowseItem),
                        ))
                        pageMedia = emptyList()
                        next = page.nextCursor
                    } else {
                        val page = repository.browsePage(section, requestCursor)
                        pageRows = telegramRows(section, page.items)
                        pageMedia = page.items
                        next = page.nextCursor
                    }
                    if (pageRows.isNotEmpty() || next == null || next == requestCursor || attempt == 4) break
                    requestCursor = next
                }
                if (generation != pageGeneration || mutableState.value.section != section) return@launch
                val previous = if (cursor != null) mutableState.value.rows else emptyList()
                val merged = LinkedHashMap<String, ContentRowData>()
                (previous + pageRows).forEach { row ->
                    val old = merged[row.id]
                    merged[row.id] = if (old == null) row else old.copy(
                        items = (old.items + row.items).distinctBy(MediaItem::ratingKey),
                    )
                }
                val earlier = if (cursor != null) mutableState.value.media else emptyMap()
                mutableState.value = TelegramBrowseState(
                    section = section, available = true, rows = merged.values.toList(),
                    media = earlier + pageMedia.associateBy(TelegramMedia::mediaKey),
                    nextCursor = next?.takeUnless { it == requestCursor },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == pageGeneration) {
                    val old = mutableState.value
                    mutableState.value = old.copy(
                        loading = false, loadingMore = false,
                        error = if (old.rows.isEmpty()) (failure.message ?: "Monogram request failed") else null,
                        pageError = if (old.rows.isNotEmpty()) (failure.message ?: "Monogram request failed") else null,
                    )
                }
            }
        }
    }

    fun rememberPlaybackQueue(ids: List<String>, selected: String) {
        val safe = ids.filter { mutableState.value.media[it]?.playableOnTelegram == true }
        repository.activatePlaybackQueue(safe, selected)
    }

    fun selected(key: String): TelegramMedia? = mutableState.value.media[key]
}
