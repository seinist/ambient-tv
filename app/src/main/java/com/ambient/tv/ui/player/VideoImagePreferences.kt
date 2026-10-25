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

/** MPV runs untouched image defaults cheap: under Sparsam, and in every mode on a low-end device. */
internal fun mpvCheapDefaults(context: Context): Boolean = performanceProfile(context).mpvCheapDefaults
