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
        // A card thumbnail that is unavailable right now (FLOOD_WAIT, offline) must fail: the
        // card composable draws the inline preview itself and ArtworkImage retries the inline
        // model as its next candidate. Returning the preview here would cache the pixelated
        // image under the sharp thumbnail's key, and the sharp one would not be tried again.
        val uri = (if (fullPhoto) runCatching { repository().photoFile(ref.chatId, ref.messageId) }
                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null } ?: ref.fallback
            else if (!options.networkCachePolicy.readEnabled) repository().cachedArtworkFile(ref.chatId, ref.messageId)
            else repository().thumbnailFile(ref.chatId, ref.messageId)) ?: return null
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

/** Normalize only sharp card requests; full photo and inline preview keys stay separate. */
internal class MonogramSharpCacheInterceptor(
    private val repository: () -> TelegramRepository,
) : coil3.intercept.Interceptor {
    override suspend fun intercept(chain: coil3.intercept.Interceptor.Chain): coil3.request.ImageResult {
        val request = chain.request
        val model = when (val data = request.data) {
            is String -> data
            is Uri -> data.toString()
            else -> return chain.proceed()
        }
        if (!model.startsWith("$MONOGRAM_THUMB_SCHEME://")) return chain.proceed()
        val repo = repository()
        val key = repo.artworkCacheKey(model)
        val result = chain.withRequest(request.newBuilder().apply {
            if (key == null) {
                memoryCachePolicy(coil3.request.CachePolicy.DISABLED)
                placeholderMemoryCacheKey(null)
                networkCachePolicy(coil3.request.CachePolicy.DISABLED)
            } else memoryCacheKey(key)
        }.build()).proceed()
        if (key != repo.artworkCacheKey(model))
            throw kotlinx.coroutines.CancellationException("Monogram artwork revision changed")
        return result
    }
}
