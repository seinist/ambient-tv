package com.ambient.tv.youtube

import com.ambient.tv.domain.model.PlaybackSegment
import com.ambient.tv.domain.model.PlaybackTimeline

/** One SponsorBlock range with its category ("sponsor", "intro", ...). */
data class YouTubeSponsorSegment(val startMs: Long, val endMs: Long, val category: String)

/**
 * SponsorBlock categories offered in Settings, and what happens in each:
 * auto = skipped by the playback service, button = "Skip …" button, mark = only coloured in the
 * seek bar, off = not even fetched.
 */
object SponsorBlockCategories {
    val ALL = listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "filler", "music_offtopic")
    val MODES = listOf("auto", "button", "mark", "off")
    val DEFAULTS = mapOf(
        "sponsor" to "auto", "selfpromo" to "auto", "interaction" to "auto",
        "intro" to "button", "outro" to "button", "preview" to "mark",
        "filler" to "off", "music_offtopic" to "off",
    )

    /** Stored as "category=mode,…"; unknown entries fall back to [DEFAULTS]. */
    fun parse(stored: String?): Map<String, String> {
        val saved = stored.orEmpty().split(',').mapNotNull { entry ->
            val (category, mode) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            (category.trim() to mode.trim()).takeIf { it.first in ALL && it.second in MODES }
        }.toMap()
        return ALL.associateWith { saved[it] ?: DEFAULTS.getValue(it) }
    }

    fun format(modes: Map<String, String>): String =
        ALL.joinToString(",") { "$it=${modes[it] ?: DEFAULTS.getValue(it)}" }

    /** Categories worth requesting at all. */
    fun requested(modes: Map<String, String>): Set<String> = ALL.filter { modes[it] != "off" }.toSet()
}

/** Timeline segment type of a SponsorBlock category: "sponsor:intro", "sponsor:sponsor", … */
fun sponsorSegmentType(category: String) = "sponsor:$category"
fun sponsorCategoryOf(type: String): String? = type.takeIf { it.startsWith("sponsor:") }?.removePrefix("sponsor:")

/**
 * SponsorBlock ranges in the shared timeline, typed by category. They can update after playback
 * has begun; categories set to "off" are left out.
 */
internal fun youtubeSponsorTimeline(
    timeline: PlaybackTimeline,
    segments: List<YouTubeSponsorSegment>,
    durationMs: Long,
    modes: Map<String, String> = SponsorBlockCategories.DEFAULTS,
): PlaybackTimeline {
    if (segments.isEmpty()) return timeline
    val sponsorSegments = segments.mapNotNull { segment ->
        if (modes[segment.category] == "off") return@mapNotNull null
        val start = segment.startMs
        val end = if (durationMs > 0) segment.endMs.coerceAtMost(durationMs) else segment.endMs
        if (start < 0 || end <= start) null else PlaybackSegment(sponsorSegmentType(segment.category), start, end)
    }
    return timeline.copy(segments = timeline.segments + sponsorSegments).normalized(durationMs)
}
