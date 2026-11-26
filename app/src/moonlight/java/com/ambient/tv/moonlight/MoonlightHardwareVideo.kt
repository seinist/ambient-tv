package com.ambient.tv.moonlight

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Display
import android.os.Build
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.binding.video.MediaCodecHelper

/**
 * GameStream video codec selection. Do NOT advertise a 10-bit format unless the display
 * reports HDR10 and a hardware decoder exposes an HDR10-compatible Main10 profile.
 * HDR10 signal is never reinterpreted with an SDR BT.709 pixel format.
 */
internal object MoonlightHardwareVideo {
    private data class Candidate(val name: String, val mime: String, val sdr: Int, val hdr10: Int)
    private val codecs = listOf(
        Candidate("av1", MediaFormat.MIMETYPE_VIDEO_AV1,
            MoonBridge.VIDEO_FORMAT_AV1_MAIN8, MoonBridge.VIDEO_FORMAT_AV1_MAIN10),
        Candidate("hevc", MediaFormat.MIMETYPE_VIDEO_HEVC,
            MoonBridge.VIDEO_FORMAT_H265, MoonBridge.VIDEO_FORMAT_H265_MAIN10),
        Candidate("h264", MediaFormat.MIMETYPE_VIDEO_AVC,
            MoonBridge.VIDEO_FORMAT_H264, 0),
    )

    fun displaySupportsHdr10(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching false
        val types = if (Build.VERSION.SDK_INT >= 34) display.mode.supportedHdrTypes
            else display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
        Display.HdrCapabilities.HDR_TYPE_HDR10 in types
    }.getOrDefault(false)

    private var helperInitialized = false
    @Synchronized fun initialize(context: Context) {
        if (helperInitialized) return
        // The direct Surface path does not create an EGL context just to query the GPU.
        // Use the upstream helper's device/vendor logic; unknown GL renderer stays unknown.
        MediaCodecHelper.initialize(context.applicationContext, "")
        helperInitialized = true
    }

    private val codecInfos by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }

    internal fun decoders(
        mime: String, width: Int, height: Int, fps: Int, hdr10: Boolean = false,
    ): List<MediaCodecInfo> = runCatching {
        codecInfos.filter { codec ->
            !codec.isEncoder && codec.isHardwareAccelerated &&
                codec.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                runCatching {
                    val capabilities = codec.getCapabilitiesForType(mime)
                    capabilities.videoCapabilities
                        ?.areSizeAndRateSupported(width, height, fps.toDouble()) == true &&
                        (!hdr10 || capabilities.profileLevels.any { profile ->
                            when (mime) {
                                MediaFormat.MIMETYPE_VIDEO_HEVC ->
                                    profile.profile in setOf(
                                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
                                    )
                                MediaFormat.MIMETYPE_VIDEO_AV1 ->
                                    profile.profile in setOf(
                                        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
                                        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus,
                                    )
                                else -> false
                            }
                        })
                }.getOrDefault(false)
        }
    }.getOrDefault(emptyList())

    internal fun decoder(mime: String, width: Int, height: Int, fps: Int, hdr10: Boolean = false) =
        decoders(mime, width, height, fps, hdr10).firstOrNull()

    /**
     * Explicit HDR "on": reject if any peer/display/decoder requirement is absent.
     * Automatic: prefer HDR10 but always fall back to an actually supported SDR codec.
     */
    fun supportedFormat(
        profile: MoonlightStreamProfile,
        appHdrSupported: Boolean = false,
        displayHdr10: Boolean = false,
        hostCodecModes: Long = 0L,
    ): Int {
        val valid = profile.validated()
        val choices = if (valid.codec == "auto") codecs else codecs.filter { it.name == valid.codec }
        var sdr = 0
        var hdr = 0
        for (choice in choices) {
            val hostSupportsSdr = when (choice.name) {
                "hevc" -> hostCodecModes == 0L || hostCodecModes and 0x100L != 0L
                "av1" -> hostCodecModes and 0x10000L != 0L
                else -> true
            }
            val hostSupportsHdr = when (choice.name) {
                "hevc" -> hostCodecModes and 0x200L != 0L
                "av1" -> hostCodecModes and 0x20000L != 0L
                else -> false
            }
            if (hostSupportsSdr && decoder(choice.mime, valid.width, valid.height, valid.fps) != null)
                sdr = sdr or choice.sdr
            if (valid.hdrMode != "off" && appHdrSupported && displayHdr10 && hostSupportsHdr &&
                decoder(choice.mime, valid.width, valid.height, valid.fps, hdr10 = true) != null)
                hdr = hdr or choice.hdr10
        }
        // Common-c negotiates the best mutually supported codec; never advertise a
        // single preferred codec for Auto and thereby prevent fallback.
        return when (valid.hdrMode) {
            "on" -> hdr
            "off" -> sdr
            else -> hdr or sdr
        }
    }

    fun isHdr10Format(format: Int): Boolean =
        (format and MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0

    fun mimeFor(format: Int): String? = when {
        (format and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0 -> MediaFormat.MIMETYPE_VIDEO_AV1
        (format and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0 -> MediaFormat.MIMETYPE_VIDEO_HEVC
        (format and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0 -> MediaFormat.MIMETYPE_VIDEO_AVC
        else -> null
    }
}
