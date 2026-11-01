package com.ambient.tv.ui.telegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.telegram.TelegramChannel
import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.data.telegram.TelegramRepository
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
        .groupBy { it.channel }
        .map { (channel, media) ->
            ContentRowData(
                id = "telegram.$section.$channel",
                title = channel,
                style = MediaTileStyle.LANDSCAPE,
                items = media.map(TelegramMedia::toMediaItem),
            )
        }

@HiltViewModel
class TelegramViewModel @Inject constructor(
    private val repository: TelegramRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TelegramBrowseState())
    val state: StateFlow<TelegramBrowseState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    fun open(section: String, refresh: Boolean = false) {
        val key = section.takeIf { it in setOf("recent", "channels", "videos", "youtube", "audio", "photos") ||
            (it.startsWith("channel:") && it.substringAfter(':').toLongOrNull() != null) } ?: "recent"
        val current = mutableState.value
        if (!refresh && current.section == key && current.available && current.rows.isNotEmpty()) return
        loadJob?.cancel()
        mutableState.value = current.copy(
            section = key, loading = repository.available,
            available = repository.available,
            // Keep only the same category's rows during refresh, never flash another section.
            rows = if (current.section == key) current.rows else emptyList(),
            media = if (current.section == key) current.media else emptyMap(),
            error = null,
        )
        if (!repository.available) return
        loadJob = viewModelScope.launch {
            try {
                if (key == "channels") {
                    val channels = repository.channels()
                    mutableState.value = TelegramBrowseState(
                        section = key, available = true,
                        rows = if (channels.isEmpty()) emptyList() else listOf(ContentRowData(
                            id = "telegram.channels",
                            title = "Telegram",
                            style = MediaTileStyle.POSTER,
                            items = channels.distinctBy(TelegramChannel::chatId).map(TelegramChannel::toBrowseItem),
                        )),
                    )
                } else {
                    val entries = repository.browse(key)
                    mutableState.value = TelegramBrowseState(
                        section = key, available = true,
                        rows = telegramRows(key, entries),
                        media = entries.associateBy(TelegramMedia::mediaKey),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(loading = false,
                    error = failure.message ?: "Telegram request failed")
            }
        }
    }

    fun selected(key: String): TelegramMedia? = mutableState.value.media[key]
}
