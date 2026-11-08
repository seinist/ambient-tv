package com.ambient.tv.car

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.ambient.tv.BuildConfig
import com.ambient.tv.livetv.LiveTvRegistry
import com.ambient.tv.livetv.LiveTvRepository
import com.ambient.tv.ui.common.requestHeaders
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Car clients read local artwork URIs; server URLs, tickets and credentials stay in the app. */
internal object CarAudioArtwork {
    fun uri(context: Context, mediaId: String): Uri {
        require(liveSpec(mediaId) != null) { "Expected a live radio ID" }
        return Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.caraudioart")
            .appendPath("live")
            .appendPath(mediaId)
            .build()
    }

    fun mediaId(uri: Uri, packageName: String = BuildConfig.APPLICATION_ID): String? {
        if (uri.scheme != "content" || uri.authority != "$packageName.caraudioart" ||
            uri.query != null || uri.fragment != null) return null
        val path = uri.pathSegments
        if (path.size != 2 || path[0] != "live") return null
        return path[1].takeIf { liveSpec(it) != null }
    }

    private fun liveSpec(mediaId: String): Pair<String, String>? =
        mediaId.takeIf { it.length <= 1024 }?.let(CarAudioIds::liveAudioSpec)
            ?.takeIf { (source, channel) ->
                (source.startsWith("tvheadend:") || source.startsWith("iptv:")) &&
                    source.substringAfter(':').isNotBlank() && channel.isNotBlank() && '\n' !in channel
            }
}

internal data class CarAudioArtworkRequest(val url: String, val headers: Map<String, String>)

/** Resolve through the configured source, never through a URL supplied by a car client. */
internal suspend fun carLiveArtworkRequest(
    mediaId: String,
    repositories: List<LiveTvRepository>,
): CarAudioArtworkRequest? {
    val (sourceId, uuid) = CarAudioIds.liveAudioSpec(mediaId) ?: return null
    val repository = repositories.firstOrNull {
        it.source.id == sourceId && it.source.system in setOf("iptv", "tvheadend")
    } ?: return null
    // Repeated playlist rows can supply the missing logo in a later occurrence of the same ID.
    val url = repository.channels().asSequence()
        .filter { it.uuid == uuid && carLiveAudioEligible(it) }
        .mapNotNull { it.iconUrl }
        .firstOrNull {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        } ?: return null
    return CarAudioArtworkRequest(url, repository.authHeaders())
}

/**
 * Loads only configured radio channel logos on demand, off the UI thread. Coil supplies the
 * shared, bounded memory/disk caches and the app's credential/redirect handling. A pipe keeps
 * image bytes out of the media browser's Binder result and preserves transparent station logos.
 */
class CarAudioArtworkProvider : ContentProvider() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun liveTv(): LiveTvRegistry
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Car artwork is read-only")
        val context = context ?: throw FileNotFoundException()
        val mediaId = CarAudioArtwork.mediaId(uri, context.packageName) ?: throw FileNotFoundException()
        val bitmap = runBlocking(Dispatchers.IO) {
            try {
                withTimeoutOrNull(10_000L) {
                    val liveTv = EntryPointAccessors.fromApplication(
                        context.applicationContext, Dependencies::class.java,
                    ).liveTv()
                    val artwork = carLiveArtworkRequest(mediaId, liveTv.configured()) ?: return@withTimeoutOrNull null
                    val request = ImageRequest.Builder(context)
                        .data(artwork.url)
                        .size(512)
                        .allowHardware(false)
                        .requestHeaders(artwork.headers)
                        .build()
                    (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Offline/removed sources are missing artwork, never a provider or app crash.
                null
            }
        } ?: throw FileNotFoundException("Radio artwork is unavailable")
        return openPipeHelper(uri, "image/png", null, bitmap) { output, _, _, _, image ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                    image.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            } catch (_: IOException) {
                // A car client may close its end when the visible page changes.
            }
        }
    }

    override fun getType(uri: Uri): String? =
        if (CarAudioArtwork.mediaId(uri, context?.packageName ?: BuildConfig.APPLICATION_ID) != null) "image/png" else null

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Car artwork is read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Car artwork is read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Car artwork is read-only")
}
