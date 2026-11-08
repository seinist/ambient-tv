package com.ambient.tv.ui.youtube

import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Where a YouTube link was selected. These fields describe the post, never the video. */
data class YouTubeDetailOrigin(
    val chatId: Long,
    val messageId: Int,
    val channel: String? = null,
    val publishedAtMs: Long = 0L,
)

internal fun youtubeDetailOrigin(chatId: Long?, messageId: Int?): YouTubeDetailOrigin? =
    if (chatId != null && chatId != 0L && messageId != null && messageId > 0) {
        YouTubeDetailOrigin(chatId, messageId)
    } else null

/**
 * Source attribution is optional enrichment. A slow/unavailable Monogram account must not delay
 * the YouTube actions, replace their metadata, or turn an otherwise playable video into an error.
 */
internal suspend fun updateYouTubeDetailOrigin(
    origin: YouTubeDetailOrigin,
    state: MutableStateFlow<YouTubeDetailState>,
    loadPost: suspend (Long, Int) -> TelegramMedia?,
) {
    val post = suspendResult { loadPost(origin.chatId, origin.messageId) }.getOrNull()
        ?.takeIf { it.chatId == origin.chatId && it.messageId == origin.messageId } ?: return
    state.update { current ->
        val currentOrigin = current.origin
        if (currentOrigin == null || currentOrigin.chatId != origin.chatId || currentOrigin.messageId != origin.messageId) current
        else current.copy(origin = currentOrigin.copy(
            channel = post.channel.trim().takeIf(String::isNotBlank),
            publishedAtMs = post.publishedAtMs.coerceAtLeast(0L),
        ))
    }
}
