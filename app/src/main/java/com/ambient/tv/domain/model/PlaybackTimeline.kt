package com.ambient.tv.domain.model

/** Milliseconds on the original media timeline, independent of decoder/transcode offsets. */
@kotlinx.serialization.Serializable
data class VideoChapter(val startMs: Long, val title: String? = null, val imageUrl: String? = null, val endMs: Long? = null)
data class PreviewFrame(val url: String, val x: Int = 0, val y: Int = 0, val width: Int = 0, val height: Int = 0,
                        val sheetWidth: Int = 0, val sheetHeight: Int = 0)

sealed interface TimelinePreview {
    fun frameAt(positionMs: Long): PreviewFrame?
    data class Plex(val urlTemplate: String) : TimelinePreview {
        override fun frameAt(positionMs: Long) = PreviewFrame(urlTemplate.replace("__TIME__", ((positionMs.coerceAtLeast(0) / 2_000L) * 2_000L).toString()))
    }
    data class Sprite(val width: Int, val height: Int, val columns: Int, val rows: Int,
                      val intervalMs: Long, val count: Long, val urls: List<String> = emptyList(),
                      val urlTemplate: String? = null) : TimelinePreview {
        override fun frameAt(positionMs: Long): PreviewFrame? {
            if (width !in 1..1024 || height !in 1..1024 || columns !in 1..20 || rows !in 1..20 ||
                intervalMs <= 0 || count <= 0 || width.toLong() * height * columns * rows > 12_000_000L) return null
            val frame = (positionMs.coerceAtLeast(0) / intervalMs).coerceAtMost(count - 1)
            val perSheet = columns * rows
            val sheet = frame / perSheet
            val url = urlTemplate?.replace("__INDEX__", sheet.toString()) ?: urls.getOrNull(sheet.toInt()) ?: return null
            val cell = (frame % perSheet).toInt()
            return PreviewFrame(url, cell % columns * width, cell / columns * height, width, height, width * columns, height * rows)
        }
    }
}

data class PlaybackTimeline(val chapters: List<VideoChapter> = emptyList(), val preview: TimelinePreview? = null, val segments: List<PlaybackSegment> = emptyList()) {
    fun normalized(durationMs: Long = 0): PlaybackTimeline = copy(segments = normalizePlaybackSegments(segments, durationMs), chapters = chapters
        .filter { it.startMs >= 0 && (durationMs <= 0 || it.startMs < durationMs) }
        .sortedBy { it.startMs }.distinctBy { it.startMs }.take(2_000),
        preview = if (preview is TimelinePreview.Sprite && durationMs > 0 && preview.intervalMs > 0)
            preview.copy(count = minOf(preview.count, (durationMs - 1) / preview.intervalMs + 1)) else preview)
    fun chapterAt(positionMs: Long): VideoChapter? = chapters.lastOrNull { it.startMs <= positionMs }
        ?.takeIf { it.endMs == null || it.endMs <= it.startMs || positionMs < it.endMs }
    fun previewFrameAt(positionMs: Long): PreviewFrame? = preview?.frameAt(positionMs) ?: chapterImageFrame(chapterAt(positionMs))
    fun chapterFrame(chapter: VideoChapter): PreviewFrame? = preview?.frameAt(chapter.startMs) ?: chapterImageFrame(chapter)
}

private fun chapterImageFrame(chapter: VideoChapter?): PreviewFrame? =
    chapter?.imageUrl?.takeIf { it.isNotBlank() }?.let { PreviewFrame(it) }
