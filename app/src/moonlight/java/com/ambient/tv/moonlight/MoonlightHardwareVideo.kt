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
    private data class DecoderKey(val mime: String, val width: Int, val height: Int, val fps: Int, val hdr: Boolean)
    private val decoderCache = LinkedHashMap<DecoderKey, List<MediaCodecInfo>>(16, .75f, true)

    private fun performanceRank(codec: MediaCodecInfo, mime: String, width: Int, height: Int, fps: Int): Int =
        runCatching {
            val caps = codec.getCapabilitiesForType(mime)
            val point = MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, fps)
            val points = caps.videoCapabilities?.supportedPerformancePoints
            val guaranteed = if (points.isNullOrEmpty()) 1 else if (points.any { it.covers(point) }) 2 else 0
            val lowLatency = Build.VERSION.SDK_INT >= 30 &&
                caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            val recommended = when (mime) {
                MediaFormat.MIMETYPE_VIDEO_HEVC -> MediaCodecHelper.decoderIsWhitelistedForHevc(codec)
                MediaFormat.MIMETYPE_VIDEO_AV1 -> MediaCodecHelper.isDecoderWhitelistedForAv1(codec)
                else -> !MediaCodecHelper.decoderNeedsBaselineSpsHack(codec.name) &&
                    !MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(codec.name)
            }
            guaranteed * 4 + (if (lowLatency) 2 else 0) + (if (recommended) 1 else 0)
        }.getOrDefault(0)

    @Synchronized internal fun decoders(
        mime: String, width: Int, height: Int, fps: Int, hdr10: Boolean = false,
    ): List<MediaCodecInfo> {
        val key = DecoderKey(mime, width, height, fps, hdr10)
        decoderCache[key]?.let { return it }
        val result = runCatching {
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
        }.getOrDefault(emptyList()).sortedByDescending { performanceRank(it, mime, width, height, fps) }
        if (decoderCache.size >= 32) decoderCache.remove(decoderCache.keys.first())
        decoderCache[key] = result
        return result
    }

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

    private val advancedCaps = MoonBridge.CAPABILITY_DIRECT_SUBMIT or
        MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC or
        MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC or
        MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1

    fun hasAdvancedCapabilities(flags: Int): Boolean = flags and advancedCaps != 0

    /** Re-check the actual negotiated dimensions, format and selected fallback decoder. */
    fun candidateMatchesCapabilities(info: MediaCodecInfo, mime: String, height: Int, flags: Int): Boolean =
        runCatching {
            val directSafe = flags and MoonBridge.CAPABILITY_DIRECT_SUBMIT == 0 ||
                (mime == MediaFormat.MIMETYPE_VIDEO_AVC && MediaCodecHelper.decoderCanDirectSubmit(info.name) &&
                    !MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(info.name) &&
                    !MediaCodecHelper.decoderNeedsBaselineSpsHack(info.name) &&
                    !MediaCodecHelper.decoderNeedsConstrainedHighProfile(info.name))
            directSafe && when (mime) {
                MediaFormat.MIMETYPE_VIDEO_AVC ->
                    flags and MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC == 0 ||
                        (MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(info.name, height) &&
                            !MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(info.name) &&
                            !MediaCodecHelper.decoderNeedsBaselineSpsHack(info.name))
                MediaFormat.MIMETYPE_VIDEO_HEVC ->
                    flags and MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC == 0 ||
                        MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(info)
                MediaFormat.MIMETYPE_VIDEO_AV1 ->
                    flags and MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1 == 0 ||
                        MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(info)
                else -> false
            }
        }.getOrDefault(false)

    /** Negotiate conservative capabilities for every offered format, before setup(). */
    fun rendererCapabilities(profile: MoonlightStreamProfile, offered: Int): Int {
        var flags = 0
        var slices: Byte = 1
        val candidates = mutableListOf<MediaCodecInfo>()
        for (choice in codecs) {
            val available = buildList {
                if (offered and choice.sdr != 0) addAll(decoders(choice.mime, profile.width, profile.height, profile.fps))
                if (choice.hdr10 != 0 && offered and choice.hdr10 != 0)
                    addAll(decoders(choice.mime, profile.width, profile.height, profile.fps, hdr10 = true))
            }.distinctBy { it.name }
            if (available.isEmpty()) continue
            candidates.addAll(available)
            if (choice.name != "av1") for (decoder in available) {
                val preferred = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(decoder.name)
                if (preferred > slices) slices = preferred
            }
            val rfi = when (choice.name) {
                "h264" -> available.all { MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(it.name, profile.height) &&
                    !MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(it.name) &&
                    !MediaCodecHelper.decoderNeedsBaselineSpsHack(it.name) }
                "hevc" -> available.all { MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(it) }
                else -> available.all { MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(it) }
            }
            if (rfi) flags = flags or when (choice.name) {
                "h264" -> MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC
                "hevc" -> MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC
                else -> MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1
            }
        }
        // Direct submit is opt-in and restricted to SDR H.264 with no known SPS fixups.
        if (profile.pacing == "latency" && profile.hdrMode == "off" &&
            offered == MoonBridge.VIDEO_FORMAT_H264 && candidates.isNotEmpty() && candidates.all {
                MediaCodecHelper.decoderCanDirectSubmit(it.name) &&
                    !MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(it.name) &&
                    !MediaCodecHelper.decoderNeedsBaselineSpsHack(it.name) &&
                    !MediaCodecHelper.decoderNeedsConstrainedHighProfile(it.name)
            }) flags = flags or MoonBridge.CAPABILITY_DIRECT_SUBMIT
        return flags or MoonBridge.CAPABILITY_SLICES_PER_FRAME(slices)
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
