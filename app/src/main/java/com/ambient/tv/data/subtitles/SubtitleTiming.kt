package com.ambient.tv.data.subtitles

import kotlin.math.roundToLong

/** When a subtitle shows text, in milliseconds. */
data class CueSpan(val startMs: Long, val endMs: Long)

/** How to move a subtitle onto another: `t' = t × scale + offsetMs`. */
data class SubtitleSync(val offsetMs: Long, val scale: Double, val score: Double)

private const val TIME = """(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})"""
private val ARROW = Regex("""$TIME\s*-->\s*$TIME""")
private val ASS_DIALOGUE = Regex("""^(Dialogue:\s*[^,]*,)(\d+):(\d{2}):(\d{2})[.:](\d{2}),(\d+):(\d{2}):(\d{2})[.:](\d{2}),""", RegexOption.MULTILINE)

private fun ms(h: String, m: String, s: String, frac: String): Long =
    (h.toLongOrNull() ?: 0L) * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 + frac.padEnd(3, '0').take(3).toLong()

/** Cue times of SRT, WebVTT or ASS/SSA text; overlapping cues are merged. */
fun parseCueSpans(text: String): List<CueSpan> {
    val spans = mutableListOf<CueSpan>()
    ARROW.findAll(text).forEach { m ->
        val g = m.groupValues
        spans += CueSpan(ms(g[1], g[2], g[3], g[4]), ms(g[5], g[6], g[7], g[8]))
    }
    ASS_DIALOGUE.findAll(text).forEach { m ->
        val g = m.groupValues
        spans += CueSpan(ms(g[2], g[3], g[4], g[5] + "0"), ms(g[6], g[7], g[8], g[9] + "0"))
    }
    return mergeSpans(spans.filter { it.endMs > it.startMs })
}

internal fun mergeSpans(spans: List<CueSpan>): List<CueSpan> {
    val merged = mutableListOf<CueSpan>()
    spans.sortedBy { it.startMs }.forEach { s ->
        val last = merged.lastOrNull()
        if (last != null && s.startMs <= last.endMs) merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, s.endMs))
        else merged += s
    }
    return merged
}

/** Time both show text when [target] is scaled and shifted; both lists sorted and merged. */
internal fun overlapMs(reference: List<CueSpan>, target: List<CueSpan>, scale: Double, offsetMs: Long): Long {
    var i = 0
    var j = 0
    var total = 0L
    while (i < reference.size && j < target.size) {
        val r = reference[i]
        val ts = (target[j].startMs * scale).roundToLong() + offsetMs
        val te = (target[j].endMs * scale).roundToLong() + offsetMs
        val start = maxOf(r.startMs, ts)
        val end = minOf(r.endMs, te)
        if (end > start) total += end - start
        if (r.endMs < te) i++ else j++
    }
    return total
}

/** Frame-rate mismatches between releases (23.976 / 24 / 25 fps), besides a plain shift. */
internal val SYNC_SCALES = listOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 25.0 / 24.0, 24.0 / 25.0, 24.0 / 23.976, 23.976 / 24.0)

/**
 * The shift (and frame-rate scale) that lays [target]'s cues best over [reference]'s, searched
 * within ±[maxOffsetMs]. Null when the match is not clearly better than chance.
 */
fun findSubtitleSync(reference: List<CueSpan>, target: List<CueSpan>, maxOffsetMs: Long = 90_000): SubtitleSync? {
    if (reference.size < MIN_CUES || target.size < MIN_CUES) return null
    val refTotal = reference.sumOf { it.endMs - it.startMs }
    var best: SubtitleSync? = null
    for (scale in SYNC_SCALES) {
        val tgtTotal = (target.sumOf { it.endMs - it.startMs } * scale).roundToLong()
        val norm = minOf(refTotal, tgtTotal).coerceAtLeast(1).toDouble()
        var coarseBest = Long.MIN_VALUE
        var coarseOffset = 0L
        var sum = 0.0
        var count = 0
        var offset = -maxOffsetMs
        while (offset <= maxOffsetMs) {
            val o = overlapMs(reference, target, scale, offset)
            sum += o; count++
            if (o > coarseBest) { coarseBest = o; coarseOffset = offset }
            offset += COARSE_STEP_MS
        }
        var fineBest = coarseBest
        var fineOffset = coarseOffset
        var fine = coarseOffset - COARSE_STEP_MS
        while (fine <= coarseOffset + COARSE_STEP_MS) {
            val o = overlapMs(reference, target, scale, fine)
            if (o > fineBest) { fineBest = o; fineOffset = fine }
            fine += FINE_STEP_MS
        }
        val score = fineBest / norm
        val chance = (sum / count) / norm
        // A real match stands well above what random shifts give.
        if (score < MIN_SCORE || score < chance * MIN_OVER_CHANCE) continue
        // Another frame rate must do clearly better than a plain shift to be taken.
        val margin = if (best?.scale == 1.0) SCALE_MARGIN else 1.0
        if (best == null || score > best.score * margin) best = SubtitleSync(fineOffset, scale, score)
    }
    return best
}

/** [text] with every cue time moved by [sync]; formats are kept (SRT, WebVTT, ASS/SSA). */
fun retimeSubtitle(text: String, sync: SubtitleSync): String {
    fun move(t: Long) = ((t * sync.scale).roundToLong() + sync.offsetMs).coerceAtLeast(0L)
    fun clock(t: Long, separator: Char): String {
        val h = t / 3_600_000; val m = t / 60_000 % 60; val s = t / 1_000 % 60; val ms = t % 1_000
        return "%02d:%02d:%02d%c%03d".format(h, m, s, separator, ms)
    }
    val arrows = ARROW.replace(text) { m ->
        val g = m.groupValues
        val separator = if (m.value.contains(',')) ',' else '.'
        val start = move(ms(g[1], g[2], g[3], g[4]))
        val end = move(ms(g[5], g[6], g[7], g[8]))
        // Cue settings after the end time (WebVTT) stay as they are.
        "${clock(start, separator)} --> ${clock(end, separator)}"
    }
    return ASS_DIALOGUE.replace(arrows) { m ->
        val g = m.groupValues
        fun ass(t: Long) = "%d:%02d:%02d.%02d".format(t / 3_600_000, t / 60_000 % 60, t / 1_000 % 60, t % 1_000 / 10)
        "${g[1]}${ass(move(ms(g[2], g[3], g[4], g[5] + "0")))},${ass(move(ms(g[6], g[7], g[8], g[9] + "0")))},"
    }
}

private const val MIN_CUES = 20
private const val COARSE_STEP_MS = 100L
private const val FINE_STEP_MS = 10L
private const val MIN_SCORE = 0.45
private const val MIN_OVER_CHANCE = 1.5
private const val SCALE_MARGIN = 1.08
