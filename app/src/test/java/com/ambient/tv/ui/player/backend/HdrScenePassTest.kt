package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.SvpOptions
import com.ambient.tv.data.local.VlcImageOptions
import com.ambient.tv.ui.player.HdrStandard
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class HdrScenePassTest {
    @get:Rule val folder = TemporaryFolder()

    private fun sei(version: Int = 1, value: Int = 19): ByteArray =
        byteArrayOf(0x4e, 1, 4, 8, 0xb5.toByte(), 0, 0x3c, 0, 1, 4, version.toByte(), value.toByte(), 0x80.toByte())

    @Test fun recognizesAnnexBAndLengthPrefixedRegisteredPayload() {
        val nal = sei()
        val annex = byteArrayOf(0,0,0,1) + nal
        val length = byteArrayOf(0,0,0,nal.size.toByte()) + nal
        val key = HdrScenePlanner.metadata(annex)
        assertNotNull(key)
        assertEquals(key, HdrScenePlanner.metadata(length))
        assertNotEquals(key, HdrScenePlanner.metadata(byteArrayOf(0,0,1)+sei(value=20)))
        assertNull(HdrScenePlanner.metadata(byteArrayOf(0,0,1)+sei(version=0)))
        assertNull(HdrScenePlanner.metadata(length.copyOf(length.size-1)))
        assertNull(HdrScenePlanner.metadata(annex + byteArrayOf(0,0,1)+sei(value=21)))
    }

    private fun samples(): List<HdrScenePlanner.Sample> = (0 until 8).map {
        HdrScenePlanner.Sample(kotlin.math.round(it*1_000_000.0/24).toLong(),if(it<4)"a" else "b")
    }

    @Test fun plansBothRenderersOnPresentationOrderAndExactTickGrid() {
        for (vlc in listOf(false,true)) {
            val p = HdrScenePlanner.plan(samples().reversed(),24.0,60.0,vlc)
            assertNotNull(p)
            assertEquals(listOf(10L,20L),p!!.ends)
            val fraction = HdrScenePlanner.plan((0 until 8).map {
                HdrScenePlanner.Sample(kotlin.math.round(it*1001.0*1_000_000/24000).toLong(),if(it<4)"a" else "b")
            },24000.0/1001,60000.0/1001,vlc)
            assertEquals(listOf(10L,20L),fraction!!.ends)
        }
    }

    @Test fun rejectsAmbiguousOrDiscontinuousInputs() {
        val input = samples()
        assertNull(HdrScenePlanner.plan(input.mapIndexed { i,s -> s.copy(metadata=i.toString()) },24.0,60.0,false))
        assertNull(HdrScenePlanner.plan(input + input.last(),24.0,60.0,false))
        assertNull(HdrScenePlanner.plan(input.mapIndexed { i,s -> if(i>=4)s.copy(ptsUs=s.ptsUs+500_000) else s },24.0,60.0,true))
        assertNull(HdrScenePlanner.plan(input,24.0,24.0,false))
    }

    @Test fun onlyMatchingCompleteCacheCanRequestSingleReplay() {
        val dir = folder.newFolder()
        val file = folder.newFile()
        file.writeText("media")
        val key = "a".repeat(64)
        val manifest = java.io.File(dir,"plan").apply { writeText("plan") }
        val cache = java.io.File(dir,"cache")
        val session = HdrSceneSession(file,file.length(),file.lastModified(),key,
            HdrScenePlanner.Plan(listOf(20),24.0,60.0),manifest,cache)
        assertEquals(1,session.mode)
        assertFalse(session.advance())
        cache.writeText("AMBIENTSVP_SCENE_CACHE_1\n" + "b".repeat(64) + "\n")
        assertFalse(session.advance())
        cache.writeText("AMBIENTSVP_SCENE_CACHE_1\n$key\n")
        assertFalse(session.advance())
        cache.appendText("mpv:fixture\n128 64\n0 20 1 1000 1000 1000 500 100 0 255 200 300 400 500 800 1000 321\n")
        assertTrue(session.advance()) // native pass 2 performs full interval/profile validation
        assertEquals(2,session.mode)
        assertFalse(session.advance())
        assertTrue(session.mpvOptions().contains("hdr-pass=2"))
        file.appendText("changed")
        assertFalse(session.valid())
        session.cleanup()
        assertFalse(dir.exists())
    }

    @Test fun hdr10plusVlcRequiresExplicitOptInAndMetadataAwareRoute() {
        val standard = VlcImageOptions(svpMode="balanced",svp=SvpOptions(hardwareResident=true))
        val enabled = standard.copy(svp=standard.svp.copy(hdrTwoPass=true))
        assertEquals("off",vlcOptionsForNativeHdr(standard,true,HdrStandard.HDR10_PLUS,true).svpMode)
        assertEquals("off",vlcOptionsForNativeHdr(enabled,true,HdrStandard.HDR10_PLUS,false).svpMode)
        assertEquals("balanced",vlcOptionsForNativeHdr(enabled,true,HdrStandard.HDR10_PLUS,true).svpMode)
        val opts = vlcImageMediaOptions(enabled,60.0,true,listOf("hdr-pass=1","hdr-key=abc"))
        assertTrue(opts.any { it.contains("hdr-pass=1") && it.contains("hwresident=1") })
        assertFalse(":mediacodec-svp-vulkan" in opts)
        assertTrue(":mediacodec-svp-vulkan" in vlcImageMediaOptions(standard,60.0,true))
    }
}
