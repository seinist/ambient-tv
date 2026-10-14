package com.ambient.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.NoticePhase
import com.ambient.tv.notifications.noticeMessage
import com.ambient.tv.notifications.noticeTitle
import com.ambient.tv.ui.common.TvActionButton
import java.text.DateFormat
import java.util.Date

internal fun LazyListScope.notificationHistoryItems(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    item(key = "notification-history") {
        val snapshot by AppNotifications.store.state.collectAsState()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsGroup(
                title = stringResource(R.string.notice_display_duration),
                subtitle = stringResource(R.string.notice_display_duration_hint),
            ) {
                SettingsSelect(options = listOf("off", "short", "normal", "long").map { key ->
                    SettingsSelectOption(
                        text = notificationDurationLabel(key),
                        selected = uiState.notificationDisplayDurationKey == key,
                        onClick = { viewModel.onNotificationDisplayDurationChanged(key) },
                    )
                })
            }
            Text(stringResource(R.string.notice_history_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TvActionButton(stringResource(R.string.notice_history_clear), onClick = AppNotifications.store::clearHistory)
            if (snapshot.history.isEmpty()) Text(stringResource(R.string.notice_history_empty), color = MaterialTheme.colorScheme.onSurface)
            snapshot.history.forEach { entry ->
                key(entry.id) {
                    var focused by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth()
                        .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .2f) else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .onFocusChanged { focused = it.isFocused }.focusable().padding(16.dp)) {
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.wallTime)) + " · " + noticeTitle(entry.notice), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(noticeMessage(entry.notice), style = MaterialTheme.typography.bodyMedium,
                            color = if (entry.notice.phase == NoticePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun notificationDurationLabel(key: String): String = stringResource(when (key) {
    "off" -> R.string.notice_display_duration_off
    "short" -> R.string.notice_display_duration_short
    "long" -> R.string.notice_display_duration_long
    else -> R.string.notice_display_duration_normal
})
