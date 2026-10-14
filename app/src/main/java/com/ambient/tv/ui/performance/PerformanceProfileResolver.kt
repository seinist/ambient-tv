package com.ambient.tv.ui.performance

import android.content.Context
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.player.backend.SvpNative
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central resolver for [PerformanceProfile]. Compose UI reads [LocalPerformanceProfile];
 * data-layer helpers (URL builders, Coil) use this singleton so HQ/backdrop budgets stay in sync
 * with the saved modes — and with an in-memory Settings draft preview.
 *
 * Interface and playback previews are independent. An absent preview falls through to the
 * stored key for that side, which itself falls back to the legacy performance mode.
 */
@Singleton
class PerformanceProfileResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
) {
    @Volatile
    private var previewUiModeKey: String? = null

    @Volatile
    private var previewPlaybackModeKey: String? = null

    @Volatile
    private var lowEndCached: Boolean? = null

    @Synchronized
    fun setPreviewModeKeys(uiKey: String?, playbackKey: String?) {
        previewUiModeKey = uiKey?.takeIf { it in PerformanceMode.KEYS }
        previewPlaybackModeKey = playbackKey?.takeIf { it in PerformanceMode.KEYS }
        SvpNative.updateGate(currentPlaybackMode() == PerformanceMode.MAXIMUM)
    }

    fun currentUiMode(): PerformanceMode =
        PerformanceMode.fromKey(previewUiModeKey ?: preferences.effectiveUiPerformanceModeKey())

    fun currentPlaybackMode(): PerformanceMode =
        PerformanceMode.fromKey(previewPlaybackModeKey ?: preferences.effectivePlaybackPerformanceModeKey())

    @Synchronized
    fun current(): PerformanceProfile =
        PerformanceProfile.resolve(currentUiMode(), isLowEndDevice(), currentPlaybackMode()).also {
            SvpNative.updateGate(it.allowSvpInterpolation)
        }

    fun effectiveHighQualityAssets(): Boolean =
        preferences.highQualityAssetsEnabled && current().allowHighQualityAssets

    fun isLowEndDevice(): Boolean {
        lowEndCached?.let { return it }
        return detectLowEndDevice(context).also { lowEndCached = it }
    }
}
