@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.ThemeMusic
import com.ambient.tv.spotify.playback.SpotifyPlaybackEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Plays the theme song of the open detail page quietly and without any player chrome.
 *
 * It is deliberately not a [BackgroundPlaybackSession] engine: no media session, no resume
 * state, no audio DSP or passthrough. Real playback always wins and silences it at once.
 * Main looper only, like every other player in the app.
 *
 * Audio attributes use game usage without requesting audio focus so theme music never fights
 * PlaybackService / Spotify for MEDIA focus. App-side silencing via
 * [BackgroundPlaybackSession] / Spotify pauses and ducks instead of hard-releasing.
 */
@Singleton
class ThemeMusicPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    spotify: SpotifyPlaybackEngine,
) {
    private val spotifyState = spotify.state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var songId: String? = null
    private var owner: Any? = null
    private var fade: Job? = null
    private var silenceWatch: Job? = null

    val isSilenced: Boolean
        get() = BackgroundPlaybackSession.snapshot.value.active || spotifyState.value.playing

    /** True while a theme sample is actually producing audible output (nav-sound mute hook). */
    val isAudible: Boolean
        get() {
            val current = player ?: return false
            return current.isPlaying && current.volume > 0.02f
        }

    /** True while real playback owns the audio output. */
    val silenced: Flow<Boolean> = combine(BackgroundPlaybackSession.snapshot, spotifyState) { playback, spotify ->
        playback.active || spotify.playing
    }.distinctUntilChanged()

    private fun targetVolume(): Float = preferences.themeMusicVolume.coerceIn(0f, 1f)

    /** [owner] is the page asking; only the page that asked last may [stop] the song again. */
    fun play(music: ThemeMusic, owner: Any) {
        if (isSilenced) return
        val current = player
        if (current != null && music.id == songId) {
            // Same song on the next page (or after silence): cancel the pending stop / resume.
            this.owner = owner
            if (!current.isPlaying) {
                current.playWhenReady = true
                current.play()
            }
            fadeTo(current, targetVolume(), FADE_IN_MS)
            return
        }
        release()
        this.owner = owner
        songId = music.id
        val created = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_GAME)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            setHandleAudioBecomingNoisy(true)
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            addListener(object : Player.Listener {
                // Never log the URL: it carries the server token.
                override fun onPlayerError(error: PlaybackException) {
                    Timber.tag("ThemeMusic").w("Theme music failed: %s", error.errorCodeName)
                }
            })
            setMediaItem(MediaItem.fromUri(music.url))
            playWhenReady = true
            prepare()
        }
        player = created
        fadeTo(created, targetVolume(), FADE_IN_MS)
        // The page that started the song is gone by the time playback begins, so watch here.
        if (silenceWatch == null) {
            silenceWatch = scope.launch {
                silenced.collect { silencedNow -> if (silencedNow) pauseForSilence() }
            }
        }
    }

    /** Fades out after a short grace period in which the next page may continue the same song. */
    fun stop(owner: Any) {
        // Pages overlap during navigation: a page that already handed over must not stop its successor.
        if (owner !== this.owner) return
        val current = player ?: return
        fade?.cancel()
        fade = scope.launch {
            delay(HANDOVER_MS)
            if (owner !== this@ThemeMusicPlayer.owner) return@launch
            ramp(current, 0f, FADE_OUT_MS)
            release()
        }
    }

    /** Duck + pause so returning to the detail page can resume without a cold start. */
    private fun pauseForSilence() {
        val current = player ?: return
        fade?.cancel()
        fade = null
        current.volume = 0f
        current.pause()
    }

    private fun fadeTo(target: ExoPlayer, volume: Float, durationMs: Long) {
        fade?.cancel()
        fade = scope.launch { ramp(target, volume, durationMs) }
    }

    private suspend fun ramp(target: ExoPlayer, volume: Float, durationMs: Long) {
        val start = target.volume
        val steps = (durationMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
        for (step in 1..steps) {
            delay(FADE_STEP_MS)
            target.volume = start + (volume - start) * step / steps
        }
        target.volume = volume
    }

    private fun release() {
        fade?.cancel()
        fade = null
        player?.release()
        player = null
        songId = null
        owner = null
    }

    private companion object {
        const val FADE_IN_MS = 1_200L
        const val FADE_OUT_MS = 800L
        // Show → season → episode pages share one song; the next page takes it over without a gap.
        const val HANDOVER_MS = 1_000L
        const val FADE_STEP_MS = 40L
    }
}
