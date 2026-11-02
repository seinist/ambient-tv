package com.ambient.tv.ui.common

import android.text.format.DateUtils
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Publication time, short and relative only while that reads better than a clock time:
 * under 1 h "vor 5 Min.", under 6 h "vor 3 Std.", then "Heute, 14:05" / "Gestern, 14:05",
 * older the plain date. [withTime] adds the clock time to older dates (detail views).
 */
fun relativePublishedLabel(epochMs: Long, nowMs: Long = System.currentTimeMillis(), withTime: Boolean = false): String {
    val age = nowMs - epochMs
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))
    return when {
        age < 0 -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
        age < DateUtils.HOUR_IN_MILLIS -> DateUtils.getRelativeTimeSpanString(
            nowMs - age.coerceAtLeast(DateUtils.MINUTE_IN_MILLIS), nowMs, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        age < 6 * DateUtils.HOUR_IN_MILLIS -> DateUtils.getRelativeTimeSpanString(
            epochMs, nowMs, DateUtils.HOUR_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        daysBetween(epochMs, nowMs) <= 1 -> DateUtils.getRelativeTimeSpanString(
            epochMs, nowMs, DateUtils.DAY_IN_MILLIS, 0).toString() + ", " + time
        withTime -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}

private fun daysBetween(fromMs: Long, toMs: Long): Int {
    fun dayStart(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return Math.round((dayStart(toMs) - dayStart(fromMs)) / DateUtils.DAY_IN_MILLIS.toDouble()).toInt()
}
