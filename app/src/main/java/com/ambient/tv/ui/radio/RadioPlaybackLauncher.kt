package com.ambient.tv.ui.radio

import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

@Singleton
class RadioPlaybackLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RadioBrowserRepository,
    private val preferences: AppPreferences,
    private val queue: RadioPlaybackQueue,
) {
    private val playMutex = Mutex()
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var reconnectJob: Job? = null
    private var reconnectStationId: String? = null
    private var reconnectAttempt = 0

    suspend fun play(
        station: RadioStation,
        stations: List<RadioStation>? = null,
    ): Result<Unit> = playInternal(station, stations, reconnect = false)

    private suspend fun playInternal(
        station: RadioStation,
        stations: List<RadioStation>?,
        reconnect: Boolean,
    ): Result<Unit> = playMutex.withLock {
        runCatching {
            if (!reconnect) {
                reconnectJob?.cancel()
                reconnectJob = null
                reconnectStationId = station.id
                reconnectAttempt = 0
            }

            // A reconnect refreshes the catalog row but does not count another user click or
            // duplicate the Recent entry. The stored URL is still the final offline fallback.
            val currentStation = if (reconnect) repository.station(station.id) ?: station else station
            val streamUrl = if (reconnect) currentStation.streamUrl else repository.playbackUrl(currentStation)

            if (stations != null) queue.replace(stations, currentStation.id) else queue.select(currentStation.id)
            if (!reconnect) preferences.recordRadioStation(currentStation)

            val player = PlaybackEngineFactory.create(
                context = context,
                streamUrl = streamUrl,
                startPositionMs = 0L,
                mimeType = if (currentStation.isHls) MimeTypes.APPLICATION_M3U8 else null,
                metadata = PlaybackMetadata(
                    route = Destinations.RADIO,
                    title = currentStation.name,
                    subtitle = radioStationSubtitle(currentStation),
                    source = "radio",
                    mediaId = currentStation.id,
                    hasVideo = false,
                    artworkUrl = currentStation.artworkUrl,
                    artworkAspectRatio = 1f,
                    isLive = true,
                ),
                audioOnly = true,
                liveStream = true,
            )

            fun publishNowPlaying(value: CharSequence?) {
                val nowPlaying = radioNowPlayingTitle(value, currentStation.name) ?: return
                val current = BackgroundPlaybackSession.snapshot.value.metadata ?: return
                // A delayed callback from the previous stream must never relabel the new station.
                if (current.source != "radio" ||
                    current.mediaId != currentStation.id ||
                    current.subtitle == nowPlaying
                ) return
                BackgroundPlaybackSession.refreshMetadata(current.copy(subtitle = nowPlaying))
            }

            player.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    publishNowPlaying(mediaMetadata.title)
                }

                override fun onPlayerError(error: PlaybackException) {
                    scheduleReconnect(currentStation, error)
                }
            })
            // Some streams expose the first ICY title before the listener is attached.
            publishNowPlaying(player.mediaMetadata.title)
        }
    }

    private fun scheduleReconnect(station: RadioStation, error: Throwable) {
        val metadata = BackgroundPlaybackSession.snapshot.value.metadata
        if (!shouldReconnectRadio(
                source = metadata?.source,
                mediaId = metadata?.mediaId,
                stationId = station.id,
                activeStationId = reconnectStationId,
                attempt = reconnectAttempt,
            )
        ) return

        reconnectAttempt += 1
        val attempt = reconnectAttempt
        reconnectJob?.cancel()
        reconnectJob = reconnectScope.launch {
            val delayMs = radioReconnectDelayMs(attempt)
            Timber.w(error, "Radio stream failed; retry %d/%d in %d ms", attempt, MAX_RADIO_RECONNECT_ATTEMPTS, delayMs)
            delay(delayMs)

            val current = BackgroundPlaybackSession.snapshot.value.metadata
            if (current?.source != "radio" ||
                current.mediaId != station.id ||
                reconnectStationId != station.id
            ) return@launch

            playInternal(station, stations = null, reconnect = true)
                .onFailure { Timber.w(it, "Radio reconnect %d failed to start", attempt) }
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

internal const val MAX_RADIO_RECONNECT_ATTEMPTS = 3

internal fun radioReconnectDelayMs(attempt: Int): Long = when (attempt.coerceAtLeast(1)) {
    1 -> 1_000L
    2 -> 2_500L
    else -> 5_000L
}

internal fun shouldReconnectRadio(
    source: String?,
    mediaId: String?,
    stationId: String,
    activeStationId: String?,
    attempt: Int,
): Boolean = source == "radio" &&
    mediaId == stationId &&
    activeStationId == stationId &&
    attempt < MAX_RADIO_RECONNECT_ATTEMPTS
