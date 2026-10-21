package dev.rusty.app

import com.ambient.tv.spotify.playback.LibrespotNativeStateBus

/**
 * JNI callback target expected by Rusty's librespot core. This is intentionally not an Android
 * Service; the app owns lifecycle in LibrespotPlaybackEngine while the native core only requires
 * these static callback names.
 */
class SpotifyService {
    companion object {
        @JvmStatic fun onReceiverRenamed(newName: String) = LibrespotNativeStateBus.starting(newName)
        @JvmStatic fun onNativeReceiverConnected(username: String?) {
            LibrespotNativeStateBus.connected(username)
            if (NativeBridge.available) runCatching { NativeBridge.requestAccessToken() }
        }
        @JvmStatic fun onNativeReceiverDisconnected() = LibrespotNativeStateBus.disconnected()
        @JvmStatic fun onNativeAccessToken(token: String?, expiresInSecs: Int) = LibrespotNativeStateBus.token(token)

        /** Visualizer PCM from [dev.rusty.app.audio.PcmTap]: mono f32, 44.1 kHz. */
        @JvmStatic fun onNativePcm(samples: FloatArray, sampleRateHz: Int) =
            com.ambient.tv.spotify.SpotifyAudioAnalysis.onPcm(samples, sampleRateHz)
        // Rusty 2.8.2 adds autoAdvanced; retain the older JNI signature for cached cores.
        @Suppress("UNUSED_PARAMETER")
        @JvmStatic @JvmOverloads fun onNativePlaybackEvent(
            playbackState: String,
            title: String?,
            artist: String?,
            elapsedMs: Long,
            durationMs: Long,
            queueTitle: String?,
            queueArtist: String?,
            queueUnavailable: Boolean,
            coverUrl: String?,
            trackId: String?,
            autoAdvanced: Boolean = false,
        ) = LibrespotNativeStateBus.playback(playbackState, title, artist, elapsedMs, durationMs, queueTitle, queueArtist, coverUrl, trackId)
    }
}
