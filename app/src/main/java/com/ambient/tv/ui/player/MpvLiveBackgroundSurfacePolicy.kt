package com.ambient.tv.ui.player

/**
 * MPV's video decoder should not lose its Surface while live audio continues in the
 * in-app background. Reattaching a new video decoder to a non-seekable live source
 * can leave it rendering old frames behind the still-running audio clock.
 *
 * Retain a hidden surface only for live MPV video. VOD and other backends keep the
 * normal minimize policy (no invisible decoder work).
 */
internal fun shouldRetainMpvLiveVideoSurface(
    activeVideo: Boolean,
    isLive: Boolean,
    mpvActive: Boolean,
): Boolean = activeVideo && isLive && mpvActive
