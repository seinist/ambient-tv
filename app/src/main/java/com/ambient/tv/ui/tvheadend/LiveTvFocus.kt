package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.GuideOptions
import com.ambient.tv.livetv.LiveTvDashboard
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.guideChannelRows
import com.ambient.tv.livetv.guideChannels
import com.ambient.tv.livetv.guideEvents

/**
 * Where focus goes when a Live TV tab is entered: the saved selection if it is still shown,
 * else the first entry. In the Guide a channel target is the channel header, its row starts
 * at the programme running now (see [guideLiveIndex]).
 */
internal fun liveTvFocusTarget(
    tab: TvheadendTab,
    dashboard: LiveTvDashboard?,
    options: GuideOptions,
    savedId: String?,
    savedRowId: String?,
    now: Long,
    listing: LiveTvChannelListing? = null,
): TvhFocusTarget = when (tab) {
    TvheadendTab.LIVE -> {
        val ids = listing?.visibleChannelIds ?: visibleChannelIds(dashboard, options, now)
        TvhFocusTarget(savedId?.takeIf { it in ids } ?: ids.firstOrNull())
    }
    TvheadendTab.GUIDE -> {
        // Prepared listing avoids a second full group/sort pass on the Compose thread.
        val rows = if (listing != null) listing.visibleChannels.map { channel ->
            channel.uuid to listing.eventsByChannel[channel.uuid].orEmpty()
        } else {
            val eventsByChannel = dashboard?.events.orEmpty().groupBy { it.channelUuid }
            guideChannels(dashboard?.channels.orEmpty(), eventsByChannel, now, options).map { channel ->
                channel.uuid to guideEvents(eventsByChannel[channel.uuid].orEmpty(), now)
            }
        }
        val savedRow = rows.firstOrNull { (rowId, events) ->
            rowId == savedRowId && (savedId == "channel:$rowId" || events.any { "event:${it.eventId}" == savedId })
        }
        val row = savedRow ?: rows.firstOrNull { (rowId, events) -> savedId == "channel:$rowId" || events.any { "event:${it.eventId}" == savedId } }
            ?: rows.firstOrNull()
        val event = if (savedId == "channel:${row?.first}") null else savedId?.let { id -> row?.second?.firstOrNull { "event:${it.eventId}" == id } }
            ?: row?.second?.firstOrNull()
        TvhFocusTarget(event?.let { "event:${it.eventId}" } ?: row?.first?.let { "channel:$it" }, row?.first)
    }
    TvheadendTab.RECORDINGS -> {
        val rows = (dashboard?.recordings.orEmpty() + dashboard?.failedRecordings.orEmpty()).sortedByDescending { it.startEpochSeconds }
        TvhFocusTarget(savedId?.takeIf { value -> rows.any { it.uuid == value } } ?: rows.firstOrNull()?.uuid)
    }
    TvheadendTab.TIMERS -> {
        val ids = dashboard?.upcoming.orEmpty().map { it.uuid } + dashboard?.autoRecs.orEmpty().map { "autorec:${it.uuid}" }
        TvhFocusTarget(savedId?.takeIf { it in ids } ?: ids.firstOrNull())
    }
    TvheadendTab.HBBTV -> {
        val ids = tvheadendHbbtvEntries(dashboard?.channels.orEmpty()).map { it.key }
        TvhFocusTarget(savedId?.takeIf { it in ids } ?: ids.firstOrNull())
    }
}

/** Channels Live TV and the Guide show with the current filters and sort. */
internal fun visibleChannelIds(dashboard: LiveTvDashboard?, options: GuideOptions, now: Long): List<String> =
    guideChannels(dashboard?.channels.orEmpty(), dashboard?.events.orEmpty().groupBy { it.channelUuid }, now, options).map { it.uuid }

/**
 * The selection to store for a channel coming back from the player, per tab, or null when the
 * channel is hidden by the current filters (then the previous selection stays). A duplicate
 * merged into another row selects that row.
 */
internal fun returnFocusSelections(channelUuid: String, dashboard: LiveTvDashboard?, options: GuideOptions, now: Long): Map<TvheadendTab, TvhFocusTarget>? {
    val row = if (dashboard == null) channelUuid else guideChannelRows(dashboard.channels,
        dashboard.events.groupBy { it.channelUuid }, now, options).second[channelUuid] ?: return null
    return mapOf(
        TvheadendTab.LIVE to TvhFocusTarget(row),
        TvheadendTab.GUIDE to TvhFocusTarget("channel:$row", row),
    )
}

/** Guide row start: the programme running at [now], else the next one, else the first. */
internal fun guideLiveIndex(events: List<LiveTvProgramme>, now: Long): Int =
    events.indexOfFirst { it.stopEpochSeconds > now }.coerceAtLeast(0)
