package com.ambient.tv.ui.tvheadend

/** Playback/preview wall time mapped onto the currently advertised EPG programme. */
internal fun programmePositionMs(playbackWallMs: Long, startSeconds: Long, stopSeconds: Long): Long =
    (playbackWallMs - startSeconds * 1000L).coerceIn(0L, ((stopSeconds - startSeconds) * 1000L).coerceAtLeast(0L))
