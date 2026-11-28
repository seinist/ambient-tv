package com.ambient.tv.ui.moonlight

import android.app.Activity
import android.os.Build
import android.view.Display
import androidx.core.content.ContextCompat
import com.ambient.tv.moonlight.MoonlightStreamProfile
import com.ambient.tv.ui.mobile.isTelevision
import com.ambient.tv.ui.player.DisplayRefreshRateMatcher
import com.ambient.tv.ui.player.SupportedDisplayMode
import com.ambient.tv.ui.player.awaitDisplayMode
import kotlinx.coroutines.delay
import timber.log.Timber

internal data class MoonlightDisplayResult(
    val mode: SupportedDisplayMode? = null, val hdr10: Boolean = false, val unavailable: Boolean = false,
)

/** Own only this Activity's window preference, not the video or Libretro player's window. */
internal class MoonlightDisplaySession(private val activity: Activity) {
    private var previousPreference: Int? = null
    private var requestedMode: SupportedDisplayMode? = null

    fun snapshot(unavailable: Boolean = false): MoonlightDisplayResult {
        val mode = DisplayRefreshRateMatcher.currentMode(activity)
        val hdr10 = runCatching {
            val display = ContextCompat.getDisplayOrDefault(activity)
            val types = if (Build.VERSION.SDK_INT >= 34) display.mode.supportedHdrTypes
                else display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
            Display.HdrCapabilities.HDR_TYPE_HDR10 in types
        }.getOrDefault(false)
        return MoonlightDisplayResult(mode, hdr10, unavailable)
    }

    /** Confirm or roll back BEFORE constructing the decoder/starting the remote stream. */
    suspend fun prepare(profile: MoonlightStreamProfile, allowed: Set<String>, settleMs: Int): MoonlightDisplayResult {
        val baseline = snapshot()
        if (!moonlightDisplayRequested(profile)) return baseline
        val original = baseline.mode ?: return baseline.copy(unavailable = true)
        // Preserve HDR-capable output for Auto when available; explicit HDR must not select an SDR mode.
        val hdr = if (profile.hdrMode == "on" || (profile.hdrMode == "auto" && baseline.hdr10))
            Display.HdrCapabilities.HDR_TYPE_HDR10 else null
        val target = selectMoonlightDisplayMode(profile, DisplayRefreshRateMatcher.supportedModes(activity),
            original, handheld = !isTelevision(activity), allowed = allowed, requiredHdrType = hdr)
            ?: return baseline.copy(unavailable = true)
        if (target.key == original.key) return baseline
        previousPreference = activity.window.attributes.preferredDisplayModeId
        requestedMode = target
        val sent = runCatching {
            DisplayRefreshRateMatcher.requestDisplayMode(activity, target, settleMs.toLong())
        }.onFailure { Timber.w(it, "Moonlight display request failed") }.isSuccess
        if (sent && awaitDisplayMode(activity, target.key, 3_500L)) {
            if (settleMs > 0) delay(settleMs.toLong())
            val actual = snapshot()
            if (actual.mode?.key == target.key) return actual
        }
        // No repeated HDMI requests on a rejected mode; continue with confirmed physical output.
        restore()
        awaitDisplayMode(activity, original.key, 3_500L)
        return snapshot(unavailable = true)
    }

    fun restore() {
        val target = requestedMode ?: return
        requestedMode = null
        val previous = previousPreference
        previousPreference = null
        if (activity.window.attributes.preferredDisplayModeId == target.modeId) {
            runCatching { DisplayRefreshRateMatcher.restoreMode(activity, previous) }
                .onFailure { Timber.w(it, "Moonlight display restore failed") }
        }
    }
}
