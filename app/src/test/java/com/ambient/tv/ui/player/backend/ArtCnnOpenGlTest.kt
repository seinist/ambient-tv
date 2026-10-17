package com.ambient.tv.ui.player.backend

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ArtCnnOpenGlTest {
    @Test fun allPinnedKernelsRetainWeightsAndSharedArrayConstants() {
        val files = File("src/main/res/raw").listFiles()!!.filter { it.name.startsWith("quality_artcnn") }
        assertEquals(6, files.size)
        for (file in files) {
            val original = file.readText()
            val adapted = artCnnOpenGlSource(original)
            assertFalse(file.name, adapted.contains("#extension"))
            assertFalse(file.name, adapted.contains("float16_t"))
            assertFalse(file.name, Regex("(?m)^[ \\t]+const ").containsMatchIn(adapted))
            assertFalse(file.name, Regex("\\b(uint|uvec2)\\b").containsMatchIn(adapted))
            assertTrue(file.name, adapted.contains("ivec2 local_xy = ivec2(gl_LocalInvocationID.xy);"))
            val passes = adapted.split("//!DESC").drop(1)
            assertTrue(file.name, passes.last().contains("//!COMPONENTS 1"))
            assertTrue(file.name, passes.dropLast(1).all { it.contains("//!COMPONENTS 4") })
            // Only declaration/precision adaptation is allowed; weights and hook layout stay intact.
            val preserved = original.lines().filter {
                it.startsWith("const ") || it.startsWith("shared ") || it.startsWith("//!") ||
                    it.contains("M4(") || it.contains("barrier()")
            }
            assertTrue(file.name, preserved.isNotEmpty())
            for (line in preserved) assertTrue("${file.name}: $line", line in adapted)
            assertEquals(adapted, artCnnOpenGlSource(adapted))
        }
    }
}
