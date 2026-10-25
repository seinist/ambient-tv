package com.ambient.tv.ui.player.backend

/**
 * Kodi's "Sync playback to display" for mpv: `video-sync=display-resample` times frames to
 * the display refresh rate and resamples audio to keep it in sync.
 *
 * Only on mpv GPU outputs: the Android EGL/Vulkan contexts get the real refresh rate through
 * `display-fps-override` (see MpvPlaybackEngine.displayRefreshRateChanged); mediacodec_embed
 * (direct surface) presents through MediaCodec timestamps and has no mpv vsync timing.
 * Never with passthrough (a bitstream cannot be resampled) or SVP (it re-times frames itself).
 */
internal fun mpvVideoSync(
    syncToDisplay: Boolean,
    gpuOutput: Boolean,
    encodedAudio: Boolean,
    svpActive: Boolean,
): String = if (syncToDisplay && gpuOutput && !encodedAudio && !svpActive) "display-resample" else "audio"
