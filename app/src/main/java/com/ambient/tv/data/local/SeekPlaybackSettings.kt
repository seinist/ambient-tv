package com.ambient.tv.data.local

/** Kept together so adding playback options does not exhaust SettingsUiState's JVM parameter slots. */
data class SeekPlaybackSettings(
    val bigSeekJumpSeconds: Int = 60,
    val pauseRewindSeconds: Int = 0,
    val pauseRewindMinPauseSeconds: Int = 10,
    val resumeRewindSeconds: Int = 0,
)

internal val SEEK_JUMP_SECONDS = listOf(15, 30, 60, 90, 120)
internal val PAUSE_REWIND_SECONDS = listOf(0, 3, 5, 10, 15, 30)
internal val PAUSE_REWIND_MIN_PAUSE_SECONDS = listOf(0, 5, 10, 30, 60)
internal val RESUME_REWIND_SECONDS = listOf(0, 5, 10, 15, 30, 60)

fun AppPreferences.seekPlaybackSettings(): SeekPlaybackSettings = SeekPlaybackSettings(
    bigSeekJumpSeconds, pauseRewindSeconds, pauseRewindMinPauseSeconds, resumeRewindSeconds,
)
