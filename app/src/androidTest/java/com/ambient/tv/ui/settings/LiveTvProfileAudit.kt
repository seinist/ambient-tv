package com.ambient.tv.ui.settings

import android.app.Instrumentation
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import com.ambient.tv.R
import com.ambient.tv.data.local.*
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.player.*
import com.ambient.tv.ui.theme.AmbientTvTheme

internal class LiveTvProfileAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    private fun click(text: String) {
        repeat(24) { attempt ->
            val all = nodes(instrumentation.uiAutomation.rootInActiveWindow)
            val found = all.firstOrNull { it.isClickable && it.text?.toString()?.substringBefore('\n') == text }
                ?: all.firstOrNull { it.text?.toString() == text }
            if (found != null) {
                var target: AccessibilityNodeInfo? = found
                while (target != null && !target.isClickable) target = target.parent
                if (text == instrumentation.targetContext.getString(R.string.settings_save) &&
                    target?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true) {
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                    Thread.sleep(500)
                    log("D-pad activated Save")
                    return
                }
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    Thread.sleep(500)
                    return
                }
            }
            all.firstOrNull { it.isScrollable }?.performAction(
                if (attempt < 12) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            Thread.sleep(250)
        }
        error("Missing clickable $text: ${nodes(instrumentation.uiAutomation.rootInActiveWindow).mapNotNull { it.text }}")
    }

    fun run() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".renderaudit"))
        val prefs = videoImagePreferences(context)
        val movie = ResolutionVideoProfile("vlc", audio = AudioSettings().update(AudioOptions(delayMs = 75)))
        val otherLive = ResolutionVideoProfile("media3")
        prefs.resolutionVideoProfiles = mapOf("720" to movie, "live:1080" to otherLive)
        prefs.tvheadendPlaybackBackendKey = "media3"
        prefs.mpvVideoOutputModeKey = "auto"
        prefs.audioSettings = AudioSettings()
        val originalAudio = prefs.audioSettings
        val snapshot = savedPlaybackOverview(prefs)
        val activity = instrumentation.startActivitySync(Intent(context, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent { AmbientTvTheme {
                    LiveTvProfileDialog(snapshot, save = { resolution, profile ->
                        prefs.resolutionVideoProfiles = withLiveResolutionProfile(prefs.resolutionVideoProfiles, resolution, profile)
                    }, onDismiss = {})
                } }
            }
            Thread.sleep(2_000)
            fun label(id: Int) = context.getString(id)
            click(label(R.string.settings_playback_backend)); click("MPV")
            click(label(R.string.player_backend_auto))
            click(label(R.string.renderer_output_direct))
            click(label(R.string.live_profiles_section)); click(label(R.string.player_audio))
            click(label(R.string.audio_output)); click("PCM")
            Thread.sleep(1_000) // saved on change (debounced); no Save button anymore
            val live = checkNotNull(prefs.resolutionVideoProfiles["live:720"])
            check(live.rendererKey == "mpv" && live.mpvOutput == "direct")
            check(live.audio!!.profiles.values.any { it.output == "pcm" })
            check(prefs.resolutionVideoProfiles["720"] == movie)
            check(prefs.resolutionVideoProfiles["live:1080"] == otherLive)
            check(prefs.audioSettings == originalAudio && prefs.tvheadendPlaybackBackendKey == "media3")
            val metadata = PlaybackMetadata(route = "audit/channel", title = "Channel", source = "tvheadend", mediaId = "1", isLive = true,
                sourceWidth = 1280, sourceHeight = 720)
            check(ResolutionVideoProfileStore.load(context, metadata) == live)
            check(ResolutionVideoProfileStore.load(context, metadata.copy(isLive = false)) == movie)
            log("PASS Live TV 720 audio/video editor saved MPV Direct and PCM; movie/1080/global settings unchanged; runtime lookup isolated")
            click(label(R.string.live_profiles_reset))
            check("live:720" !in prefs.resolutionVideoProfiles)
            check(prefs.resolutionVideoProfiles["720"] == movie)
            log("PASS reset removes only live:720")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
