package com.ambient.tv.companion

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.ambient.tv.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*

internal class CompanionLaunchException(val reason: String) : IllegalStateException(reason)

/** TV-only idle receiver, explicitly enabled locally. It never creates a player at boot. */
@AndroidEntryPoint
class CompanionReceiverService : Service() {
    @Inject lateinit var manager: CompanionManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.companion_receiver_background), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 410, Intent(this, com.ambient.tv.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 411, Intent(this, CompanionReceiverService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_companion_share)
            .setContentTitle(getString(R.string.companion_receiver_background))
            .setContentText(getString(R.string.companion_receiver_ready)).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.companion_disable), stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(410, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(410, notification)
        if (!manager.receiverServiceStarted()) { stopSelf(); return }
        scope.launch {
            while (isActive) {
                delay(2500)
                if (!manager.maintainReceiver()) { stopSelf(); break }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { manager.enableBackgroundReceiver(false); stopSelf(); return START_NOT_STICKY }
        return if (manager.state.value.backgroundReceiver) START_STICKY else START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); manager.receiverServiceStopped(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "companion_receiver"
        private const val STOP = "com.ambient.tv.companion.STOP_RECEIVER"
    }
}

class CompanionReceiverBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val prefs = context.getSharedPreferences("companion_devices_v1", Context.MODE_PRIVATE)
        if (!com.ambient.tv.ui.mobile.isTelevision(context) || !prefs.getBoolean("enabled", false) || !prefs.getBoolean("background_receiver", false)) return
        if (Build.VERSION.SDK_INT >= 29 && !android.provider.Settings.canDrawOverlays(context)) return
        if (!com.ambient.tv.util.LocalNetworkAccess.isGranted(context)) return
        runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, CompanionReceiverService::class.java)) }
    }
}
