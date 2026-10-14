package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.MediaVersion

/** Pure selection logic kept Android-free so version decisions can be unit-tested. */
object MediaVersionSelection {
    fun selectIndex(
        versions: List<MediaVersion>,
        requestedIndex: Int,
        targetWidth: Int,
        targetHeight: Int,
        dimensionTolerance: Int = 32,
    ): Int {
        if (versions.isEmpty()) return requestedIndex
        val fallback = requestedIndex.coerceIn(0, versions.lastIndex)
        val target = normalize(targetWidth, targetHeight)
        if (target.first <= 0 || target.second <= 0) return fallback

        val candidates = versions.mapIndexed { index, version -> Candidate(index, version, dimensions(version)) }
        val known = candidates.filter { it.size != null }
        if (known.isEmpty()) return fallback

        val fitting = known.filter { candidate ->
            val size = candidate.size!!
            size.first <= target.first + dimensionTolerance && size.second <= target.second + dimensionTolerance
        }
        val selected = if (fitting.isNotEmpty()) {
            fitting.maxWithOrNull(compareBy<Candidate> { pixels(it.size!!) }.thenBy { it.version.bitrateKbps ?: 0 })
        } else {
            known.minWithOrNull(compareBy<Candidate> { pixels(it.size!!) }.thenBy { it.version.bitrateKbps ?: Int.MAX_VALUE })
        }
        return selected?.index ?: fallback
    }

    internal fun dimensions(version: MediaVersion): Pair<Int, Int>? {
        val width = version.width
        val height = version.height
        if (width != null && height != null && width > 0 && height > 0) return normalize(width, height)
        return when (version.resolution?.lowercase()) {
            "4k", "2160", "2160p" -> 3840 to 2160
            "2k", "1440", "1440p" -> 2560 to 1440
            "1080", "1080p" -> 1920 to 1080
            "720", "720p" -> 1280 to 720
            "576", "576p" -> 1024 to 576
            "480", "480p" -> 854 to 480
            else -> null
        }
    }

    private fun normalize(width: Int, height: Int): Pair<Int, Int> =
        if (width >= height) width to height else height to width

    private fun pixels(size: Pair<Int, Int>): Long = size.first.toLong() * size.second.toLong()

    private data class Candidate(
        val index: Int,
        val version: MediaVersion,
        val size: Pair<Int, Int>?,
    )
}
