package com.ambient.tv.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Update
import androidx.compose.ui.graphics.vector.ImageVector
import com.ambient.tv.R

/** One Monogram (Telegram) menu entry; shared by the TV sidebar and the phone menu. */
internal data class TelegramMenuEntry(val key: String, @StringRes val title: Int)

private val TelegramMenuOrder = listOf(
    TelegramMenuEntry("recent", R.string.telegram_recent),
    TelegramMenuEntry("channels", R.string.telegram_channels),
    TelegramMenuEntry("videos", R.string.telegram_videos),
    TelegramMenuEntry("youtube", R.string.telegram_youtube),
    TelegramMenuEntry("audio", R.string.telegram_audio),
    TelegramMenuEntry("photos", R.string.telegram_photos),
)

/** The chosen Monogram entries in menu order; with none chosen, the overview alone. */
internal fun telegramMenuEntries(submenuKeys: Collection<String>): List<TelegramMenuEntry> =
    TelegramMenuOrder.filter { it.key in submenuKeys }.ifEmpty { listOf(TelegramMenuOrder.first()) }

internal fun telegramMenuIcon(key: String): ImageVector = when (key) {
    "channels" -> Icons.Filled.Subscriptions
    "videos" -> Icons.Filled.OndemandVideo
    "youtube" -> Icons.Filled.SmartDisplay
    "audio" -> Icons.Filled.Audiotrack
    "photos" -> Icons.Filled.PhotoLibrary
    else -> Icons.Filled.Update
}
