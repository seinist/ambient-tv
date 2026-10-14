@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player

import android.app.Instrumentation
import androidx.media3.exoplayer.ExoPlayer
import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.data.local.ResolutionVideoProfile

/** Exercises the real preference store and capture path without a network stream. */
internal class ChannelPlaybackProfileAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    fun run() {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { exercise() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
    private fun exercise() {
        val context = instrumentation.targetContext
        val prefs = videoImagePreferences(context)
        val oldTitles = prefs.titleVideoSettings
        val oldProfiles = prefs.resolutionVideoProfiles
        val originalAudio = prefs.audioSettings
        val metadata = PlaybackMetadata(route = "audit/channel", title = "Channel", source = "tvheadend",
            mediaId = "audit-channel", isLive = true, liveTvSourceId = "audit-server", sourceWidth = 1920, sourceHeight = 1080)
        val key = TitleVideoSettingsStore.key(metadata)!!
        val player = ExoPlayer.Builder(context).build()
        val handle = Media3PlaybackHandle(player)
        try {
            prefs.titleVideoSettings = oldTitles - key
            prefs.resolutionVideoProfiles = oldProfiles + ("live:width:1920" to ResolutionVideoProfile("mpv"))
            val audio = AudioSession(prefs, "media3", false)
            Media3AudioProcessing.attach(player, audio, context)
            TitleVideoSettingsStore.attachAudio(audio, metadata, null)
            audio.update(audio.options.copy(night = !audio.options.night, delayMs = 175))
            val picture = Media3ImageOptions(enabled = true, brightness = 7)
            Media3ImageProcessing.get(context, player).updateOptions(picture)
            TitleVideoSettingsStore.rememberRenderer(context, metadata, "auto")
            TitleVideoSettingsStore.capture(context, metadata, handle, null)
            val saved = requireNotNull(TitleVideoSettingsStore.load(context, metadata))
            check(saved.rendererKey == "auto")
            check(saved.audioDelayMs == 175)
            check(saved.audio?.currentForRoute(audio.route.profile)?.night == audio.options.night)
            check(saved.media3 == picture)
            check(TitleVideoSettingsStore.load(context, metadata.copy(liveTvSourceId = "other-server")) == null)
            check(TitleVideoSettingsStore.load(context, metadata.copy(mediaId = "other-channel")) == null)
            val restored = AudioSession(prefs, "media3", false)
            restoreSavedPlaybackAudio(restored, saved, ResolutionVideoProfileStore.load(context, metadata))
            TitleVideoSettingsStore.attachAudio(restored, metadata, saved.audioDelayMs)
            check(restored.options.delayMs == 175)
            check(restored.options.night == audio.options.night)
            check(prefs.audioSettings == originalAudio)
            val before = prefs.titleVideoSettings
            TitleVideoSettingsStore.capture(context, metadata, handle, null)
            check(before == prefs.titleVideoSettings) { "Unchanged polling rewrote the channel settings" }
            TitleVideoSettingsStore.withSessionRendererSelection {
                TitleVideoSettingsStore.rememberRenderer(context, metadata, "mpv")
            }
            check(TitleVideoSettingsStore.load(context, metadata)?.rendererKey == "auto")
            TitleVideoSettingsStore.forget(context, metadata)
            check(TitleVideoSettingsStore.load(context, metadata) == null)
            log("PASS channel capture/restore: audio, delay, picture, explicit Auto, server isolation, reset and unchanged polling")
        } finally {
            handle.release()
            prefs.titleVideoSettings = oldTitles
            prefs.resolutionVideoProfiles = oldProfiles
        }
    }
}
