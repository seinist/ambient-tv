package com.ambient.tv.ui.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.ambient.tv.ui.player.backend.MpvBackendAvailability

/** Owned by the playback engine, including background playback with no audio panel. */
internal class AudioRouteMonitor(context: Context, private val changed: (AudioRouteCapabilities) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private var closed = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = refresh()
    }
    init { manager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }
    private fun refresh() {
        if (!closed) changed(AudioPassthroughPolicy.probe(context).copy(mpvAvailable = MpvBackendAvailability.isAvailable))
    }
    override fun close() { closed = true; manager?.unregisterAudioDeviceCallback(callback) }
}
