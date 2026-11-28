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
        assertNotNull(HdrScenePlanner.metadata(byteArrayOf(0,0,1)+sei(version=0)))
        assertNull(HdrScenePlanner.metadata(byteArrayOf(0,0,1)+sei(version=2)))
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

    @Test fun detectsHardCutsWithIdenticalMetadataAndIgnoresSingleFrameFlash() {
        fun f(pts:Long,luma:Int) = HdrContentSceneDetector.Signature(pts,ByteArray(HdrContentSceneDetector.CELLS){luma.toByte()})
        assertEquals(setOf(2L),HdrContentSceneDetector.cuts(listOf(f(0,20),f(1,21),f(2,220),f(3,221))))
        assertEquals(emptySet<Long>(),HdrContentSceneDetector.cuts(listOf(f(0,20),f(1,220),f(2,20),f(3,21))))
        assertNull(HdrContentSceneDetector.cuts(listOf(f(0,20),f(0,21))))
        val same = samples().map { it.copy(metadata="unchanged") }
        val p = HdrScenePlanner.plan(same,24.0,60.0,false,setOf(same[4].ptsUs))
        assertEquals(listOf(10L,20L),p!!.ends)
        val varying = same.mapIndexed { i,s -> s.copy(metadata=i.toString()) }
        assertEquals(listOf(10L,20L),HdrScenePlanner.plan(varying,24.0,60.0,true,setOf(same[4].ptsUs))!!.ends)
        assertNull(HdrScenePlanner.plan(same,24.0,60.0,true,setOf(-1L)))
    }

    @Test fun uhdSceneModeRemainsExplicitAndBounded() {
        val standard = SvpOptions(hardwareResident=true,maxHeight=2160)
        assertFalse(svpResidentRequested(standard,3840,2160))
        assertTrue(svpResidentRequested(standard.copy(hdrTwoPass=true),3840,2160))
        assertFalse(svpResidentRequested(standard.copy(hdrTwoPass=true),4096,2160))
        assertFalse(svpResidentRequested(standard.copy(hdrTwoPass=true,maxHeight=1080),3840,2160))
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

    @Test fun legacyCacheHasIndependentWindowSchemasAndZeroFraction() {
        val dir=folder.newFolder()
        val media=folder.newFile().apply { writeText("media") }
        val manifest=java.io.File(dir,"plan").apply { writeText("plan") }
        val cache=java.io.File(dir,"cache")
        val key="b".repeat(64)
        val session=HdrSceneSession(media,media.length(),media.lastModified(),key,
            HdrScenePlanner.Plan(listOf(20),24.0,60.0),manifest,cache,version=0)
        val header="AMBIENTSVP_SCENE_CACHE_0\n$key\nmpv:fixture\n128 64\n"
        cache.writeText(header+"0 20 2 1 400 400 400 200 0 99 400 2 300 300 300 100 1 50 100 99 300\n")
        assertFalse(session.advance())
        cache.writeText(header+"0 20 2 1 400 400 400 200 0 99 400 2 300 300 300 100 0 50 100 99 300\n")
        assertTrue(session.advance())
        session.cleanup()
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
