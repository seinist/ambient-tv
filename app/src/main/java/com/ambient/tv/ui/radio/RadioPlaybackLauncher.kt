@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.radio

import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.radio.RadioBrowserRepository
import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.data.radio.localizedLanguageNames
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.PlaybackEngineFactory
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.util.suspendResult
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
import java.util.Locale

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
        source: RadioBrowseQueueSource? = null,
    ): Result<Unit> = playInternal(station, stations, source, reconnect = false)

    private suspend fun playInternal(
        station: RadioStation,
        stations: List<RadioStation>?,
        source: RadioBrowseQueueSource?,
        reconnect: Boolean,
    ): Result<Unit> = playMutex.withLock {
        suspendResult {
            if (!reconnect) {
                reconnectJob?.cancel()
                reconnectJob = null
                // Keep the current stream as reconnect owner until the replacement really starts.
            }

            // A reconnect refreshes the catalog row but does not count another user click or
            // duplicate the Recent entry. The stored URL is still the final offline fallback.
            val currentStation = if (reconnect) repository.station(station.id) ?: station else station
            val streamUrl = if (reconnect) currentStation.streamUrl else repository.playbackUrl(currentStation)

            val player = PlaybackEngineFactory.create(
                context = context,
                streamUrl = streamUrl,
                startPositionMs = 0L,
                mimeType = if (currentStation.isHls) MimeTypes.APPLICATION_M3U8 else null,
                metadata = PlaybackMetadata(
                    route = Destinations.RADIO_PLAYER,
                    title = currentStation.name,
                    // Technical station details belong to badges and the information panel.
                    // The subtitle is reserved exclusively for dynamic now-playing data.
                    subtitle = "",
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

            // Commit browse/player state only after the replacement player exists. A failed or
            // cancelled start must leave the previous queue and Recent history untouched.
            if (stations != null) queue.replace(stations, currentStation.id, source) else queue.select(currentStation.id)
            if (!reconnect) {
                reconnectStationId = currentStation.id
                reconnectAttempt = 0
                preferences.recordRadioStation(currentStation)
            }

            val playbackGeneration = BackgroundPlaybackSession.snapshot.value.generation

            fun publishNowPlaying(value: CharSequence?) {
                val nowPlaying = radioNowPlayingTitle(value, currentStation.name) ?: return
                val snapshot = BackgroundPlaybackSession.snapshot.value
                val current = snapshot.metadata ?: return
                // A delayed callback from an older stream generation — including an earlier
                // connection of the same station — must never relabel the replacement.
                if (snapshot.generation != playbackGeneration ||
                    current.source != "radio" ||
                    current.mediaId != currentStation.id ||
                    current.subtitle == nowPlaying
                ) return
                BackgroundPlaybackSession.refreshMetadata(current.copy(subtitle = nowPlaying))
            }

            player.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    publishNowPlaying(
                        radioMetadataNowPlaying(mediaMetadata.title, mediaMetadata.artist, currentStation.name),
                    )
                }

                // Some Icecast/Shoutcast servers send their song updates as timed ICY
                // metadata without changing the combined MediaMetadata.title.
                override fun onMetadata(metadata: Metadata) {
                    (0 until metadata.length())
                        .mapNotNull { metadata.get(it) as? IcyInfo }
                        .lastOrNull()?.title?.let(::publishNowPlaying)
                }

                override fun onPlayerError(error: PlaybackException) {
                    scheduleReconnect(currentStation, playbackGeneration, error)
                }
            })
            // Some streams expose the first ICY title before the listener is attached.
            publishNowPlaying(
                radioMetadataNowPlaying(player.mediaMetadata.title, player.mediaMetadata.artist, currentStation.name),
            )
        }
    }

    private fun scheduleReconnect(station: RadioStation, expectedGeneration: Long, error: Throwable) {
        val snapshot = BackgroundPlaybackSession.snapshot.value
        val metadata = snapshot.metadata
        if (!shouldReconnectRadio(
                source = metadata?.source,
                mediaId = metadata?.mediaId,
                stationId = station.id,
                activeStationId = reconnectStationId,
                currentGeneration = snapshot.generation,
                expectedGeneration = expectedGeneration,
                attempt = reconnectAttempt,
            )
        ) return
        // One player error can fan out through more than one Media3 callback. A pending retry
        // already owns this failure; duplicate callbacks must not consume extra attempts.
        if (reconnectJob?.isActive == true) return

        reconnectAttempt += 1
        val attempt = reconnectAttempt
        reconnectJob?.cancel()
        reconnectJob = reconnectScope.launch {
            val delayMs = radioReconnectDelayMs(attempt)
            Timber.w(error, "Radio stream failed; retry %d/%d in %d ms", attempt, MAX_RADIO_RECONNECT_ATTEMPTS, delayMs)
            delay(delayMs)

            val currentSnapshot = BackgroundPlaybackSession.snapshot.value
            val current = currentSnapshot.metadata
            if (currentSnapshot.generation != expectedGeneration ||
                current?.source != "radio" ||
                current.mediaId != station.id ||
                reconnectStationId != station.id
            ) return@launch

            playInternal(station, stations = null, source = null, reconnect = true)
                .onFailure { Timber.w(it, "Radio reconnect %d failed to start", attempt) }
        }
    }
}

internal fun radioStationSubtitle(
    station: RadioStation,
    displayLocale: Locale = Locale.getDefault(),
): String = listOfNotNull(
    station.country,
    station.localizedLanguageNames(displayLocale).firstOrNull(),
    station.codec,
    station.bitrateKbps?.let { "$it kbps" },
).joinToString(" • ")

internal fun radioNowPlayingTitle(value: CharSequence?, stationName: String): String? =
    value?.toString()?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals(stationName.trim(), ignoreCase = true) }

/** Prefer a stream's full title, adding a separately supplied artist only when not already present. */
internal fun radioMetadataNowPlaying(
    title: CharSequence?,
    artist: CharSequence?,
    stationName: String,
): String? {
    val track = radioNowPlayingTitle(title, stationName) ?: return null
    val performer = artist?.toString()?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals(stationName.trim(), ignoreCase = true) }
    if (performer == null || track.contains(performer, ignoreCase = true)) return track
    // Explicit artist and title are separate fields; combine them for the existing
    // subtitle transport used by the playback session and notification.
    return performer + " - " + track
}

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
    currentGeneration: Long,
    expectedGeneration: Long,
    attempt: Int,
): Boolean = source == "radio" &&
    mediaId == stationId &&
    activeStationId == stationId &&
    currentGeneration == expectedGeneration &&
    attempt < MAX_RADIO_RECONNECT_ATTEMPTS
