package com.ambient.tv.livetv

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
enum class GuideSort { NUMBER, NAME }

@Serializable
data class GuideOptions(
    val sort: GuideSort = GuideSort.NUMBER,
    val kind: LiveTvChannelKind? = null,
    val access: LiveTvChannelAccess? = null,
    val tag: String? = null,
    val onlyWithEpg: Boolean = false,
    /** Reverses the chosen field only. Missing channel numbers stay at the end either way. */
    val descending: Boolean = false,
    /**
     * Virtual category backed only by provider/server metadata. For TVHeadend this is true only
     * for channels whose stored service metadata exposed HbbTV without opening the live stream.
     */
    val onlyHbbtv: Boolean = false,
) {
    val filtersActive: Boolean get() = kind != null || access != null || tag != null || onlyWithEpg || onlyHbbtv
}

internal fun guideName(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

fun guideIndexLetter(name: String): Char {
    val initial = Normalizer.normalize(name.trim(), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar()
    return initial?.takeIf { it in 'A'..'Z' } ?: '#'
}

private data class ProgrammeIdentity(val start: Long, val stop: Long, val title: String, val subtitle: String?, val description: String?, val summary: String?)
private fun LiveTvProgramme.identity() = ProgrammeIdentity(startEpochSeconds, stopEpochSeconds, title, subtitle, description, summary)

/** Preserve event IDs for recording/playback, collapsing only identical programme cards. */
fun guideEvents(events: List<LiveTvProgramme>, now: Long): List<LiveTvProgramme> = events
    .filter { it.stopEpochSeconds >= now - 1800 }
    .distinctBy { it.sourceId to it.eventId }
    .sortedWith(compareByDescending<LiveTvProgramme> { it.dvrUuid != null }.thenBy { it.eventId })
    .distinctBy { Triple(it.sourceId, it.channelUuid, it.identity()) }
    .sortedWith(compareBy<LiveTvProgramme> { it.startEpochSeconds }.thenBy { it.stopEpochSeconds })

/** Apply filters before grouping: a hidden variant must never suppress a matching channel. */
fun guideChannels(
    channels: List<LiveTvChannel>,
    eventsByChannel: Map<String, List<LiveTvProgramme>>,
    now: Long,
    options: GuideOptions = GuideOptions(),
): List<LiveTvChannel> = guideChannelRows(channels, eventsByChannel, now, options).first

/**
 * The shown rows ([guideChannels]) and, for every channel that passes the filters, the UUID of
 * the row that shows it. A duplicate merged into another row maps to that row, so a channel
 * watched through its duplicate still finds its row.
 */
internal fun guideChannelRows(
    channels: List<LiveTvChannel>,
    eventsByChannel: Map<String, List<LiveTvProgramme>>,
    now: Long,
    options: GuideOptions = GuideOptions(),
): Pair<List<LiveTvChannel>, Map<String, String>> =
    guideChannelRowsFromIdentities(
        channels, eventsByChannel.mapValues { (_, events) -> guideEvents(events, now).map { it.identity() } }, options,
    )

/** Already-normalized EPG groups are used by the async channel browser; never sort them twice. */
internal fun guideChannelsPrepared(
    channels: List<LiveTvChannel>,
    normalizedEventsByChannel: Map<String, List<LiveTvProgramme>>,
    options: GuideOptions = GuideOptions(),
): List<LiveTvChannel> =
    guideChannelRowsFromIdentities(
        channels, normalizedEventsByChannel.mapValues { (_, events) -> events.map { it.identity() } }, options,
    ).first

private fun guideChannelRowsFromIdentities(
    channels: List<LiveTvChannel>,
    programmes: Map<String, List<ProgrammeIdentity>>,
    options: GuideOptions,
): Pair<List<LiveTvChannel>, Map<String, String>> {
    val rowFor = mutableMapOf<Any, String>()
    val shownAs = mutableMapOf<String, String>()
    // A stale persisted HbbTV filter becomes inert when this provider can no longer report the
    // category without opening streams. The UI then hides the category instead of showing zero rows.
    val hbbtvCategoryAvailable = channels.any { !it.hbbtvUrl.isNullOrBlank() }
    val matching = channels.distinctBy { it.sourceId to it.uuid }.filter { channel ->
        (options.kind == null || channel.kind == options.kind) &&
            (options.access == null || channel.access == options.access) &&
            (options.tag == null || options.tag in channel.tags) &&
            (!options.onlyWithEpg || !programmes[channel.uuid].isNullOrEmpty()) &&
            (!options.onlyHbbtv || !hbbtvCategoryAvailable || !channel.hbbtvUrl.isNullOrBlank())
    }.sortedWith(compareBy<LiveTvChannel> { it.number ?: Int.MAX_VALUE }.thenBy { it.uuid })
    val rows = matching.filter { channel ->
        val schedule = programmes[channel.uuid].orEmpty()
        // Without programme evidence, only identical source/UUIDs can safely be collapsed.
        val identity: Any = if (schedule.isEmpty()) channel.sourceId to channel.uuid
        else listOf(channel.sourceId, guideName(channel.name), channel.kind, channel.access, schedule)
        val existing = rowFor[identity]
        shownAs.putIfAbsent(channel.uuid, existing ?: channel.uuid)
        if (existing == null) rowFor[identity] = channel.uuid
        existing == null
    }
    val sorted = sortedGuideRows(rows, options)
    return sorted to shownAs
}

/** Descending flips the sort field. Name and number ties keep their previous order. */
private fun sortedGuideRows(rows: List<LiveTvChannel>, options: GuideOptions): List<LiveTvChannel> {
    val name: (LiveTvChannel) -> String = { guideName(it.name) }
    return when (options.sort) {
        GuideSort.NUMBER -> rows.sortedWith(
            compareBy<LiveTvChannel> { if (it.number == null) 1 else 0 }
                .thenBy { if (options.descending) -(it.number ?: 0) else (it.number ?: 0) }
                .thenBy(name)
                .thenBy { it.uuid },
        )
        GuideSort.NAME -> {
            val letter: (LiveTvChannel) -> Char = { guideIndexLetter(it.name) }
            val primary = if (options.descending) {
                compareByDescending<LiveTvChannel>(letter).thenByDescending(name)
            } else {
                compareBy<LiveTvChannel>(letter).thenBy(name)
            }
            rows.sortedWith(primary.thenBy { it.number ?: Int.MAX_VALUE }.thenBy { it.uuid })
        }
    }
}
