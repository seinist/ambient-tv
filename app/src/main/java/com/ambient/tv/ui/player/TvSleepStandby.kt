package com.ambient.tv.ui.player

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.ambient.tv.ui.mobile.isTelevision
import timber.log.Timber

/** Standby is strictly TV-only. Never lock phones/tablets through the playback sleep timer. */
object TvSleepStandby {
    private const val RELEASE_SCREEN_DELAY_MS = 500L

    internal fun supportedDevice(tv: Boolean, adminFeature: Boolean): Boolean = tv && adminFeature

    fun isSupported(context: Context): Boolean =
        supportedDevice(
            tv = isTelevision(context),
            adminFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN),
        )

    fun component(context: Context): ComponentName =
        ComponentName(context, SleepTimerDeviceAdminReceiver::class.java)

    fun isEnabled(context: Context): Boolean {
        if (!isSupported(context)) return false
        return runCatching {
            context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(component(context)) == true
        }.getOrDefault(false)
    }

    /** Optional immediate screen-off. Without permission the released keep-screen-on flag is the fallback. */
    fun request(context: Context) {
        // Guard again here, even if the caller has already checked. Phone standby must be impossible.
        if (!isTelevision(context) || !isEnabled(context)) return
        val app = context.applicationContext
        // Compose needs a frame to drop the TV screen-on flag; otherwise the old flag can wake it.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!SleepSession.standbyRequested || !isTelevision(app) || !isEnabled(app)) return@postDelayed
            runCatching {
                app.getSystemService(DevicePolicyManager::class.java)?.lockNow()
            }.onFailure { Timber.w(it, "Sleep timer could not enter TV standby") }
        }, RELEASE_SCREEN_DELAY_MS)
    }
}
