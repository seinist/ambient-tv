package com.ambient.tv.network

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.ambient.tv.data.browse.displayArtworkUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persist the same artwork under the same key whether its URL came directly from a
 * provider or from a token-free browse snapshot. Include the hashed provider scope:
 * two accounts sharing a host must never share their private image cache entries.
 *
 * Only media-server endpoints are rewritten. External images retain Coil's own key,
 * avoiding collisions between arbitrary signed CDN URLs with distinct query semantics.
 */
@Singleton
class MediaArtworkDiskCacheInterceptor @Inject constructor(
    private val refresh: MediaArtworkRefresh,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        if (request.diskCacheKey != null) return chain.proceed()
        val identity = try { refresh.identity(url) }
        catch (_: android.database.sqlite.SQLiteException) {
            // A broken metadata cache must not hide artwork or reuse an uncertain revision.
            return chain.withRequest(request.newBuilder()
                .diskCachePolicy(coil3.request.CachePolicy.DISABLED)
                .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build()).proceed()
        }
        if (identity == null) return chain.proceed()
        return chain.withRequest(request.newBuilder().diskCacheKey(identity.diskKey)
            .memoryCacheKeyExtra(MEDIA_ARTWORK_IDENTITY_EXTRA, identity.imageKey)
            .memoryCacheKeyExtra(MEDIA_ARTWORK_REVISION_EXTRA, identity.revision).build()).proceed()
    }
}

/** Safe to persist: source scope is hashed and the canonical URL contains no auth query. */
internal fun normalizedMediaArtworkDiskKey(url: String, sourceScope: String): String? =
    displayArtworkUrl(url)?.let { "media-artwork-v1:$sourceScope:$it" }
