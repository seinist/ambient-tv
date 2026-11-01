package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.LiveTvProgramme

/** One shared half-hour time origin keeps all guide lanes aligned when initially opened. */
internal fun guideTimelineOrigin(nowEpochSeconds: Long): Long =
    ((nowEpochSeconds - 1_800L).coerceAtLeast(0L) / 1_800L) * 1_800L

/**
 * The item count and order deliberately remain identical to the EPG programme list.
 * RowColumnFocus and the remembered lazy-row indices must continue to refer to real events.
 * Missing airtime is rendered as a non-focusable prefix inside the following event's item.
 */
internal data class GuideTimelineSlot(
    val programme: LiveTvProgramme,
    val gapBeforeSeconds: Long,
    val visibleSeconds: Long,
)

internal fun guideTimelineSlots(events: List<LiveTvProgramme>, origin: Long): List<GuideTimelineSlot> {
    var cursor = origin
    return events.map { event ->
        val gap = (event.startEpochSeconds - cursor).coerceAtLeast(0L)
        val start = maxOf(cursor, event.startEpochSeconds)
        val visible = (event.stopEpochSeconds - start).coerceAtLeast(0L)
        cursor = maxOf(cursor, event.stopEpochSeconds)
        GuideTimelineSlot(event, gap, visible)
    }
}

/** True time-proportional width, unlike the old fixed 150-430dp clipping. */
internal fun guideTimelineWidthDp(seconds: Long): Float =
    (seconds.coerceAtLeast(0L).toFloat() / 60f) * 4f
