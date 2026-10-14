package com.ambient.tv.spotify.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.ambient.tv.R
import com.ambient.tv.spotify.spotifyNetworkChangeNeedsRestart
import com.ambient.tv.spotify.spotifyNetworkKey
import java.net.Inet4Address
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.rusty.app.NativeBridge
import dev.rusty.app.audio.PcmTap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Rusty/librespot implementation of [SpotifyPlaybackEngine].
 *
 * This class is the only application-layer owner of the native receiver lifecycle. No OAuth,
 * browse, library or Web API behavior is implemented here.
 */
@Singleton
class LibrespotPlaybackEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpotifyPlaybackEngine {
    override val state: StateFlow<com.ambient.tv.spotify.SpotifyPlaybackState> =
        LibrespotNativeStateBus.state
    override val available: Boolean
        get() = NativeBridge.available

    private val started = AtomicBoolean(false)
    @Volatile private var currentName: String? = null
    private var currentBitrate: Int? = null
    @Volatile private var startGeneration = 0L
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentConfig: SpotifyPlaybackEngineConfig? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkKey: String? = null
    @Volatile private var restartPending = false
    private var restartJob: Job? = null

    init {
        scope.launch {
            state.collect { playback ->
                synchronized(this@LibrespotPlaybackEngine) { syncPlaybackWakeLock(playback.playing && started.get()) }
                if (!playback.playing && restartPending) scheduleNetworkRestart()
            }
        }
    }

    @Synchronized
    override fun start(config: SpotifyPlaybackEngineConfig): Boolean {
        if (!available) return false
        return runCatching {
            if (started.get() && currentBitrate != config.bitrateKbps) stop()
            if (started.compareAndSet(false, true)) {
                val generation = ++startGeneration
                acquireDiscoveryLock()
                NativeBridge.initAndroidContext(context.applicationContext, context.cacheDir.absolutePath)
                NativeBridge.initLogger()
                LibrespotNativeStateBus.starting(config.deviceName)
                currentName = config.deviceName
                currentBitrate = config.bitrateKbps
                currentConfig = config
                watchNetwork()
                Thread({
                    runCatching {
                        if (generation != startGeneration || !started.get()) return@runCatching
                        NativeBridge.startDevice(
                            config.deviceName,
                            config.deviceId,
                            config.bitrateKbps,
                            STARTUP_VOLUME_PERCENT,
                        )
                        // stop() may have raced a native start that was already in progress. A
                        // second stop after start returns prevents a late receiver resurrection
                        // without holding the Kotlin monitor across a potentially blocking JNI call.
                        if (generation != startGeneration || !started.get()) {
                            runCatching { NativeBridge.stopDevice() }
                        }
                    }.onFailure { error ->
                        synchronized(this@LibrespotPlaybackEngine) {
                            if (generation == startGeneration) {
                                stop()
                                LibrespotNativeStateBus.error(
                                    error.message ?: context.getString(R.string.spotify_native_start_failed),
                                )
                            }
                        }
                    }
                }, "spotify-librespot").start()
            } else if (currentName != config.deviceName) {
                NativeBridge.renameDevice(config.deviceName)
                currentName = config.deviceName
                currentConfig = config
            }
            true
        }.getOrElse { error ->
            stop()
            LibrespotNativeStateBus.error(
                error.message ?: context.getString(R.string.spotify_native_start_failed),
            )
            false
        }
    }

    @Synchronized
    override fun stop() {
        startGeneration++
        currentBitrate = null
        currentName = null
        currentConfig = null
        unwatchNetwork()
        if (available && started.compareAndSet(true, false)) {
            runCatching { NativeBridge.stopDevice() }
        }
        runCatching { multicastLock?.release() }
        multicastLock = null
        syncPlaybackWakeLock(false)
        LibrespotNativeStateBus.disconnected()
        LibrespotNativeStateBus.token(null)
    }

    override fun play(): Boolean = nativeTransport("play") { NativeBridge.play() }
    override fun pause(): Boolean = nativeTransport("pause") { NativeBridge.pause() }
    override fun next(): Boolean = nativeTransport("next") { NativeBridge.nextTrack() }
    override fun previous(): Boolean = nativeTransport("previous") { NativeBridge.previousTrack() }

    private inline fun nativeTransport(operation: String, block: () -> Unit): Boolean {
        if (!available || !started.get()) return false
        return runCatching {
            block()
            true
        }.getOrElse { error ->
            Timber.w(error, "Spotify native transport failed: %s", operation)
            LibrespotNativeStateBus.error(error.message ?: "Spotify $operation failed")
            false
        }
    }

    override fun setPcmTapEnabled(enabled: Boolean): Boolean {
        if (!available) return false
        PcmTap.setEnabled(enabled)
        return true
    }

    /**
     * Zeroconf announces the addresses that existed when the receiver started. After a Wi-Fi
     * reconnect with a new lease or a switch to Ethernet the TV would silently vanish from the
     * Connect device list, so a real network move re-announces it (deferred while music plays).
     */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val ipv4 = linkProperties.linkAddresses.mapNotNull { link ->
                    (link.address as? Inet4Address)?.hostAddress
                }
                val key = spotifyNetworkKey(network.networkHandle, ipv4)
                val previous = networkKey
                if (ipv4.isNotEmpty()) networkKey = key
                if (spotifyNetworkChangeNeedsRestart(previous, key, ipv4.isNotEmpty())) {
                    Timber.i("Spotify receiver network changed; re-announcing")
                    restartPending = true
                    scope.launch { if (!state.value.playing) scheduleNetworkRestart() }
                }
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { Timber.w(it, "Spotify network callback unavailable") }
    }

    private fun unwatchNetwork() {
        restartJob?.cancel()
        restartJob = null
        restartPending = false
        networkKey = null
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    /** Debounced so a burst of link updates during a reconnect restarts the receiver once. */
    private fun scheduleNetworkRestart() {
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(NETWORK_RESTART_DEBOUNCE_MS)
            synchronized(this@LibrespotPlaybackEngine) {
                if (!restartPending || state.value.playing) return@synchronized
                val config = currentConfig ?: return@synchronized
                restartPending = false
                val key = networkKey
                stop()
                start(config)
                // The new receiver already announces the current addresses.
                networkKey = key
            }
        }
    }

    private fun acquireDiscoveryLock() {
        if (multicastLock == null) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("SpotifyDiscoveryLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private var wakeLockRenewal: Job? = null

    /**
     * Held while Spotify plays, but always with a timeout: a missed "stopped" state (crashed
     * native player, process kept alive) can no longer keep the CPU awake for ever. A renewal
     * loop re-arms the timeout while playback continues.
     */
    private fun syncPlaybackWakeLock(playing: Boolean) {
        if (playing) {
            if (wakeLock?.isHeld != true) {
                val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = power?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Ambient TV:SpotifyPlayback",
                )?.apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            }
            if (wakeLockRenewal?.isActive != true) {
                wakeLockRenewal = scope.launch {
                    while (true) {
                        delay(WAKE_LOCK_RENEW_MS)
                        synchronized(this@LibrespotPlaybackEngine) {
                            if (state.value.playing && started.get()) runCatching { wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS) }
                        }
                    }
                }
            }
        } else {
            wakeLockRenewal?.cancel()
            wakeLockRenewal = null
            runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
            wakeLock = null
        }
    }

    private companion object {
        /** Rusty 2.0.0 hard-coded a 50 % initial Connect volume; keep that behavior. */
        const val STARTUP_VOLUME_PERCENT = 50
        const val NETWORK_RESTART_DEBOUNCE_MS = 3_000L
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        const val WAKE_LOCK_RENEW_MS = 60_000L
    }
}
