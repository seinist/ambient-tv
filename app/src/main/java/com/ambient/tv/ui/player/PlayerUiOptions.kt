@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.media3.ui.AspectRatioFrameLayout

enum class PlayerResizeMode { FIT, ZOOM, STRETCH }
enum class PlayerAspectPreset(val ratio: Float?) {
    AUTO(null),
    RATIO_16_9(16f / 9f),
    RATIO_4_3(4f / 3f),
    RATIO_21_9(21f / 9f),
}

data class VideoPresentation(
    val resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    val aspectRatio: Float? = null,
    val zoom: Float = 1f,
)

/** Common presentation target implemented by every playback backend. */
interface VideoPresentationTarget {
    fun applyVideoPresentation(presentation: VideoPresentation)
}

internal fun videoResizeMode(key: String): Int = when (key) {
    "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

internal fun videoAspectRatio(key: String?): Float? = when (key) {
    "16:9" -> 16f / 9f
    "4:3" -> 4f / 3f
    "21:9" -> 21f / 9f
    else -> null
}

/** Provider dimensions are the initial FIT fallback until the decoder reports display geometry. */
internal fun sourceAspectRatioHint(width: Int?, height: Int?): Float? {
    val validWidth = width?.takeIf { it > 0 } ?: return null
    val validHeight = height?.takeIf { it > 0 } ?: return null
    return (validWidth.toFloat() / validHeight)
        .takeIf { it.isFinite() && it in 0.25f..4f }
}

internal fun VideoPresentation.resizeKey(): String = when (resizeMode) {
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "zoom"
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "stretch"
    else -> "fit"
}

internal fun VideoPresentation.aspectKey(): String = when {
    aspectRatio == null -> "auto"
    kotlin.math.abs(aspectRatio - (16f / 9f)) < 0.01f -> "16:9"
    kotlin.math.abs(aspectRatio - (4f / 3f)) < 0.01f -> "4:3"
    kotlin.math.abs(aspectRatio - (21f / 9f)) < 0.01f -> "21:9"
    else -> "auto"
}

internal fun VideoPresentation.playerResizeMode(): PlayerResizeMode = when (resizeMode) {
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> PlayerResizeMode.ZOOM
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> PlayerResizeMode.STRETCH
    else -> PlayerResizeMode.FIT
}

internal fun VideoPresentation.playerAspectPreset(): PlayerAspectPreset = when (aspectKey()) {
    "16:9" -> PlayerAspectPreset.RATIO_16_9
    "4:3" -> PlayerAspectPreset.RATIO_4_3
    "21:9" -> PlayerAspectPreset.RATIO_21_9
    else -> PlayerAspectPreset.AUTO
}

enum class SleepTimerKind { OFF, DURATION, END_OF_ITEM, PAUSE_INACTIVITY, USER_INACTIVITY }

// Keep the unified and legacy player menus in sync.
internal val pauseSleepTimerMinutes = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

data class SleepTimerConfig(
    val kind: SleepTimerKind = SleepTimerKind.OFF,
    val minutes: Int = 0,
    val deadlineRealtimeMs: Long? = null,
)
