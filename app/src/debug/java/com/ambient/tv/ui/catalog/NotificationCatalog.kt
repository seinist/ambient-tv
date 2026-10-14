package com.ambient.tv.ui.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.notifications.*
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.AppBackground
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.performance.PerformanceMode
import com.ambient.tv.ui.performance.PerformanceProfile
import com.ambient.tv.ui.theme.AmbientTvTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Production host, synthetic events only. No accounts, network access or saved settings. */
@Composable
internal fun NotificationCatalog() {
    var low by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    CompositionLocalProvider(LocalPerformanceProfile provides PerformanceProfile.resolve(if (low) PerformanceMode.LOW else PerformanceMode.BALANCED, false)) {
        AmbientTvTheme {
            Box(Modifier.fillMaxSize()) {
                AppBackground(Modifier.fillMaxSize())
                Column(Modifier.padding(40.dp).width(340.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Notifications · Debug", style = MaterialTheme.typography.headlineMedium)
                    TvActionButton("VPN → Wake → Ready", {
                        scope.launch {
                            val base = AppNotice("demo:connection", R.string.notice_vpn_connecting, listOf("plex", "jellyfin"), "Medienserver", NoticePhase.WORKING, NoticeKind.VPN)
                            AppNotifications.post(base)
                            delay(2_000)
                            AppNotifications.post(base.copy(message = R.string.notice_waking, kind = NoticeKind.WAKE))
                            delay(3_000)
                            AppNotifications.post(base.copy(message = R.string.notice_connected, phase = NoticePhase.SUCCESS, kind = NoticeKind.CONNECTION))
                        }
                    }, Modifier.focusRequester(focus))
                    TvActionButton("Login success", { notifyLogin("youtube", NoticePhase.SUCCESS) })
                    TvActionButton("Server error", { notifyResult("demo:error", "files", "NAS Filme", success = false, kind = NoticeKind.WAKE, failureMessage = R.string.notice_wake_failed) })
                    TvActionButton("Performance: ${if (low) "Economy" else "Balanced"}", { low = !low })
                    TvActionButton("Open dialog", { dialog = true })
                }
                AppNotificationHost()
                if (dialog) AppDialog(onDismiss = { dialog = false }, title = "Notifications in a dialog", footer = {
                    TvActionButton("Confirm login", { notifyLogin("jellyfin", NoticePhase.SUCCESS, "Medienserver", "Max") })
                    TvActionButton("Back", { dialog = false })
                }) { Text("The status card does not take remote focus.") }
            }
            LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
        }
    }
}
