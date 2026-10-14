package com.ambient.tv.notifications

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.player.DisplayRefreshRateMatcher
import com.ambient.tv.ui.player.SupportedDisplayMode

/** Compare physical formats, not mode IDs (which can also change for unrelated reasons). */
internal class DisplayChangeTracker(initial: SupportedDisplayMode?) {
    private var previous = initial

    fun update(actual: SupportedDisplayMode?, enabled: Boolean): SupportedDisplayMode? {
        actual ?: return null
        val old = previous
        previous = actual
        return actual.takeIf { enabled && old != null && old.key != actual.key }
    }
}

/** Activity scope includes restoration after any renderer leaves its playback screen. */
@Composable
fun DisplayChangeNotifications(activity: ComponentActivity, preferences: AppPreferences) {
    DisposableEffect(activity, preferences) {
        val manager = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val handler = Handler(Looper.getMainLooper())
        fun current(): SupportedDisplayMode? {
            val mode = ContextCompat.getDisplayOrDefault(activity).mode
            return SupportedDisplayMode(
                DisplayRefreshRateMatcher.modeKey(mode), mode.modeId,
                mode.physicalWidth, mode.physicalHeight, mode.refreshRate,
            )
        }
        val tracker = DisplayChangeTracker(current())
        val publish = Runnable {
            val switchingAvailable = DisplayRefreshRateMatcher.supportedModes(activity).size > 1
            if (!switchingAvailable) AppNotifications.dismiss("display-mode-change")
            val enabled = switchingAvailable && preferences.displayChangeNotificationsEnabled &&
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            tracker.update(current(), enabled)?.let { mode ->
                // Replace a preceding switch notice; never queue resolution and Hz separately.
                AppNotifications.dismiss("display-mode-change")
                AppNotifications.post(AppNotice(
                    key = "display-mode-change",
                    message = R.string.notification_display_changed,
                    args = listOf(mode.label),
                    kind = NoticeKind.PLAYBACK,
                ))
            }
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != ContextCompat.getDisplayOrDefault(activity).displayId) return
                handler.removeCallbacks(publish)
                // HDMI may publish resolution and cadence in successive callbacks.
                handler.postDelayed(publish, 500)
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) {
                handler.removeCallbacks(publish)
                tracker.update(current(), enabled = false)
            }
        }
        activity.lifecycle.addObserver(lifecycleObserver)
        manager?.registerDisplayListener(listener, handler)
        onDispose {
            handler.removeCallbacks(publish)
            manager?.unregisterDisplayListener(listener)
            activity.lifecycle.removeObserver(lifecycleObserver)
        }
    }
}
