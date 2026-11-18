package com.ambient.tv.ui.player.backend

/**
 * Kodi's "Sync playback to display" for mpv: `video-sync=display-resample` times frames to
 * the display refresh rate and resamples audio to keep it in sync.
 *
 * Only on mpv GPU outputs: the Android EGL/Vulkan contexts get the real refresh rate through
 * `display-fps-override` (see MpvPlaybackEngine.displayRefreshRateChanged); mediacodec_embed
 * (direct surface) presents through MediaCodec timestamps and has no mpv vsync timing.
 * Never with passthrough (a bitstream cannot be resampled), SVP (it re-times frames
 * itself), or a live stream (which may use variable FPS and must not retime playback).
 */
internal fun mpvVideoSync(
    syncToDisplay: Boolean,
    gpuOutput: Boolean,
    encodedAudio: Boolean,
    svpActive: Boolean,
    liveStream: Boolean = false,
): String = if (syncToDisplay && gpuOutput && !encodedAudio && !svpActive && !liveStream) "display-resample" else "audio"
