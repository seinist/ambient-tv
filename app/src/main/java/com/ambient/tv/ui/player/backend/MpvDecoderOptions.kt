package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.player.HdrStandard

/**
 * Lowest coded height that keeps MPEG-2 and MPEG-4 Part 2 on MediaCodec (Kodi decodes SD
 * MPEG-2/MPEG-4 in software). H.263 has no MediaCodec decoder in FFmpeg and is always software.
 */
internal const val MPV_SD_SOFTWARE_MAX_HEIGHT = 720

/**
 * `vd-lavc-o` for the project's FFmpeg MediaCodec options (native/ambientmpv/patches), only
 * for options the loaded libavcodec really has ([MpvNativeFeatures]):
 *
 * - direct surface: `dolbyvision=1` on a Dolby Vision display (profile 5/7/8 and AV1 profile 10 go
 *   to a vendor DV decoder that advertises the profile), `dolbyvision=2` otherwise (profiles with
 *   an HDR10/HLG/SDR base layer decode it as HEVC/AV1; only profile 5 keeps the DV decoder).
 *   Without the routing patch the option is a 0..1 bool: always `dolbyvision=1` as before.
 * - GPU outputs: `hw_min_height` so SD MPEG-2/MPEG-4 fall back to libavcodec. The direct
 *   mediacodec_embed surface cannot show software frames, so it keeps them in hardware.
 *   Without the SD-software patch nothing is set (empty result).
 */
internal fun mpvLavcDecoderOptions(directSurface: Boolean, display: Set<HdrStandard>,
    features: Set<String> = MpvNativeFeatures.current): String = when {
    directSurface -> "dolbyvision=${if (HdrStandard.DOLBY_VISION in display || MpvNativeFeatures.DV_ROUTING !in features) 1 else 2}"
    MpvNativeFeatures.SD_SOFTWARE in features -> "hw_min_height=$MPV_SD_SOFTWARE_MAX_HEIGHT"
    else -> ""
}
