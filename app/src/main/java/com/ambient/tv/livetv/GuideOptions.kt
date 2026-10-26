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
) {
    val filtersActive: Boolean get() = kind != null || access != null || tag != null || onlyWithEpg
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
): Pair<List<LiveTvChannel>, Map<String, String>> {
    val programmes = eventsByChannel.mapValues { (_, events) -> guideEvents(events, now).map { it.identity() } }
    val rowFor = mutableMapOf<Any, String>()
    val shownAs = mutableMapOf<String, String>()
    val matching = channels.distinctBy { it.sourceId to it.uuid }.filter { channel ->
        (options.kind == null || channel.kind == options.kind) &&
            (options.access == null || channel.access == options.access) &&
            (options.tag == null || options.tag in channel.tags) &&
            (!options.onlyWithEpg || !programmes[channel.uuid].isNullOrEmpty())
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
    val sorted = when (options.sort) {
        GuideSort.NUMBER -> rows.sortedWith(compareBy<LiveTvChannel> { it.number ?: Int.MAX_VALUE }.thenBy { guideName(it.name) }.thenBy { it.uuid })
        GuideSort.NAME -> rows.sortedWith(compareBy<LiveTvChannel> { guideIndexLetter(it.name) }.thenBy { guideName(it.name) }.thenBy { it.number ?: Int.MAX_VALUE }.thenBy { it.uuid })
    }
    return sorted to shownAs
}
