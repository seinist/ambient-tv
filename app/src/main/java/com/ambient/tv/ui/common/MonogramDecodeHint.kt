package com.ambient.tv.ui.common

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import com.ambient.tv.AmbientTvApplication
import com.ambient.tv.data.telegram.MonogramThumbRef
import com.ambient.tv.data.telegram.monogramThumbModel
import kotlinx.coroutines.sync.Semaphore

internal val LocalMonogramDecodeHint = staticCompositionLocalOf<MonogramDecodeHint?> { null }

/** One opportunistic cache-only decode, never on a low-RAM device. The owner is the viewport. */
internal class MonogramDecodeHint(context: Context) {
    private val app = context.applicationContext as? AmbientTvApplication
    private val enabled = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice != true
    private data class Hint(val size: IntSize, val rgb565: Boolean)
    private var hint: Hint? = null
    private val slot = Semaphore(1)

    fun record(size: IntSize, rgb565: Boolean) {
        if (enabled && size.width in 1..1024 && size.height in 1..1024) hint = Hint(size, rgb565)
    }

    suspend fun warm(ref: MonogramThumbRef) {
        val application = app ?: return
        val current = hint ?: return
        if (!enabled || !slot.tryAcquire()) return
        try {
            val model = monogramThumbModel(ref.chatId, ref.messageId, ref.fallback)
            val repository = application.telegramRepository.get()
            val key = repository.artworkCacheKey(model) ?: return
            val loader = SingletonImageLoader.get(application)
            val request = ImageRequest.Builder(application)
                .data(model).memoryCacheKey(key)
                .memoryCacheKeyExtra(com.ambient.tv.network.MEDIA_ARTWORK_HEADERS_EXTRA,
                    com.ambient.tv.network.mediaArtworkHeadersKey(emptyMap()))
                .networkCachePolicy(CachePolicy.DISABLED)
                .size(current.size.width, current.size.height)
                .precision(Precision.INEXACT)
                .scale(Scale.FILL)
                .apply { if (current.rgb565) bitmapConfig(android.graphics.Bitmap.Config.RGB_565) }
                .build()
            val result = loader.execute(request) as? SuccessResult ?: return
            if (key == repository.artworkCacheKey(model)) result.memoryCacheKey?.let {
                rememberPrefetchedMonogramArtwork(key, it)
            }
        } finally {
            slot.release()
        }
    }
}
