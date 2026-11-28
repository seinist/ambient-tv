package com.ambient.tv.modules

import com.ambient.tv.ui.games.RetroCorePort
import com.ambient.tv.ui.games.RetroFrontendPort
import com.ambient.tv.ui.games.RetroViewPort
import com.ambient.tv.ui.player.backend.*
import org.junit.Assert.*
import org.junit.Test

class HostModulePortTest {
    @Test fun noVendorTypesCrossHostPorts() {
        val ports = listOf(VlcEnginePort::class.java, VlcLibraryPort::class.java,
            VlcPlayerPort::class.java, VlcMediaPort::class.java, VlcOutputPort::class.java,
            VlcOutputPort.Callback::class.java, VlcTracksPort::class.java, RetroCorePort::class.java,
            RetroFrontendPort::class.java, RetroViewPort::class.java)
        ports.flatMap { it.methods.toList() }.forEach { method ->
            val signature = method.toGenericString()
            assertFalse(signature, signature.contains("org.videolan.libvlc"))
            assertFalse(signature, signature.contains("com.swordfish.libretrodroid"))
        }
    }

    @Test fun nativeTrackSnapshotsKeepCodecAndGeometryWithoutVendorObjects() {
        val video = VlcVideoTrack(1920, 1080, 24000, 1001, 1, 1, 0, 0, 5000000, "h264")
        assertEquals(1920, video.width)
        assertEquals(23.976, video.frameRateNum.toDouble() / video.frameRateDen, 0.001)
        assertEquals("h264", video.codec)
        val audio = VlcAudioTrack(6, 48000, 384000, "ac3")
        assertEquals(6, audio.channels)
        assertEquals("ac3", audio.codec)
    }
}
