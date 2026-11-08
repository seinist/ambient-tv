package com.ambient.tv.ui.player.backend

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FsrcnnxGlesTest {
    @Test fun bothPinnedModelsKeepTheirNetworkAndUsePortableAggregation() {
        val files = File("src/main/res/raw").listFiles()!!.filter { it.name.startsWith("quality_fsrcnnx") }
        assertEquals(2, files.size)
        for (file in files) {
            val original = file.readText()
            val adapted = fsrcnnxGlesSource(original)
            assertNotEquals(file.name, original, adapted)
            // Everything before the final output stage is the original trained network.
            assertEquals(file.name, original.substringBeforeLast("//!HOOK LUMA"),
                adapted.substringBeforeLast("//!HOOK LUMA"))
            assertEquals(file.name, original.lines().filter { it.startsWith("//!") },
                adapted.lines().filter { it.startsWith("//!") })
            val output = adapted.substringAfterLast("//!HOOK LUMA")
            assertFalse(file.name, output.contains("["))
            assertFalse(file.name, output.contains("ivec2 index"))
            assertTrue(file.name, output.contains("vec4 res = SUBCONV1_tex(base);"))
            assertEquals(file.name, adapted, fsrcnnxGlesSource(adapted))
        }
    }
}
