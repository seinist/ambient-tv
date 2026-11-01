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
    private val mutableState = MutableStateFlow(TelegramBrowseState())
    val state: StateFlow<TelegramBrowseState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    private var pageGeneration = 0

    fun open(section: String, refresh: Boolean = false) {
        val key = section.takeIf { it in setOf("recent", "channels", "videos", "youtube", "audio", "photos") ||
            (it.startsWith("channel:") && it.substringAfter(':').toLongOrNull() != null) } ?: "recent"
        val current = mutableState.value
        if (!refresh && current.section == key && current.available && current.rows.isNotEmpty()) return
        loadJob?.cancel()
        pageGeneration++
        mutableState.value = current.copy(
            section = key, loading = repository.available,
            loadingMore = false, nextCursor = null,
            available = repository.available,
            rows = if (current.section == key) current.rows else emptyList(),
            media = if (current.section == key) current.media else emptyMap(),
            error = null,
        )
        if (!repository.available) return
        fetchPage(key, cursor = null, generation = pageGeneration)
    }

    fun loadMore() {
        val current = mutableState.value
        val next = current.nextCursor ?: return
        if (!current.available || current.loading || current.loadingMore || current.error != null) return
        mutableState.value = current.copy(loadingMore = true)
        fetchPage(current.section, cursor = next, generation = pageGeneration)
    }

    private fun fetchPage(section: String, cursor: String?, generation: Int) {
        loadJob = viewModelScope.launch {
            try {
                val pageRows: List<ContentRowData>
                val pageMedia: List<TelegramMedia>
                val next: String?
                if (section == "channels") {
                    val page = repository.channelsPage(cursor)
                    pageRows = if (page.items.isEmpty()) emptyList() else listOf(ContentRowData(
                        id = "telegram.channels",
                        title = "Monogram",
                        style = MediaTileStyle.POSTER,
                        items = page.items.distinctBy(TelegramChannel::chatId).map(TelegramChannel::toBrowseItem),
                    ))
                    pageMedia = emptyList()
                    next = page.nextCursor
                } else {
                    val page = repository.browsePage(section, cursor)
                    pageRows = telegramRows(section, page.items)
                    pageMedia = page.items
                    next = page.nextCursor
                }
                if (generation != pageGeneration || mutableState.value.section != section) return@launch
                val existing = if (cursor != null) mutableState.value.rows else emptyList()
                val merged = LinkedHashMap<String, ContentRowData>()
                (existing + pageRows).forEach { row ->
                    val previous = merged[row.id]
                    merged[row.id] = if (previous == null) row else previous.copy(
                        title = row.title,
                        items = (previous.items + row.items).distinctBy(MediaItem::ratingKey),
                    )
                }
                mutableState.value = TelegramBrowseState(
                    section = section, available = true, rows = merged.values.toList(),
                    media = (if (cursor != null) mutableState.value.media else emptyMap()) +
                        pageMedia.associateBy(TelegramMedia::mediaKey),
                    nextCursor = next?.takeIf { it != cursor },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == pageGeneration) mutableState.value = mutableState.value.copy(
                    loading = false, loadingMore = false,
                    error = failure.message ?: "Monogram request failed",
                )
            }
        }
    }

    fun selected(key: String): TelegramMedia? = mutableState.value.media[key]
}
