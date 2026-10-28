@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Test

class Media3ImageProcessingPolicyTest {
    @Test fun shieldAndroid11BypassesBrokenGpuSink() {
        assertFalse(media3GpuEffectsSupported("NVIDIA", 30))
        assertFalse(media3GpuEffectsSupported("nvidia", 28))
    }

    @Test fun shieldWorkaroundDoesNotDisableOtherDrivers() {
        assertTrue(media3GpuEffectsSupported("Google", 30))
        assertTrue(media3GpuEffectsSupported("NVIDIA", 31))
    }

    @Test fun missingTrackWaitsForMetadata() { assertFalse(media3ImageEffectsAllowed(null)) }

    @Test fun unsignalledColorIsSdrOnlyForProfilesWithoutHdr() {
        assertTrue(media3ImageEffectsAllowed(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()))
        assertTrue(media3ImageEffectsAllowed(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.1.6.L120.90").build()))
        assertFalse(media3ImageEffectsAllowed(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").build()))
        assertFalse(media3ImageEffectsAllowed(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()))
        assertTrue(media3UnsignalledIsSdr(MimeTypes.VIDEO_AV1, "av01.0.08M.08"))
        assertFalse(media3UnsignalledIsSdr(MimeTypes.VIDEO_AV1, "av01.0.08M.10"))
        assertTrue(media3UnsignalledIsSdr(MimeTypes.VIDEO_VP9, "vp09.00.41.08"))
        assertFalse(media3UnsignalledIsSdr(MimeTypes.VIDEO_VP9, "vp09.02.41.10"))
        // Signalled HDR is never overridden by the codec.
        assertFalse(media3ImageEffectsAllowed(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
            .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_HLG).build()).build()))
    }

    @Test fun onlyExplicitSdrColorIsAccepted() {
        assertTrue(media3ImageEffectsAllowed(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
            .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_SDR).build()).build()))
    }

    @Test fun pqHlgAndDolbyVisionAreRejected() {
        listOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG).forEach { transfer ->
            assertFalse(media3ImageEffectsAllowed(Format.Builder()
                .setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build()).build()))
        }
        assertFalse(media3ImageEffectsAllowed(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build()))
        assertFalse(media3ImageEffectsAllowed(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("dvh1.08.06").build()))
    }

    @Test fun protectedSdrAlsoBypassesEffects() {
        assertFalse(media3ImageEffectsAllowed(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
            .setDrmInitData(DrmInitData(emptyList())).build()))
    }
}
