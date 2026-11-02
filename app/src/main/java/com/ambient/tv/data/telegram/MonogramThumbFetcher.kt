package com.ambient.tv.data.telegram

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Path.Companion.toOkioPath

/**
 * Loads [monogramThumbModel] images: Telegram's real thumbnail of a post, only for cards Coil
 * actually draws. Without it (no account, FLOOD_WAIT, no thumbnail) the post's inline preview
 * is shown instead, as before.
 */
class MonogramThumbFetcher(
    private val ref: MonogramThumbRef,
    /** The post's full photo (photo viewer) instead of its card thumbnail. */
    private val fullPhoto: Boolean,
    private val options: Options,
    private val repository: () -> TelegramRepository,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val uri = (if (fullPhoto) runCatching { repository().photoFile(ref.chatId, ref.messageId) }
                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
            else repository().thumbnailFile(ref.chatId, ref.messageId)) ?: ref.fallback ?: return null
        val file = runCatching { java.io.File(java.net.URI(uri)) }.getOrNull()?.takeIf { it.isFile } ?: return null
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val repository: () -> TelegramRepository) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != MONOGRAM_THUMB_SCHEME && data.scheme != MONOGRAM_PHOTO_SCHEME) return null
            val ref = parseMonogramThumbModel(data.toString()) ?: return null
            return MonogramThumbFetcher(ref, data.scheme == MONOGRAM_PHOTO_SCHEME, options, repository)
        }
    }
}
