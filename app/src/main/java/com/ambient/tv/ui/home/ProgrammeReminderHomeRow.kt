package com.ambient.tv.ui.home

import android.content.Context
import com.ambient.tv.R
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.livetv.LiveTvReminder
import com.ambient.tv.ui.common.ContentRowData
import com.ambient.tv.ui.common.MediaTileStyle
import java.text.DateFormat
import java.util.Date

internal const val HOME_PROGRAMME_REMINDER_PREFIX = "home-programme-reminder:"
internal const val HOME_PROGRAMME_REMINDER_ROW = "reminders.scheduled"

internal fun homeProgrammeReminderId(key: String): String? =
    key.takeIf { it.startsWith(HOME_PROGRAMME_REMINDER_PREFIX) }
        ?.removePrefix(HOME_PROGRAMME_REMINDER_PREFIX)?.takeIf(String::isNotBlank)

internal fun programmeReminderHomeRow(context: Context, reminders: List<LiveTvReminder>): ContentRowData? {
    val items = reminders.filter { !it.sessionEnd && it.isRelevant(System.currentTimeMillis()) }.sortedBy { it.startEpochSeconds }.map { reminder ->
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(reminder.startEpochSeconds * 1000))
        MediaItem(
            ratingKey = HOME_PROGRAMME_REMINDER_PREFIX + reminder.id,
            title = reminder.title, type = "clip", index = null,
            thumbUrl = reminder.imageUrl, artUrl = reminder.imageUrl, year = null,
            summary = date, tagline = reminder.channelName + " · " + date,
            durationMs = null, viewOffsetMs = null, parentTitle = null, parentRatingKey = null,
            grandparentTitle = null, grandparentRatingKey = null, contentRating = null, rating = null,
            studio = if (reminder.youtube) "SmartTube" else reminder.channelName,
        )
    }
    return items.takeIf { it.isNotEmpty() }?.let {
        ContentRowData(context.getString(R.string.programme_reminder_row), it, MediaTileStyle.LANDSCAPE, HOME_PROGRAMME_REMINDER_ROW)
    }
}
