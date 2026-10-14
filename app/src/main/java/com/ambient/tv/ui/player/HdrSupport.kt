@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.core.content.getSystemService

data class HdrOutputAssessment(
    val source: HdrStandard?,
    val displayStandards: Set<HdrStandard>,
    /** What the display can actually show: the source itself, its HDR10/HLG base layer, or null. */
    val output: HdrStandard? = hdrOutputStandard(source, null, null, displayStandards),
) {
    val displaySupportsSource: Boolean get() = source == null || output != null
}

/** Display and selected-format HDR negotiation used by every renderer and diagnostics panel. */
object HdrSupport {

    fun displayStandards(context: Context): Set<HdrStandard> {
        val displayManager = context.getSystemService<DisplayManager>() ?: return emptySet()
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptySet()
        val types = enabledHdrTypes(display)
        return buildSet {
            if (Display.HdrCapabilities.HDR_TYPE_HDR10 in types) add(HdrStandard.HDR10)
            if (Display.HdrCapabilities.HDR_TYPE_HLG in types) add(HdrStandard.HLG)
            if (Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types) add(HdrStandard.HDR10_PLUS)
            if (Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types) add(HdrStandard.DOLBY_VISION)
        }
    }

    /**
     * HDR types of the current mode minus those the user switched off (Settings → Display → HDR
     * formats) and none at all under "force SDR". `Display.Mode.supportedHdrTypes` ignores both,
     * `getHdrCapabilities()` applies them, so it stays the source even on API 34+.
     */
    fun enabledHdrTypes(display: Display): Set<Int> =
        @Suppress("DEPRECATION") display.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()

    fun isDisplayHdrCapable(context: Context): Boolean {
        return displayStandards(context).isNotEmpty()
    }

    fun assess(context: Context, format: Format?): HdrOutputAssessment {
        val source = sourceStandard(format)
        val display = displayStandards(context)
        val profile = dolbyVisionProfile(format?.codecs)
        val base = transferStandard(format?.colorInfo?.colorTransfer)
        return HdrOutputAssessment(source, display, hdrOutputStandard(source, profile, base, display))
    }

    fun assess(context: Context, codec: String?, dynamicRange: String?): HdrOutputAssessment {
        val source = sourceStandard(codec, dynamicRange)
        val display = displayStandards(context)
        val profile = dolbyVisionProfile(codec)
        val base = if (source == HdrStandard.DOLBY_VISION) dolbyVisionBaseLayer(dynamicRange) else null
        return HdrOutputAssessment(source, display, hdrOutputStandard(source, profile, base, display))
    }

    private fun transferStandard(transfer: Int?): HdrStandard? = when (transfer) {
        C.COLOR_TRANSFER_ST2084 -> HdrStandard.HDR10
        C.COLOR_TRANSFER_HLG -> HdrStandard.HLG
        else -> null
    }

    fun sourceStandard(format: Format?): HdrStandard? {
        if (format == null) return null
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
            format.codecs.orEmpty().split(',').any { it.trim().startsWith("dvhe", true) || it.trim().startsWith("dvh1", true) }) {
            return HdrStandard.DOLBY_VISION
        }
        return when (format.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> HdrStandard.HDR10
            C.COLOR_TRANSFER_HLG -> HdrStandard.HLG
            else -> null
        }
    }


    fun sourceStandard(codec: String?, dynamicRange: String?): HdrStandard? {
        val value = listOfNotNull(codec, dynamicRange).joinToString(" ").lowercase()
        return when {
            value.contains("dolby vision") || value.contains("dolbyvision") ||
                value.contains("dovi") || value.contains("dvhe") -> HdrStandard.DOLBY_VISION
            value.contains("hdr10+") || value.contains("hdr10plus") -> HdrStandard.HDR10_PLUS
            value.contains("hlg") -> HdrStandard.HLG
            value.contains("hdr") -> HdrStandard.HDR10
            else -> null
        }
    }
}
