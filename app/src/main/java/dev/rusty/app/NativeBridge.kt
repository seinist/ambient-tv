package dev.rusty.app

import android.content.Context
import android.util.Log

/**
 * Binary-compatible Kotlin side for Rusty's MIT-licensed librespot Android core.
 * Keeping this package/class name is intentional: the prebuilt Rust JNI symbols target
 * Java_dev_rusty_app_NativeBridge_*.
 */
object NativeBridge {
    val available: Boolean
    init {
        var external = false
        available = runCatching {
            val loaded = com.ambient.tv.modules.SystemModuleHost.loadSelected(com.ambient.tv.modules.SystemModule.SPOTIFY)
            external = loaded != null
            if (loaded == null) {
                if (!com.ambient.tv.modules.SystemModuleAvailability.bundled(com.ambient.tv.modules.SystemModule.SPOTIFY))
                    return@runCatching false
                // Rusty >= 2.7 no longer links libc++_shared.
                runCatching { System.loadLibrary("c++_shared") }
                System.loadLibrary("spotify_receiver_core")
            }
            true
        }.onFailure { Log.w("SpotifyNative", "Spotify receiver unavailable", it) }.getOrDefault(false)
        com.ambient.tv.modules.SystemModuleAvailability.reportProbe(
            com.ambient.tv.modules.SystemModule.SPOTIFY, external, available,
        )
    }

    external fun initAndroidContext(context: Context, cacheDir: String)
    external fun initLogger()
    /** [startupVolumePercent] (0..100) is the volume a new Connect session starts at. */
    external fun startDevice(deviceName: String, deviceId: String, bitrateKbps: Int, startupVolumePercent: Int)
    external fun stopDevice()
    external fun play()
    external fun pause()
    external fun nextTrack()
    external fun previousTrack()
    external fun setSpotifyAttenuation(factor: Float, fadeMs: Int)
    external fun renameDevice(deviceName: String)
    external fun setStartupVolume(percent: Int)
    external fun requestAccessToken()
    /** Ambient TV patch (scripts/patches): signs the receiver in without a Connect handoff. */
    external fun loginWithAccessToken(token: String, clientId: String): Boolean
}
