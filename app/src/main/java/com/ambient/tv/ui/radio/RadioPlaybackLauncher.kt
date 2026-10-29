package com.ambient.tv.ui.radio

import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.radio.RadioBrowserRepository
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.PlaybackEngineFactory
import com.ambient.tv.ui.player.PlaybackMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class RadioPlaybackLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RadioBrowserRepository,
    private val preferences: AppPreferences,
    private val queue: RadioPlaybackQueue,
) {
    private val playMutex = Mutex()

    suspend fun play(
        station: RadioStation,
        stations: List<RadioStation>? = null,
    ): Result<Unit> = playMutex.withLock {
        runCatching {
        val streamUrl = repository.playbackUrl(station)
        if (stations != null) queue.replace(stations, station.id) else queue.select(station.id)
        preferences.recordRadioStation(station)

        val player = PlaybackEngineFactory.create(
            context = context,
            streamUrl = streamUrl,
            startPositionMs = 0L,
            metadata = PlaybackMetadata(
                route = Destinations.RADIO,
                title = station.name,
                subtitle = radioStationSubtitle(station),
                source = "radio",
                mediaId = station.id,
                hasVideo = false,
                artworkUrl = station.artworkUrl,
                artworkAspectRatio = 1f,
                isLive = true,
            ),
            audioOnly = true,
            liveStream = true,
        )

        fun publishNowPlaying(value: CharSequence?) {
            val nowPlaying = radioNowPlayingTitle(value, station.name) ?: return
            val current = BackgroundPlaybackSession.snapshot.value.metadata ?: return
            // A delayed callback from the previous stream must never relabel the new station.
            if (current.source != "radio" || current.mediaId != station.id || current.subtitle == nowPlaying) return
            BackgroundPlaybackSession.refreshMetadata(current.copy(subtitle = nowPlaying))
        }

        player.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                publishNowPlaying(mediaMetadata.title)
            }
        })
        // Some streams expose the first ICY title before the listener is attached.
        publishNowPlaying(player.mediaMetadata.title)
        }
    }
}

internal fun radioStationSubtitle(station: RadioStation): String = listOfNotNull(
    station.country,
    station.codec,
    station.bitrateKbps?.let { "$it kbps" },
).joinToString(" • ")

internal fun radioNowPlayingTitle(value: CharSequence?, stationName: String): String? =
    value?.toString()?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals(stationName.trim(), ignoreCase = true) }
