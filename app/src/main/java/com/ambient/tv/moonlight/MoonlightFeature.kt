package com.ambient.tv.moonlight

import com.ambient.tv.BuildConfig
import com.ambient.tv.data.local.AppPreferences

/** Experimental setup may exist without a compiled native streaming backend. */
object MoonlightFeature {
    val nativeAvailable: Boolean get() = BuildConfig.MOONLIGHT_NATIVE
    val setupAvailable: Boolean get() = BuildConfig.MOONLIGHT_EXPERIMENTAL
    fun configured(preferences: AppPreferences): Boolean = nativeAvailable && preferences.hasMoonlightHosts
}
