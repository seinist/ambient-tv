package com.ambient.tv.ui.telegram

import com.ambient.tv.data.photos.PhotoEntry
import com.ambient.tv.data.photos.PhotoKind
import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.data.telegram.monogramPhotoModel

/**
 * Monogram photos open in the app's photo viewer like Files, Plex and Jellyfin photos, not in
 * the video player. The page a photo was chosen on hands its photos over, so the viewer steps
 * through them; a photo opened elsewhere is shown on its own.
 */
object TelegramPhotoGallery {
    @Volatile private var photos: List<TelegramMedia> = emptyList()

    fun offer(page: List<TelegramMedia>) {
        photos = page.filter { it.kind == TelegramMediaKind.PHOTO && it.playableOnTelegram }
    }

    /** The viewer list and start index for [selected]. */
    fun open(selected: TelegramMedia): Pair<List<PhotoEntry>, Int> {
        val list = photos.takeIf { page -> page.any { it.mediaKey == selected.mediaKey } } ?: listOf(selected)
        return list.map(::photoEntry) to list.indexOfFirst { it.mediaKey == selected.mediaKey }.coerceAtLeast(0)
    }

    internal fun photoEntry(media: TelegramMedia): PhotoEntry = PhotoEntry(
        id = "$MONOGRAM_PHOTO_ID_PREFIX${media.chatId}:${media.messageId}",
        title = media.title,
        kind = PhotoKind.PHOTO,
        addedAt = media.publishedAtMs.takeIf { it > 0 },
        width = media.width.takeIf { it > 0 },
        height = media.height.takeIf { it > 0 },
        image = { maxWidth, _ ->
            // Filmstrip/thumbnails: the card image; the viewer itself: the photo file.
            if (maxWidth <= PhotoEntry.THUMB_WIDTH) media.cardImageUrl
            else monogramPhotoModel(media.chatId, media.messageId, media.cardImageUrl)
        },
    )

    const val MONOGRAM_PHOTO_ID_PREFIX = "monogram-photo:"
}
