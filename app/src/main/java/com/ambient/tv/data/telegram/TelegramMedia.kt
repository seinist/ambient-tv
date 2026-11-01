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
    /** Hidden text_url entities from Telegram, not necessarily visible in the caption. */
    val linkedYouTubeVideoIds: List<String> = emptyList(),
) {
    /** A linked YouTube video is a separate media card, even inside a post with an attachment. */
    val youtubeIds: List<String>
        get() = (telegramYouTubeIds(summary.orEmpty()) + linkedYouTubeVideoIds +
            listOfNotNull(youtubeVideoId)).distinct()
    val mediaKey: String get() = if (kind == TelegramMediaKind.YOUTUBE && youtubeVideoId != null)
        telegramYouTubeMediaKey(chatId, messageId, youtubeVideoId) else telegramMediaKey(chatId, messageId)
    val previewUrl: String? get() = if (kind == TelegramMediaKind.YOUTUBE && youtubeVideoId != null)
        com.ambient.tv.youtube.youtubeThumbnailUrl(youtubeVideoId) else thumbnailUrl
    /** YouTube playback is delegated to SmartTube; the Telegram file never shadows it. */
    val playableOnTelegram: Boolean get() = kind != TelegramMediaKind.YOUTUBE && !protectedContent
}

data class TelegramChannel(
    val chatId: Long,
    val title: String,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    /** Unknown until the native session confirms the channel's membership. */
    val subscribed: Boolean? = null,
)

const val TELEGRAM_MEDIA_PREFIX = "telegram:"
const val TELEGRAM_YOUTUBE_PREFIX = "telegram-youtube:"
fun telegramYouTubeMediaKey(chatId: Long, messageId: Int, videoId: String): String =
    "$TELEGRAM_YOUTUBE_PREFIX$chatId:$messageId:$videoId"

/** Validated virtual tile identifier; the original post stays accessible through chat/message ids. */
fun telegramYouTubeItemId(key: String): String? {
    if (!key.startsWith(TELEGRAM_YOUTUBE_PREFIX)) return null
    val parts = key.removePrefix(TELEGRAM_YOUTUBE_PREFIX).split(':')
    if (parts.size != 3 || parts[0].toLongOrNull() == null ||
        parts[1].toIntOrNull()?.let { it > 0 } != true) return null
    return parts[2].takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
}

/** The clicked card may be the post itself or one of its distinct YouTube links. */
fun telegramPostIds(key: String): Pair<Long, Int>? {
    telegramMediaIds(key)?.let { return it }
    if (!key.startsWith(TELEGRAM_YOUTUBE_PREFIX) || telegramYouTubeItemId(key) == null) return null
    val parts = key.removePrefix(TELEGRAM_YOUTUBE_PREFIX).split(':')
    return parts[0].toLongOrNull()?.let { chat ->
        parts[1].toIntOrNull()?.takeIf { it > 0 }?.let { chat to it }
    }
}

/** Expand one Telegram post into a distinct card per URL without downloading YouTube streams via MTProto. */
fun TelegramMedia.youtubePosts(): List<TelegramMedia> = youtubeIds.map { id ->
    copy(kind = TelegramMediaKind.YOUTUBE, youtubeVideoId = id,
        thumbnailUrl = com.ambient.tv.youtube.youtubeThumbnailUrl(id))
}

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
    thumbUrl = previewUrl,
    artUrl = previewUrl,
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
    hasVideo = kind == TelegramMediaKind.VIDEO,
    artworkUrl = thumbnailUrl,
    durationMs = durationMs,
)

/** Parse only actual youtube.com / youtu.be hosts. Do not mistake lookalike domains for YouTube. */
fun telegramYouTubeIds(text: String): List<String> {
    // Do not parse a YouTube-looking suffix of an attacker-controlled host.
    val urls = Regex("""(?i)(?<![a-z0-9_.-])(?:https?://)?(?:www\.|m\.)?(?:youtube\.com|youtu\.be)/[^\s<>"']+""")
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
