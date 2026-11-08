package com.ambient.tv.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3TextureSlotsTest {
    @Test fun pinnedFsrcnnxModelsKeepResidualInputsAndReuseOverwrittenModelValues() {
        val files = rawFiles("quality_fsrcnnx")
        assertEquals(2, files.size)
        for (file in files) {
            val passes = requireNotNull(media3FsrcnnxPasses(file.readText())).map {
                Media3TexturePass(it.bindings, it.save)
            }
            val plan = media3TextureSlotPlan(passes)
            assertEquals(file.name, if (file.name.startsWith("quality_fsrcnnx16_")) 12 else 6,
                plan.slotCount)
            assertInputsRemainIntact(file.name, passes, plan)
        }
    }

    @Test fun pinnedAnimeSmallModelsPingPongWhileMediumModelsKeepAllSevenSkipInputs() {
        val files = rawFiles("quality_anime4k_")
        assertEquals(4, files.size)
        for (file in files) {
            val passes = requireNotNull(media3Anime4kPasses(file.readText())).map {
                Media3TexturePass(it.bindings, it.save.takeUnless { name -> name == "MAIN" })
            }
            val plan = media3TextureSlotPlan(passes)
            assertEquals(file.name, if (passes.size == 9) 8 else 2, plan.slotCount)
            assertInputsRemainIntact(file.name, passes, plan)
        }
    }

    /** Simulate writes to physical slots and check each logical input before its draw. */
    private fun assertInputsRemainIntact(
        model: String, passes: List<Media3TexturePass>, plan: Media3TextureSlotPlan,
    ) {
        val contents = IntArray(plan.slotCount) { -1 }
        val producers = mutableMapOf<String, Int>()
        passes.forEachIndexed { index, pass ->
            val output = plan.slotByPass[index]
            pass.inputs.forEach { name ->
                producers[name]?.let { producer ->
                    val input = plan.slotByPass[producer]
                    assertEquals("$model pass $index overwrote $name before its last read",
                        producer, contents[input])
                    assertTrue("$model pass $index samples its output texture", input != output)
                }
            }
            if (pass.output == null) {
                assertEquals("$model final target must stay separate", -1, output)
            } else {
                assertTrue("$model pass $index needs a feature target", output in contents.indices)
                contents[output] = index
                producers[pass.output] = index
            }
        }
    }

    private fun rawFiles(prefix: String): List<File> =
        File("src/main/res/raw").listFiles()!!.filter { it.name.startsWith(prefix) }
}
