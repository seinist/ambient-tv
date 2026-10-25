package com.ambient.tv.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Kodi (Android) reopens its audio engine after a refresh rate change once HDMI audio is plugged
 * again (ACTION_HDMI_AUDIO_PLUG); on HDMI source devices it waits at least 2 s.
 */
internal object HdmiAudioReplug {
    /** Kodi's minimum wait after a mode switch on HDMI source (playback) devices. */
    const val SOURCE_DEVICE_MIN_WAIT_MS = 2_000L
    /** Bound for the re-plug after an unplug was seen. */
    const val REPLUG_TIMEOUT_MS = 5_000L
}

/**
 * Plug states after [start]; the initial sticky broadcast is not a change. A re-plug is a
 * plugged state after an unplug, or a fresh plugged broadcast (some sinks skip the unplug).
 */
internal class HdmiAudioPlugTracker {
    private val state = MutableStateFlow(State())
    private data class State(val unplugged: Boolean = false, val replugged: Boolean = false)

    fun onPlugState(plugged: Boolean, initialSticky: Boolean) {
        if (initialSticky) return
        state.value = if (plugged) state.value.copy(replugged = true) else State(unplugged = true)
    }

    val unpluggedSeen: Boolean get() = state.value.unplugged
    val repluggedSeen: Boolean get() = state.value.replugged

    /** True when HDMI audio came back; false at once when it never went away, or after [timeoutMs]. */
    suspend fun awaitReplug(timeoutMs: Long): Boolean {
        val now = state.value
        if (now.replugged) return true
        if (!now.unplugged) return false
        return withTimeoutOrNull(timeoutMs) { state.first { it.replugged } } != null
    }
}

/** Receiver-backed [HdmiAudioPlugTracker]; [start] before the mode request, [stop] afterwards. */
internal interface HdmiAudioWatch {
    /** Minimum time from the mode request to the audio reopen (2 s on HDMI source devices). */
    val minimumWaitMs: Long
    fun start()
    suspend fun awaitReplug(timeoutMs: Long): Boolean
    fun stop()
}

internal class AndroidHdmiAudioWatch(private val context: Context) : HdmiAudioWatch {
    private val tracker = HdmiAudioPlugTracker()
    private var receiver: BroadcastReceiver? = null
    override val minimumWaitMs: Long = if (isHdmiSourceDevice()) HdmiAudioReplug.SOURCE_DEVICE_MIN_WAIT_MS else 0L

    override fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val plugged = intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1)
                if (plugged < 0) return
                Timber.i("HdmiAudioPlug state=%d sticky=%s", plugged, isInitialStickyBroadcast)
                tracker.onPlugState(plugged == 1, isInitialStickyBroadcast)
            }
        }
        // A protected system broadcast: delivered to non-exported receivers as well.
        runCatching { ContextCompat.registerReceiver(context, r, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG), ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onSuccess { receiver = r }
            .onFailure { Timber.w(it, "HdmiAudioPlug receiver not registered") }
    }

    override suspend fun awaitReplug(timeoutMs: Long) = tracker.awaitReplug(timeoutMs)

    override fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private companion object {
        /** HDMI-CEC device type 4 = playback device (set-top box, streaming stick). */
        fun isHdmiSourceDevice(): Boolean = runCatching {
            val value = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java).invoke(null, "ro.hdmi.device_type") as? String
            hdmiDeviceTypeIsSource(value)
        }.getOrDefault(false)
    }
}

/** `ro.hdmi.device_type` is a comma list of CEC types; 4 is a playback (source) device. */
internal fun hdmiDeviceTypeIsSource(value: String?): Boolean =
    value?.split(',')?.any { it.trim() == "4" } == true

/**
 * After a mode request at [requestedAtMs]: honour the minimum wait, then wait for the HDMI audio
 * re-plug when an unplug was seen. True means the audio output should be reopened.
 */
internal suspend fun awaitHdmiAudioAfterSwitch(
    watch: HdmiAudioWatch,
    requestedAtMs: Long,
    nowMs: () -> Long = SystemClock::elapsedRealtime,
): Boolean {
    val remaining = watch.minimumWaitMs - (nowMs() - requestedAtMs)
    if (remaining > 0) delay(remaining)
    return watch.awaitReplug(HdmiAudioReplug.REPLUG_TIMEOUT_MS)
}
