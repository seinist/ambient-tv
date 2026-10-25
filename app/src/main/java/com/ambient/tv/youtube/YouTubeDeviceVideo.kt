package com.ambient.tv.youtube

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import timber.log.Timber

/**
 * What the device can show and decode, for choosing YouTube streams for VLC and MPV (which, unlike
 * Media3, get one fixed stream and do not adapt): the current display mode and the hardware
 * decoders for H.264, VP9 and AV1.
 */
object YouTubeDeviceVideo {
    private class Decoder(
        val family: String,
        val capabilities: MediaCodecInfo.VideoCapabilities,
        val hardware: Boolean,
        /** 10-bit profiles: "hdr10" with HDR10 metadata support, "10bit" for plain Main10/Profile 2. */
        val tenBit: Set<String>,
    )

    @Volatile private var decoders: List<Decoder>? = null

    /** Short side of the current display mode in pixels (TV: the HDMI output mode). */
    fun displayShortSide(context: Context): Int = runCatching {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        minOf(mode.physicalWidth, mode.physicalHeight)
    }.getOrElse {
        val metrics = context.resources.displayMetrics
        minOf(metrics.widthPixels, metrics.heightPixels)
    }

    /** "Automatic" for VLC/MPV: the display's resolution ([youtubeAutoMaxHeight]). */
    fun autoMaxHeight(context: Context): Int = youtubeAutoMaxHeight(displayShortSide(context))

    /** The user's cap, or the display-based automatic one. */
    fun maxHeight(context: Context, preference: Int): Int = preference.takeIf { it > 0 } ?: autoMaxHeight(context)

    /**
     * Target for automatic stream selection ([youtubeVideoTarget]): a chosen preset is only
     * limited by the user's cap; "automatic" (SmartTube's device default) also by the display.
     */
    fun videoTarget(context: Context, presetKey: String?, maxHeightPreference: Int): YouTubeVideoPreset {
        val cap = if (youtubeVideoPreset(presetKey) != null) maxHeightPreference else maxHeight(context, maxHeightPreference)
        return youtubeVideoTarget(presetKey, cap, decodeCheck())
    }

    /**
     * Hardware decoders only: software VP9/AV1 at these sizes stutters on TV boxes and phones.
     * H.264 may fall back to any decoder when the device reports no hardware one (emulators).
     *
     * HDR streams only with [allowHdr] (the renderer shows or tone-maps HDR), a display that
     * currently has that HDR type enabled, and a hardware decoder with the 10-bit HDR profile.
     */
    fun decodeCheck(context: Context? = null, allowHdr: Boolean = false): YouTubeDecodeCheck {
        val list = decoders ?: load().also { decoders = it }
        val display = if (allowHdr && context != null) com.ambient.tv.ui.player.HdrSupport.displayStandards(context) else emptySet()
        return check@{ family, width, height, fps, hdr ->
            if (hdr != null) {
                val shown = when (hdr) {
                    "hdr10" -> com.ambient.tv.ui.player.HdrStandard.HDR10 in display
                    "hlg" -> com.ambient.tv.ui.player.HdrStandard.HLG in display
                    else -> false
                }
                // HDR10 needs the HDR10 decoder profile; HLG has none of its own and needs 10-bit.
                val profile = if (hdr == "hdr10") setOf("hdr10") else setOf("hdr10", "10bit")
                return@check shown && list.any { it.family == family && it.hardware && it.tenBit.any(profile::contains) &&
                    runCatching {
                        if (fps > 0) it.capabilities.areSizeAndRateSupported(width, height, fps.toDouble()) else it.capabilities.isSizeSupported(width, height)
                    }.getOrDefault(false) }
            }
            fun fits(decoder: Decoder) = runCatching {
                if (fps > 0) decoder.capabilities.areSizeAndRateSupported(width, height, fps.toDouble())
                else decoder.capabilities.isSizeSupported(width, height)
            }.getOrDefault(false)
            val ofFamily = list.filter { it.family == family }
            if (ofFamily.any { it.hardware && fits(it) }) return@check true
            family == "avc" && ofFamily.none { it.hardware } && ofFamily.any(::fits)
        }
    }

    private val MIMES = mapOf("avc" to "video/avc", "vp9" to "video/x-vnd.on2.vp9", "av1" to "video/av01")

    private fun load(): List<Decoder> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filterNot { it.isEncoder }
            .flatMap { info ->
                MIMES.mapNotNull { (family, mime) ->
                    if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@mapNotNull null
                    val type = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return@mapNotNull null
                    val capabilities = type.videoCapabilities ?: return@mapNotNull null
                    Decoder(family, capabilities, isHardware(info), tenBitProfiles(family, type.profileLevels.map { it.profile }))
                }
            }
            .also { found -> Timber.i("YouTube decoders: %s", found.groupBy { it.family }.mapValues { (_, d) -> d.count { it.hardware } }) }
    }.getOrElse { error ->
        Timber.w(error, "MediaCodecList unavailable")
        emptyList()
    }

    private fun tenBitProfiles(family: String, profiles: List<Int>): Set<String> = buildSet {
        when (family) {
            "vp9" -> {
                if (profiles.any { it == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR || it == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus ||
                        it == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR }) add("hdr10")
                if (profiles.any { it == MediaCodecInfo.CodecProfileLevel.VP9Profile2 || it == MediaCodecInfo.CodecProfileLevel.VP9Profile3 }) add("10bit")
            }
            "av1" -> {
                if (profiles.any { it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 || it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus }) add("hdr10")
                if (profiles.any { it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 }) add("10bit")
            }
        }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        val name = info.name.lowercase()
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.startsWith("c2.google.") || ".sw." in name)
    }
}
