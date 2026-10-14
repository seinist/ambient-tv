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
        available = try {
            // Rusty >= 2.7 no longer links libc++_shared; other native components may still ship it.
            runCatching { System.loadLibrary("c++_shared") }
            System.loadLibrary("spotify_receiver_core")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w("SpotifyNative", "libspotify_receiver_core unavailable: ${e.message}")
            false
        }
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
}
