package com.ambient.tv.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.ambient.tv.ui.player.backend.MpvBackendAvailability

/**
 * Owned by the playback engine, including background playback with no audio panel.
 *
 * Device callbacks are debounced: an HDMI mode or refresh-rate switch removes and re-adds the
 * HDMI audio device within a second. Probing once after the burst settles means a renderer only
 * restarts when the route really ended up different, not twice for "gone" and "back".
 *
 * HDMI re-plugs (another TV or AVR, a new EDID after a mode switch) often keep the same HDMI
 * device, so no device callback arrives. ACTION_HDMI_AUDIO_PLUG covers that case.
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
    private val hdmiPlug: BroadcastReceiver?
    init {
        manager?.registerAudioDeviceCallback(callback, handler)
        hdmiPlug = registerHdmiAudioPlug(this.context) {
            AudioPassthroughPolicy.invalidateCache()
            debouncer.trigger()
        }
    }
    private fun refresh() {
        if (!closed) changed(AudioPassthroughPolicy.probe(context).copy(mpvAvailable = MpvBackendAvailability.isAvailable))
    }
    override fun close() {
        closed = true
        debouncer.close()
        manager?.unregisterAudioDeviceCallback(callback)
        hdmiPlug?.let { runCatching { context.unregisterReceiver(it) } }
    }
}

/**
 * Calls [changed] on the main thread for every HDMI audio plug or unplug after registration.
 * The initial sticky broadcast only reports the current state and is ignored.
 */
internal fun registerHdmiAudioPlug(context: Context, changed: () -> Unit): BroadcastReceiver? {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (hdmiPlugIsChange(intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1), isInitialStickyBroadcast)) changed()
        }
    }
    // A protected system broadcast: delivered to non-exported receivers as well.
    return runCatching {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver
    }.getOrNull()
}

internal fun hdmiPlugIsChange(state: Int, initialSticky: Boolean): Boolean = state >= 0 && !initialSticky

/**
 * Last probed route, valid for [maxAgeMs]. [invalidate] also discards a probe that was already
 * running: its result could describe the output from before the change.
 */
internal class RouteCapabilitiesCache(private val maxAgeMs: Long) {
    private data class Entry(val at: Long, val route: AudioRouteCapabilities)
    @Volatile private var entry: Entry? = null
    @Volatile var generation = 0L
        private set

    fun get(now: Long): AudioRouteCapabilities? = entry?.takeIf { now - it.at in 0 until maxAgeMs }?.route

    @Synchronized fun store(startedAt: Long, startedGeneration: Long, route: AudioRouteCapabilities) {
        if (startedGeneration == generation) entry = Entry(startedAt, route)
    }

    @Synchronized fun invalidate() {
        generation++
        entry = null
    }
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
