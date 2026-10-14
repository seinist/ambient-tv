package com.ambient.tv.ui.player.backend

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing

/** Device-scoped Android effect, attached to VLC's own AudioTrack session, never session zero. */
internal class VlcNightProcessing(context: Context) {
    val sessionId = runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
    }.getOrDefault(0)
    val available: Boolean by lazy { sessionId > 0 && deviceSupported }

    companion object {
        /**
         * Whether the device offers DynamicsProcessing at all. Queried once per process and
         * without generating an audio session, so settings can ask it cheaply.
         */
        val deviceSupported: Boolean by lazy {
            runCatching {
                AudioEffect.queryEffects()?.any { it.type == AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING } == true
            }.getOrDefault(false)
        }
    }
    private var effect: DynamicsProcessing? = null

    fun apply(enabled: Boolean): Boolean {
        if (!enabled) { release(); return true }
        if (!available) return false
        return runCatching {
            if (effect == null) {
                // Query the actual AudioTrack effect channel count after VLC starts output.
                val probe = DynamicsProcessing(sessionId)
                val channels = try { probe.channelCount } finally { probe.release() }
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, channels,
                    false, 0, true, 1, false, 0, true,
                ).build()
                effect = DynamicsProcessing(0, sessionId, config)
            }
            val current = checkNotNull(effect)
            current.setMbcBandAllChannelsTo(0, DynamicsProcessing.MbcBand(
                true, 20000f, 10f, 250f, 4f, -18f, 6f, -90f, 1f, 0f, 0f,
            ))
            current.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(
                true, true, 0, 1f, 60f, 20f, -1f, 0f,
            ))
            check(current.setEnabled(true) == AudioEffect.SUCCESS && current.hasControl())
            true
        }.getOrElse { release(); false }
    }

    fun release() { runCatching { effect?.release() }; effect = null }
}
