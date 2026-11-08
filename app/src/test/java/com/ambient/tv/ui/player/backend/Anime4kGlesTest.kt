package com.ambient.tv.ui.player.backend

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Anime4kGlesTest {
    @Test fun allFourPinnedModelsKeepTheirWeightsAndHookGeometryWithPortableGlesHooks() {
        val files = File("src/main/res/raw").listFiles()!!.filter { it.name.startsWith("quality_anime4k_") }
        assertEquals(4, files.size)
        for (file in files) {
            val original = file.readText()
            val adapted = anime4kGlesSource(original)
            assertNotEquals(file.name, original, adapted)
            // Preserve the actual trained model and the pass graph, including upscale gates.
            fun modelLines(source: String) = source.lines().filter {
                it.startsWith("//!") || it.contains("mat4(") || it.contains("result += vec4(")
            }
            assertEquals(file.name, modelLines(original), modelLines(adapted))
            val relu = adapted.lines().filter { it.startsWith("#define go_") && "max(" in it }
            assertTrue(file.name, relu.isNotEmpty())
            assertTrue(file.name, relu.all { ", vec4(0.0)" in it })
            // No runtime vector subscript remains in the reconstructed RGB output pass.
            val output = adapted.substringAfterLast("//!DESC")
            assertFalse(file.name, output.contains("["))
            assertFalse(file.name, output.contains("ivec2"))
            assertTrue(file.name, output.contains("return vec4(c0, c1, c2, c3) + MAIN_tex(MAIN_pos);"))
            assertEquals(file.name, adapted, anime4kGlesSource(adapted))
        }
    }
}
