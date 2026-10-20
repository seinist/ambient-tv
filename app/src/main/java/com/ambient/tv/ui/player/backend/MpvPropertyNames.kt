package com.ambient.tv.ui.player.backend

/*
 * mpv property names read for HDR routing and the HDR CAS curve. Names are checked against the
 * pinned mpv (video-params sub-properties, current-tracks/track-list fields) by
 * MpvPropertyNamesTest; a misspelt name only ever reads "unavailable" at runtime.
 */

/** Luma range of the filtered source ("limited"/"full"). mpv has no `video-params/levels`. */
internal const val MPV_PROP_COLORLEVELS = "video-params/colorlevels"
/** Decoder-side range, before filters; fallback while video-params is not published yet. */
internal const val MPV_PROP_DEC_COLORLEVELS = "video-dec-params/colorlevels"
/** Id of the selected video track; > 0 once the track list is known. */
internal const val MPV_PROP_VIDEO_TRACK_ID = "current-tracks/video/id"
/** Dolby Vision profile from the container's DOVI configuration; unavailable without it. */
internal const val MPV_PROP_DOLBY_VISION_PROFILE = "current-tracks/video/dolby-vision-profile"
