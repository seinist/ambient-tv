package com.ambient.tv.ui.player

import android.content.Context
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.performance.PerformanceProfile
import com.ambient.tv.ui.performance.PerformanceProfileResolver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface VideoImagePreferencesEntryPoint {
    fun preferences(): AppPreferences
    fun performanceProfileResolver(): PerformanceProfileResolver
}

internal fun videoImagePreferences(context: Context): AppPreferences =
    EntryPointAccessors.fromApplication(context.applicationContext, VideoImagePreferencesEntryPoint::class.java).preferences()

internal fun performanceProfile(context: Context): PerformanceProfile =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        VideoImagePreferencesEntryPoint::class.java,
    ).performanceProfileResolver().current()

internal fun reducedVideoEffects(context: Context): Boolean =
    performanceProfile(context).gateExpensiveVideoVisuals

/**
 * MPV runs untouched image defaults cheap: under Sparsam, and on a low-end device outside Maximum,
 * unless the user chose scaler/deband values deliberately ([AppPreferences.mpvImageExplicitChoice]).
 */
internal fun mpvCheapDefaults(context: Context): Boolean =
    mpvCheapDefaultsApply(performanceProfile(context).mpvCheapDefaults, videoImagePreferences(context).mpvImageExplicitChoice)

internal fun mpvCheapDefaultsApply(profileCheapDefaults: Boolean, explicitChoice: Boolean): Boolean =
    profileCheapDefaults && !explicitChoice

/** Bumped when [markMpvImageExplicitChoice] runs so menus drop their "runs cheap" rows at once. */
internal object MpvImageChoiceVersion {
    val value = androidx.compose.runtime.mutableIntStateOf(0)
}

/** A scaler/sigmoid/deband value picked in an image menu: it applies as chosen from now on. */
internal fun markMpvImageExplicitChoice(preferences: AppPreferences) {
    if (!preferences.mpvImageExplicitChoice) {
        preferences.mpvImageExplicitChoice = true
        MpvImageChoiceVersion.value.intValue++
    }
}

/** Compose-side [mpvCheapDefaults]. */
@androidx.compose.runtime.Composable
internal fun mpvCheapDefaultsActive(): Boolean {
    MpvImageChoiceVersion.value.intValue
    val context = androidx.compose.ui.platform.LocalContext.current
    val profile = com.ambient.tv.ui.performance.LocalPerformanceProfile.current
    return mpvCheapDefaultsApply(profile.mpvCheapDefaults,
        androidx.compose.runtime.remember(context) { videoImagePreferences(context) }.mpvImageExplicitChoice)
}
