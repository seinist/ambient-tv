package com.ambient.tv.update

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ambient.tv.R
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Result of the update install session started by [AppUpdater.install]. The system asks for the
 * user's confirmation through [PackageInstaller.STATUS_PENDING_USER_ACTION]; a device whose ART
 * rejects the bundled profile gets the same APK once more without it.
 */
@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {
    @Inject lateinit var updater: AppUpdater

    override fun onReceive(context: Context, intent: Intent) {
        val profiled = intent.getBooleanExtra(EXTRA_PROFILED, false)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) { updater.reportInstallError(); return }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (updateConfirmNeedsNotification(Build.VERSION.SDK_INT, appInForeground())) {
                    // Android 14+ blocks activity starts from the background: the user opens the
                    // confirmation from a notification instead.
                    if (!notifyConfirm(context, confirm)) updater.reportInstallError()
                } else {
                    // The installer is our own flow: running playback waits for the answer.
                    com.ambient.tv.ui.player.BackgroundPlaybackGuard.markExternalFlow()
                    runCatching { context.startActivity(confirm) }
                        .onFailure { if (!notifyConfirm(context, confirm)) updater.reportInstallError() }
                }
            }
            // Installed: this process is replaced. Declined: the verified APK stays for a retry.
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                Timber.w("UPDATE install failed status=%d profiled=%s message=%s", status, profiled, message)
                if (updateRetryWithoutProfile(profiled, status, message)) updater.retryWithoutProfile()
                else updater.reportInstallError()
            }
        }
    }

    private fun appInForeground(): Boolean = runCatching {
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance <=
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    private fun notifyConfirm(context: Context, confirm: Intent): Boolean = runCatching {
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.update_channel),
            NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID, confirm,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_confirm_title))
            .setContentText(context.getString(R.string.update_confirm_text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        Timber.i("UPDATE confirmation posted as notification (app in background)")
    }.onFailure { Timber.w(it, "UPDATE confirmation notification failed") }.isSuccess

    companion object {
        const val EXTRA_PROFILED = "com.ambient.tv.update.PROFILED"
        private const val CHANNEL = "app_updates"
        private const val NOTIFICATION_ID = 0x75d1
    }
}

/** Android 14+ (API 34) refuses a background activity start from this receiver: use a notification. */
internal fun updateConfirmNeedsNotification(sdkInt: Int, appInForeground: Boolean): Boolean =
    sdkInt >= 34 && !appInForeground

/**
 * Only a failure that points at the bundled profile ("base.dm" dex metadata) is retried without it;
 * storage, signature, version or conflict failures report the error instead of a pointless second try.
 */
internal fun updateRetryWithoutProfile(profiled: Boolean, status: Int, message: String?): Boolean {
    if (!profiled) return false
    if (status != PackageInstaller.STATUS_FAILURE_INVALID && status != PackageInstaller.STATUS_FAILURE) return false
    val m = message?.lowercase() ?: return false
    return UPDATE_PROFILE_FAILURE_HINTS.any { it in m }
}

private val UPDATE_PROFILE_FAILURE_HINTS = listOf(
    "dex_metadata", "dex metadata", "dexmetadata", "base.dm", "profile",
)
