package com.ambient.tv.car

import com.ambient.tv.livetv.GuideOptions
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvChannelAccess
import com.ambient.tv.livetv.LiveTvChannelKind
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.guideChannelRows
import com.ambient.tv.livetv.guideEvents
import com.ambient.tv.livetv.guideName

internal data class CarLiveRadioSelection(
    val channel: LiveTvChannel,
    val queue: List<LiveTvChannel>,
)

/** One repository's radio snapshot, shared by browsing, searching and playback queues. */
internal class CarLiveRadioChannels(
    channels: List<LiveTvChannel>,
    programmes: List<LiveTvProgramme>,
    private val nowEpochSeconds: Long,
) {
    // A playlist may repeat a station in several groups. Merge its tags before paging so
    // a duplicate cannot consume a page slot, disappear from a group or repeat in Next.
    private val radio = uniqueRadioChannels(channels)
    private val programmesByChannel = programmes.groupBy { it.channelUuid }
    private val rows = guideChannelRows(
        radio, programmesByChannel, nowEpochSeconds, GuideOptions(kind = LiveTvChannelKind.RADIO),
    )
    val all: List<LiveTvChannel> get() = rows.first
    val tags: List<String> = radio.flatMap { it.tags }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** Exact duplicates need no guide; only separate variants could benefit from EPG evidence. */
    val needsProgrammeEvidence: Boolean = run {
        val seen = HashSet<StationName>()
        radio.any { !seen.add(StationName(it.sourceId, guideName(it.name), it.access)) }
    }

    private fun rowsFor(tag: String?): Pair<List<LiveTvChannel>, Map<String, String>> =
        if (tag.isNullOrEmpty()) rows else guideChannelRows(
            radio, programmesByChannel, nowEpochSeconds,
            GuideOptions(kind = LiveTvChannelKind.RADIO, tag = tag),
        )

    fun inGroup(tag: String): List<LiveTvChannel> = rowsFor(tag).first

    /** Old IDs and a group's alternate representative must remain playable after grouping. */
    fun selection(uuid: String, group: String? = null): CarLiveRadioSelection? {
        val selected = radio.firstOrNull { it.uuid == uuid } ?: return null
        val visible = rowsFor(group).takeIf { uuid in it.second } ?: rows
        val representative = visible.second[uuid] ?: return null
        return CarLiveRadioSelection(selected, visible.first.map { channel ->
            if (channel.sourceId == selected.sourceId && channel.uuid == representative) selected else channel
        })
    }

    private data class StationName(val sourceId: String, val name: String, val access: LiveTvChannelAccess)
}

/** The car's now-playing panel uses only this station's guide, including legacy cached events. */
internal fun carLiveRadioProgrammes(
    programmes: List<LiveTvProgramme>,
    sourceId: String,
    channelUuid: String,
    nowEpochSeconds: Long,
): List<LiveTvProgramme> = guideEvents(programmes.filter {
    it.channelUuid == channelUuid && (it.sourceId.isBlank() || it.sourceId == sourceId)
}.map { if (it.sourceId.isBlank()) it.copy(sourceId = sourceId) else it }, nowEpochSeconds)

private fun uniqueRadioChannels(channels: List<LiveTvChannel>): List<LiveTvChannel> {
    val unique = LinkedHashMap<Pair<String, String>, LiveTvChannel>()
    channels.filter { it.kind == LiveTvChannelKind.RADIO }.forEach { channel ->
        val key = channel.sourceId to channel.uuid
        val previous = unique[key]
        val tags = (previous?.tags.orEmpty() + channel.tags).map(String::trim)
            .filter(String::isNotBlank).distinct()
        unique[key] = if (previous == null) channel.copy(tags = tags) else previous.copy(
            tags = tags,
            iconUrl = previous.iconUrl?.takeIf(String::isNotBlank) ?: channel.iconUrl,
        )
    }
    return unique.values.toList()
}
