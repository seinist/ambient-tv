package com.ambient.tv.ui.common.projectm

import kotlin.math.roundToInt

/**
 * Render-buffer size for projectM, independent of the layout size.
 *
 * A SurfaceView buffer may be smaller or larger than its view; the compositor scales it to the
 * view's on-screen rect at the display's physical resolution. This is how a 4K mode shows real
 * 4K detail although the Android UI itself is laid out at 1080p.
 *
 * Keys are vertical lines for a full-window visualizer. "auto" caps at 1080p; 4K is opt-in.
 */
object ProjectMRenderResolution {
    const val AUTO = "auto"
    private const val AUTO_LINES = 1080
    private const val THROTTLED_LINES = 720
    private val explicitLines = listOf(720, 1080, 1440, 2160)
    val keys: List<String> = listOf(AUTO) + explicitLines.map(Int::toString)

    fun normalize(key: String?): String = key?.takeIf { it in keys } ?: AUTO

    /** Choices for a display whose shorter physical side has [displayLines] pixels. */
    fun options(displayLines: Int): List<String> {
        val limit = displayLines.coerceAtLeast(AUTO_LINES)
        return listOf(AUTO) + explicitLines.filter { it <= limit }.map(Int::toString)
    }

    /** Choice shown as selected: a stored step above this display shows as its largest offered step. */
    fun displayedKey(key: String?, displayLines: Int): String {
        val normalized = normalize(key)
        val offered = options(displayLines)
        return if (normalized in offered) normalized else offered.last()
    }

    /** Lines a full-window visualizer renders with, after display and performance limits. */
    fun effectiveLines(key: String?, displayLines: Int, throttled: Boolean): Int {
        val normalized = normalize(key)
        val requested = if (normalized == AUTO) AUTO_LINES else normalized.toInt()
        // Never render more lines than the panel can show. Unknown display (0) keeps the request.
        val displayCapped = if (displayLines > 0) requested.coerceAtMost(displayLines) else requested
        return if (throttled) displayCapped.coerceAtMost(THROTTLED_LINES) else displayCapped
    }

    /**
     * Buffer size for a [viewWidth]×[viewHeight] view inside a [windowWidth]×[windowHeight] window.
     * The view keeps its aspect ratio and its share of the window (mini views render smaller).
     * Returns null when the geometry is not known yet; the caller then keeps the layout size.
     */
    fun bufferSize(
        key: String?,
        viewWidth: Int,
        viewHeight: Int,
        windowWidth: Int,
        windowHeight: Int,
        displayLines: Int,
        throttled: Boolean,
    ): Pair<Int, Int>? {
        if (viewWidth <= 0 || viewHeight <= 0 || windowWidth <= 0 || windowHeight <= 0) return null
        val windowLines = minOf(windowWidth, windowHeight)
        val scale = effectiveLines(key, displayLines, throttled).toFloat() / windowLines
        return even(viewWidth * scale) to even(viewHeight * scale)
    }

    /** projectM HDR conversion needs Android 14; above-Full-HD output needs a panel with more lines. */
    fun hdr4kChoiceVisible(sdk: Int, displayLines: Int): Boolean = sdk >= 34 && displayLines > AUTO_LINES

    private fun even(value: Float): Int = ((value / 2f).roundToInt() * 2).coerceAtLeast(2)
}
