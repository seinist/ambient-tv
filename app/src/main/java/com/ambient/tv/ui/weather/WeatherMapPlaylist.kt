package com.ambient.tv.ui.weather

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.ambient.tv.data.weather.WeatherMapFrame
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherMapRepository
import com.ambient.tv.data.weather.WeatherMapSeries
import com.ambient.tv.data.weather.WeatherMapViewport
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking

internal const val WEATHER_PLAYER_FRAME_DURATION_MS = 1_100L

internal data class WeatherMapFrameStatus(val baseMissing: Boolean, val coverageMissing: Boolean)

/** Preserve the observed frame timestamp when changing zoom, without assuming uniform source intervals. */
internal fun weatherPlayerFrameIndex(frames: List<WeatherMapFrame>, requestedEpoch: Long): Int =
    if (frames.isEmpty()) 0 else if (requestedEpoch <= 0L) frames.lastIndex
    else frames.indices.minByOrNull { index ->
        val millis = frames[index].epochMillis
        if (millis >= requestedEpoch) millis - requestedEpoch else requestedEpoch - millis
    } ?: frames.lastIndex

/**
 * The shared player seek bar works in elapsed milliseconds, while radar and satellite playlists
 * are discrete observations (often minutes or days apart). A short remote-key step must still
 * move at least one frame, while a large touch jump should select the closest timestamp.
 */
internal fun weatherPlayerSeekTargetIndex(
    frames: List<WeatherMapFrame>,
    currentIndex: Int,
    deltaMs: Long,
): Int {
    if (frames.isEmpty()) return 0
    val current = currentIndex.coerceIn(0, frames.lastIndex)
    if (deltaMs == 0L) return current
    val timestamp = frames[current].epochMillis + deltaMs
    // weatherPlayerFrameIndex() treats <= 0 as the special "latest" startup request.
    // A legitimate backward seek can cross epoch zero; here it must choose the first
    // observed frame instead of incorrectly jumping toward the end of the playlist.
    val closest = frames.indices.minByOrNull { index ->
        val epoch = frames[index].epochMillis
        if (epoch >= timestamp) epoch - timestamp else timestamp - epoch
    } ?: current
    return if (deltaMs > 0) closest.coerceAtLeast(current + 1).coerceAtMost(frames.lastIndex)
    else closest.coerceAtMost(current - 1).coerceAtLeast(0)
}

/**
 * Media3 resolves each image as needed, so opening a long radar timeline does not download
 * every frame before the first picture appears. Only images already composited by the same
 * weather repository used in the Weather view can reach the player.
 */
@UnstableApi
internal class WeatherMapPlaylist(
    context: Context,
    private val repository: WeatherMapRepository,
    private val provider: WeatherMapProvider,
    private val viewport: WeatherMapViewport,
    private val series: WeatherMapSeries,
    private val networkAllowed: Boolean,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableFrameStatuses = MutableStateFlow<Map<Int, WeatherMapFrameStatus>>(emptyMap())
    val frameStatuses = mutableFrameStatuses.asStateFlow()
    private val resolver = ResolvingDataSource.Resolver { spec ->
        val uri = spec.uri
        val index = if (uri.scheme == "weatherframe" && uri.host == "timeline")
            uri.lastPathSegment?.removeSuffix(".png")?.toIntOrNull()
        else null
        if (index == null || index !in series.frames.indices) {
            throw IOException("Invalid weather frame")
        }
        val resolved = try {
            runBlocking {
                scope.async {
                    repository.playbackFrameFile(provider, viewport, series, index, networkAllowed)
                }.await()
            }
        } catch (cancelled: CancellationException) {
            throw IOException("Weather playback cancelled", cancelled)
        } catch (error: Exception) {
            throw IOException("Weather frame unavailable", error)
        } ?: throw IOException("Weather frame unavailable")
        mutableFrameStatuses.update {
            it + (index to WeatherMapFrameStatus(resolved.baseMissing, resolved.coverageMissing))
        }
        spec.withUri(Uri.fromFile(resolved.file))
    }

    val dataSourceFactory = ResolvingDataSource.Factory(DefaultDataSource.Factory(context), resolver)

    fun mediaItems(): List<MediaItem> = series.frames.indices.map { index ->
        MediaItem.Builder()
            .setMediaId("weather-${provider.id}-${series.frames[index].epochMillis}")
            .setUri(Uri.parse("weatherframe://timeline/$index.png"))
            .setMimeType(MimeTypes.IMAGE_PNG)
            .setImageDurationMs(WEATHER_PLAYER_FRAME_DURATION_MS)
            .build()
    }

    override fun close() { scope.cancel() }
}
