package com.ambient.tv.data.telegram

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.player.PlaybackMetadata

/** Normalized Telegram feed entry, independent of Monogram's native model and API revisions. */
enum class TelegramMediaKind { VIDEO, AUDIO, PHOTO, YOUTUBE }

data class TelegramMedia(
    val chatId: Long,
    val messageId: Int,
    val channel: String,
    val title: String,
    val kind: TelegramMediaKind,
    val summary: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0,
    val fileSize: Long = 0,
    val publishedAtMs: Long = 0,
    val youtubeVideoId: String? = null,
    val protectedContent: Boolean = false,
) {
    val mediaKey: String get() = telegramMediaKey(chatId, messageId)
    /** YouTube playback is delegated to SmartTube; the Telegram file never shadows it. */
    val playableOnTelegram: Boolean get() = kind != TelegramMediaKind.YOUTUBE && !protectedContent
}

data class TelegramChannel(
    val chatId: Long,
    val title: String,
    val description: String? = null,
    val thumbnailUrl: String? = null,
)

const val TELEGRAM_MEDIA_PREFIX = "telegram:"
fun telegramMediaKey(chatId: Long, messageId: Int): String = "$TELEGRAM_MEDIA_PREFIX$chatId:$messageId"
fun telegramMediaIds(key: String): Pair<Long, Int>? {
    if (!key.startsWith(TELEGRAM_MEDIA_PREFIX)) return null
    val parts = key.removePrefix(TELEGRAM_MEDIA_PREFIX).split(':')
    if (parts.size != 2) return null
    val chatId = parts[0].toLongOrNull() ?: return null
    val messageId = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return chatId to messageId
}

fun TelegramMedia.toMediaItem(): MediaItem = MediaItem(
    ratingKey = mediaKey,
    title = title,
    type = when (kind) {
        TelegramMediaKind.AUDIO -> "track"
        TelegramMediaKind.PHOTO -> "photo"
        else -> "movie"
    },
    index = null,
    thumbUrl = thumbnailUrl,
    artUrl = thumbnailUrl,
    year = null,
    summary = summary,
    tagline = null,
    durationMs = durationMs.takeIf { it > 0 },
    viewOffsetMs = null,
    parentTitle = channel,
    parentRatingKey = null,
    grandparentTitle = null,
    grandparentRatingKey = null,
    contentRating = null,
    rating = null,
    studio = null,
    originallyAvailableAt = null,
    addedAt = publishedAtMs.takeIf { it > 0 },
    lastViewedAt = null,
)

fun TelegramMedia.playerMetadata(): PlaybackMetadata = PlaybackMetadata(
    route = "telegram_player/$chatId/$messageId",
    title = title,
    subtitle = channel,
    source = "telegram",
    mediaId = mediaKey,
    hasVideo = kind != TelegramMediaKind.AUDIO,
    artworkUrl = thumbnailUrl,
    durationMs = durationMs,
)

/** Parse only actual youtube.com / youtu.be hosts. Do not mistake lookalike domains for YouTube. */
fun telegramYouTubeIds(text: String): List<String> {
    val urls = Regex("""(?i)(?:https?://)?(?:www\.|m\.)?(?:youtube\.com|youtu\.be)/[^\s<>"']+""")
    val validId = Regex("[A-Za-z0-9_-]{11}")
    return urls.findAll(text).mapNotNull { match ->
        val value = match.value.let { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
        val uri = runCatching { java.net.URI(value.trimEnd('.', ',', ')', ']')) }.getOrNull()
            ?: return@mapNotNull null
        val host = uri.host?.lowercase() ?: return@mapNotNull null
        val path = uri.path?.trim('/') ?: return@mapNotNull null
        val id = when (host) {
            "youtu.be", "www.youtu.be" -> path.substringBefore('/')
            "youtube.com", "www.youtube.com", "m.youtube.com" -> when {
                path == "watch" -> uri.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.startsWith("v=") }?.substringAfter('=')
                path.startsWith("shorts/") || path.startsWith("live/") ||
                    path.startsWith("embed/") -> path.substringAfter('/').substringBefore('/')
                else -> null
            }
            else -> null
        }
        id?.takeIf(validId::matches)
    }.distinct().toList()
}
