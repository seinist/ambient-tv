package com.ambient.tv.ui.player.backend

import com.ambient.tv.R
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MpvImageShaderCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun raw(resourceId: Int?, resourceName: String?): String {
        val name = when (resourceId) {
            R.raw.quality_cas_a945e104 -> "quality_cas_a945e104"
            R.raw.quality_cas_hdr -> "quality_cas_hdr"
            R.raw.quality_adaptive_44e81f12 -> "quality_adaptive_44e81f12"
            R.raw.quality_ravu_lite_r2_cbbabe0a -> "quality_ravu_lite_r2_cbbabe0a"
            R.raw.quality_filmgrain_light_4aee719a -> "quality_filmgrain_light_4aee719a"
            R.raw.quality_anime4k_4c53ec2e -> "quality_anime4k_4c53ec2e"
            R.raw.quality_anime4k_denoise_1a45ad3a -> "quality_anime4k_denoise_1a45ad3a"
            R.raw.quality_anime4k_m_9b8e956d -> "quality_anime4k_m_9b8e956d"
            R.raw.quality_anime4k_denoise_m_967804d7 -> "quality_anime4k_denoise_m_967804d7"
            else -> requireNotNull(resourceName) { "Unknown test resource $resourceId" }
        }
        return File("src/main/res/raw/$name.glsl").readText()
    }

    private fun <T> onIo(work: () -> T): T {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "mpv-shader-cache-test-io") }
        return try {
            executor.submit(Callable { work() }).get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun capturedSdrAndHdrProfilesMaterializeAllCasStrengthsOnIo() {
        val ownerThread = Thread.currentThread()
        // Only this owner-side capture knows the source's transfer and range.
        val profiles = listOf(
            "bt.1886" to "limited", "gamma2.2" to "full",
            "pq" to "limited", "pq" to "full", "hlg" to "limited", "hlg" to "full",
        ).map { (transfer, range) -> requireNotNull(mpvCasTransferProfile(transfer, range)) }
        val requestedResources = mutableListOf<Int?>()
        val cache = MpvImageShaderCache(temporary.newFolder("shaders")) { resourceId, resourceName ->
            assertNotSame(ownerThread, Thread.currentThread())
            requestedResources += resourceId
            raw(resourceId, resourceName)
        }
        val files = onIo {
            profiles.flatMap { profile ->
                listOf("cas", "cas_light", "cas_medium").map { key ->
                    val shader = MPV_IMAGE_SHADERS.getValue(key)
                    val file = cache.materialize(shader, profile, gpuNextVulkan = true)
                    assertTrue(file.isFile)
                    assertEquals(shader.cacheFileName!!.removeSuffix(".glsl") + "_${profile.suffix}.glsl", file.name)
                    assertTrue("$key / ${profile.suffix}", mpvCasSourceValid(file.readText(), profile, shader.casBlend))
                    file
                }
            }
        }
        assertEquals(profiles.size * 3, files.toSet().size)
        assertEquals(profiles.flatMap { profile -> List(3) {
            if (profile.singlePassHdr) R.raw.quality_cas_hdr else R.raw.quality_cas_a945e104
        } }, requestedResources)
    }

    @Test fun ioUsesTheCapturedProfileWithoutReadingChangedPlayerMetadata() {
        val ownerThread = Thread.currentThread()
        var sourceTransfer = "pq"
        var sourceRange = "limited"
        var profileReads = 0
        fun readPlayerProfile(): MpvCasTransferProfile {
            check(Thread.currentThread() === ownerThread)
            profileReads++
            return requireNotNull(mpvCasTransferProfile(sourceTransfer, sourceRange))
        }
        val captured = readPlayerProfile()
        sourceTransfer = "hlg"
        sourceRange = "full"
        val cache = MpvImageShaderCache(temporary.newFolder("shaders"), ::raw)
        val shader = MPV_IMAGE_SHADERS.getValue("cas")
        val file = onIo { cache.materialize(shader, captured, gpuNextVulkan = false) }

        assertEquals(1, profileReads)
        assertTrue(mpvCasSourceValid(file.readText(), captured, null))
        assertFalse(mpvCasSourceValid(file.readText(), mpvCasTransferProfile(sourceTransfer, sourceRange)!!, null))
    }

    @Test fun missingOrUnknownTransferIsRejectedBeforeReadingResourceBytes() {
        val directory = temporary.newFolder("shaders")
        var resourceReads = 0
        val cache = MpvImageShaderCache(directory) { id, name -> resourceReads++; raw(id, name) }
        for (key in listOf("cas", "cas_light", "cas_medium")) {
            for (profile in listOf(null, mpvCasTransferProfile("unknown"))) {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    cache.materialize(MPV_IMAGE_SHADERS.getValue(key), profile, gpuNextVulkan = false)
                }
                assertTrue(error.message!!.contains("captured transfer profile"))
            }
        }
        assertEquals(0, resourceReads)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun adaptiveNeedsNoTransferProfileAndNeverUsesTheHdrCasSource() {
        val shader = MPV_IMAGE_SHADERS.getValue("adaptive")
        val cache = MpvImageShaderCache(temporary.newFolder("shaders")) { id, name ->
            assertEquals(R.raw.quality_adaptive_44e81f12, id)
            raw(id, name)
        }
        onIo {
            for (profile in listOf(null, mpvCasTransferProfile("pq", "full"))) {
                val file = cache.materialize(shader, profile, gpuNextVulkan = false)
                assertEquals(shader.fileName, file.name)
                assertEquals(raw(shader.resourceId, shader.resourceName), file.readText())
            }
        }
    }

    @Test fun aSharedHdrProfileOnlyAffectsTheCasShaderInAMixedChain() {
        val profile = mpvCasTransferProfile("pq", "full")!!
        val ravu = MPV_IMAGE_SHADERS.getValue("ravu_lite_r2")
        val cas = MPV_IMAGE_SHADERS.getValue("cas_medium")
        val grain = MPV_IMAGE_SHADERS.getValue("filmgrain_light_output")
        val requestedResources = mutableListOf<Int?>()
        val cache = MpvImageShaderCache(temporary.newFolder("shaders")) { id, name ->
            requestedResources += id
            raw(id, name)
        }
        val files = onIo {
            listOf(ravu, cas, grain).map { cache.materialize(it, profile, gpuNextVulkan = true) }
        }

        assertEquals(listOf(ravu.resourceId, R.raw.quality_cas_hdr, grain.resourceId), requestedResources)
        assertEquals(listOf(ravu.fileName, "quality_cas_939ed74a_b80_pq1p_full.glsl", grain.cacheFileName), files.map { it.name })
        assertEquals(raw(ravu.resourceId, ravu.resourceName), files[0].readText())
        assertTrue(mpvCasSourceValid(files[1].readText(), profile, cas.casBlend))
        assertEquals(mpvOutputGrainSource(raw(grain.resourceId, grain.resourceName)), files[2].readText())
    }

    @Test fun cachedCasWithTheWrongRangeIsReplacedWithoutChangingItsPath() {
        val cache = MpvImageShaderCache(temporary.newFolder("shaders"), ::raw)
        val shader = MPV_IMAGE_SHADERS.getValue("cas_light")
        val limited = mpvCasTransferProfile("pq", "limited")!!
        val full = mpvCasTransferProfile("pq", "full")!!
        val file = cache.materialize(shader, limited, gpuNextVulkan = true)
        val correctSource = file.readText()
        file.writeText(cache.materialize(shader, full, gpuNextVulkan = true).readText())
        assertFalse(mpvCasSourceValid(file.readText(), limited, shader.casBlend))

        val repaired = cache.materialize(shader, limited, gpuNextVulkan = true)
        assertEquals(file, repaired)
        assertEquals(correctSource, repaired.readText())
        assertTrue(mpvCasSourceValid(repaired.readText(), limited, shader.casBlend))
    }

    @Test fun unchangedCacheIsKeptButAnUpdatedBundledShaderReplacesIt() {
        val shader = MPV_IMAGE_SHADERS.getValue("cas")
        val profile = mpvCasTransferProfile("bt.1886")!!
        var bundledSource = raw(shader.resourceId, shader.resourceName)
        val cache = MpvImageShaderCache(temporary.newFolder("shaders")) { _, _ -> bundledSource }
        val file = cache.materialize(shader, profile, gpuNextVulkan = false)
        val markerTime = 1_600_000_000_000L
        assertTrue(file.setLastModified(markerTime))
        assertEquals(file, cache.materialize(shader, profile, gpuNextVulkan = false))
        assertEquals(markerTime, file.lastModified())

        bundledSource += "\n// Updated bundled shader revision.\n"
        assertEquals(file, cache.materialize(shader, profile, gpuNextVulkan = false))
        assertEquals(bundledSource, file.readText())
    }

    @Test fun oldAnime4kCacheIsUpgradedForBothGpuModesWithoutChangingItsPath() {
        for (gpuNextVulkan in listOf(false, true)) {
            val directory = temporary.newFolder("anime-$gpuNextVulkan")
            val cache = MpvImageShaderCache(directory, ::raw)
            for (key in listOf("anime4k", "anime4k_denoise", "anime4k_m", "anime4k_denoise_m")) {
                val shader = MPV_IMAGE_SHADERS.getValue(key)
                val original = raw(shader.resourceId, shader.resourceName)
                val oldFile = File(directory, shader.fileName).apply { writeText(original) }

                val upgraded = cache.materialize(shader, null, gpuNextVulkan)
                assertEquals(oldFile, upgraded)
                assertNotEquals(original, upgraded.readText())
                assertEquals(anime4kGlesSource(original), upgraded.readText())
                val markerTime = 1_600_000_000_000L
                assertTrue(upgraded.setLastModified(markerTime))
                assertEquals(upgraded, cache.materialize(shader, null, gpuNextVulkan))
                assertEquals(markerTime, upgraded.lastModified())
            }
            assertFalse(directory.listFiles()!!.any { it.name.endsWith(".pending") })
        }
    }

    @Test fun mismatchedBundledProfileIsRejectedAndItsStagingFileIsRemoved() {
        val directory = temporary.newFolder("shaders")
        val shader = MPV_IMAGE_SHADERS.getValue("cas")
        val cache = MpvImageShaderCache(directory) { id, name ->
            raw(id, name).replace("#define SOURCE_TRC 4", "#define SOURCE_TRC 99")
        }
        assertThrows(IllegalStateException::class.java) {
            cache.materialize(shader, mpvCasTransferProfile("bt.1886"), gpuNextVulkan = false)
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
