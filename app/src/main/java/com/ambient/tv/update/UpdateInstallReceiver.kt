package com.ambient.tv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
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
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                runCatching { context.startActivity(requireNotNull(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { updater.reportInstallError() }
            }
            // Installed: this process is replaced. Declined: the verified APK stays for a retry.
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE ->
                if (profiled) updater.retryWithoutProfile() else updater.reportInstallError()
            else -> updater.reportInstallError()
        }
    }

    companion object {
        const val EXTRA_PROFILED = "com.ambient.tv.update.PROFILED"
    }
}
