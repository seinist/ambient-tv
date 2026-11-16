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
import kotlinx.coroutines.runBlocking

internal const val WEATHER_PLAYER_FRAME_DURATION_MS = 1_100L

/** Preserve the observed frame timestamp when changing zoom, without assuming uniform source intervals. */
internal fun weatherPlayerFrameIndex(frames: List<WeatherMapFrame>, requestedEpoch: Long): Int =
    if (frames.isEmpty()) 0 else if (requestedEpoch <= 0L) frames.lastIndex
    else frames.indices.minByOrNull { index ->
        val millis = frames[index].epochMillis
        if (millis >= requestedEpoch) millis - requestedEpoch else requestedEpoch - millis
    } ?: frames.lastIndex

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
    private val resolver = ResolvingDataSource.Resolver { spec ->
        val uri = spec.uri
        val index = if (uri.scheme == "weatherframe" && uri.host == "timeline")
            uri.lastPathSegment?.removeSuffix(".png")?.toIntOrNull()
        else null
        if (index == null || index !in series.frames.indices) {
            throw IOException("Invalid weather frame")
        }
        val file = try {
            runBlocking {
                scope.async {
                    repository.playbackImageFile(provider, viewport, series, index, networkAllowed)
                }.await()
            }
        } catch (cancelled: CancellationException) {
            throw IOException("Weather playback cancelled", cancelled)
        } catch (error: Exception) {
            throw IOException("Weather frame unavailable", error)
        } ?: throw IOException("Weather frame unavailable")
        spec.withUri(Uri.fromFile(file))
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
