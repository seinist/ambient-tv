package com.ambient.tv.spotify.playback

import com.ambient.tv.spotify.SpotifyPlaybackState
import kotlinx.coroutines.flow.StateFlow

/**
 * Native Spotify audio boundary used by the application layer.
 *
 * Catalog, account and OAuth concerns intentionally do not belong here. Implementations only
 * own the in-app playback runtime: Connect discovery/session, stream/decode/output and transport
 * controls. The current implementation is librespot/Rusty, but callers must not depend on that.
 */
interface SpotifyPlaybackEngine {
    val state: StateFlow<SpotifyPlaybackState>
    val available: Boolean

    fun start(config: SpotifyPlaybackEngineConfig): Boolean
    fun stop()
    /** Transport commands report whether they were accepted by the native boundary. */
    fun play(): Boolean
    fun pause(): Boolean
    fun next(): Boolean
    fun previous(): Boolean

    /** Enables decoded PCM observation for in-app visualizers when the engine supports it. */
    fun setPcmTapEnabled(enabled: Boolean): Boolean
}

data class SpotifyPlaybackEngineConfig(
    val deviceName: String,
    val deviceId: String,
    val bitrateKbps: Int,
)
