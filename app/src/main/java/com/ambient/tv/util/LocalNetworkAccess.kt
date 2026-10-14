package com.ambient.tv.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.content.ContextCompat
import com.ambient.tv.data.local.AppPreferences

internal fun requiresLocalNetworkPermission(deviceSdk: Int, targetSdk: Int): Boolean =
    deviceSdk >= 37 && targetSdk >= 37

internal fun shouldIntroduceLocalNetworkAccess(required: Boolean, granted: Boolean, configured: Boolean, introduced: Boolean): Boolean =
    required && !granted && configured && !introduced

object LocalNetworkAccess {
    @ChecksSdkIntAtLeast(api = 37)
    fun isRequired(context: Context): Boolean = requiresLocalNetworkPermission(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion)

    fun isGranted(context: Context): Boolean = !isRequired(context) ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    fun hasConfiguredService(preferences: AppPreferences, dlnaConfigured: Boolean = false): Boolean =
        dlnaConfigured ||
            (preferences.plexEnabled && preferences.isConfigured) ||
            (preferences.jellyfinEnabled && preferences.isJellyfinConfigured) ||
            (preferences.embyEnabled && preferences.isEmbyConfigured) ||
            (preferences.tvheadendEnabled && preferences.isTvheadendConfigured) ||
            preferences.spotifyEnabled ||
            (preferences.filesEnabled && preferences.fileSources.any { it.network })
}
