package com.ambient.tv.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.ambient.tv.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends a sharesheet link to a paired TV without opening Ambient TV on the phone.
 *
 * The foreground connected-device service is started by the user-initiated, no-display
 * ShareToTvActivity. It keeps discovery/TLS alive until the TV acknowledges the start, or
 * until the bounded timeout expires. Multiple rapid shares are handled sequentially.
 */
@AndroidEntryPoint
class CompanionDirectShareService : Service() {
    @Inject lateinit var manager: CompanionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = Channel<Pair<Intent, Int>>(Channel.UNLIMITED)

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        )
        scope.launch {
            for ((share, startId) in requests) {
                val completion = runCatching { manager.startHeadlessShare(share) }.getOrNull()
                try {
                    if (completion != null) {
                        val confirmed = withTimeoutOrNull(50_000) { completion.await() }
                        if (confirmed == null) manager.notice(R.string.companion_connect_failed, error = true)
                        // The companion manager already reports an explicit TV/connection error.
                    }
                } finally {
                    if (completion != null) manager.finishHeadlessShare(completion)
                    // Returns false when newer start IDs are waiting in the channel.
                    stopSelfResult(startId)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notice = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_companion_share)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.companion_connecting))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notice)
        }
        if (intent == null || !requests.trySend(Intent(intent) to startId).isSuccess) {
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        requests.close()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "companion_direct_share"
        const val NOTIFICATION_ID = 4824
    }
}
