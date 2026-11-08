package com.ambient.tv.data.local

/** Kept together so adding playback options does not exhaust SettingsUiState's JVM parameter slots. */
data class SeekPlaybackSettings(
    val bigSeekJumpSeconds: Int = 60,
    val pauseRewindSeconds: Int = 0,
    val pauseRewindMinPauseSeconds: Int = 10,
    val resumeRewindSeconds: Int = 0,
    val watchedThresholdPercent: Int = 90,
    val resumeIgnoreEndPercent: Int = 8,
)

internal val SEEK_JUMP_SECONDS = listOf(15, 30, 60, 90, 120)
internal val PAUSE_REWIND_SECONDS = listOf(0, 3, 5, 10, 15, 30)
internal val PAUSE_REWIND_MIN_PAUSE_SECONDS = listOf(0, 5, 10, 30, 60)
internal val RESUME_REWIND_SECONDS = listOf(0, 5, 10, 15, 30, 60)
internal val WATCHED_THRESHOLD_PERCENT = listOf(70, 75, 80, 85, 90, 95, 100)
internal val RESUME_IGNORE_END_PERCENT = listOf(0, 2, 5, 8, 10, 15, 20)

fun AppPreferences.seekPlaybackSettings(): SeekPlaybackSettings = SeekPlaybackSettings(
    bigSeekJumpSeconds, pauseRewindSeconds, pauseRewindMinPauseSeconds, resumeRewindSeconds,
    watchedThresholdPercent, resumeIgnoreEndPercent,
)
