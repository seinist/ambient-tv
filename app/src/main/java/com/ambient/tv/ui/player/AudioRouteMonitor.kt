package com.ambient.tv.ui.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.ambient.tv.ui.player.backend.MpvBackendAvailability

/**
 * Owned by the playback engine, including background playback with no audio panel.
 *
 * Device callbacks are debounced: an HDMI mode or refresh-rate switch removes and re-adds the
 * HDMI audio device within a second. Probing once after the burst settles means a renderer only
 * restarts when the route really ended up different, not twice for "gone" and "back".
 */
internal class AudioRouteMonitor(context: Context, private val changed: (AudioRouteCapabilities) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private val debouncer = RouteChangeDebouncer(ROUTE_CHANGE_DEBOUNCE_MS,
        schedule = { task, delay -> handler.postDelayed(task, delay) },
        cancel = { task -> handler.removeCallbacks(task) },
        fire = ::refresh)
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = debouncer.trigger()
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = debouncer.trigger()
    }
    init { manager?.registerAudioDeviceCallback(callback, handler) }
    private fun refresh() {
        if (!closed) changed(AudioPassthroughPolicy.probe(context).copy(mpvAvailable = MpvBackendAvailability.isAvailable))
    }
    override fun close() { closed = true; debouncer.close(); manager?.unregisterAudioDeviceCallback(callback) }
}

/** Quiet time after the last audio device callback before the route is probed. */
internal const val ROUTE_CHANGE_DEBOUNCE_MS = 1_500L

/** Runs [fire] once, [delayMs] after the last [trigger]; earlier triggers are replaced. */
internal class RouteChangeDebouncer(
    private val delayMs: Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val fire: () -> Unit,
) {
    private var pending = false
    private val task = Runnable { pending = false; fire() }
    fun trigger() {
        if (pending) cancel(task)
        pending = true
        schedule(task, delayMs)
    }
    fun close() {
        if (pending) cancel(task)
        pending = false
    }
}
