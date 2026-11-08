package com.ambient.tv.ui.player.backend

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CunnyOpenGlTest {
    @Test fun allFiveKernelsUseTheirFloat32FallbackWithoutChangingTheNetwork() {
        val files = File("src/main/res/raw").listFiles()!!.filter {
            it.name.startsWith("quality_cunny_") && !it.name.contains("_q_")
        }
        assertEquals(5, files.size)
        for (file in files) {
            val original = file.readText()
            val adapted = cunnyOpenGlSource(original)
            assertFalse(file.name, adapted.contains("#extension"))
            assertFalse(file.name, adapted.contains("float16_t"))
            assertFalse(file.name, adapted.contains("f16vec4"))
            assertEquals(file.name, 4, adapted.lines().count { it.startsWith("//!COMPUTE ") })
            assertTrue(file.name, adapted.contains("#\tdefine V4 vec4"))
            assertTrue(file.name, adapted.contains("#\tdefine M4 mat4"))
            assertTrue(file.name, adapted.contains("#\tdefine F float"))
            // Compare every non-precision line, including all weights, image writes and barriers.
            assertEquals(file.name, networkLines(original), networkLines(adapted))
            assertEquals(file.name, adapted, cunnyOpenGlSource(adapted))
        }
    }

    @Test fun quantizedKernelsKeepTheirVulkanRequirementAndOriginalSource() {
        for (key in listOf("cunny_fast_soft_q", "cunny_faster_soft_q", "cunny_veryfast_soft_q")) {
            val shader = MPV_IMAGE_SHADERS.getValue(key)
            val source = File("src/main/res/raw/${shader.fileName}").readText()
            assertTrue(key, shader.requiresGpuNext)
            assertNull(key, mpvOpenGlShaderAdapter(shader))
            assertEquals(key, source, mpvShaderSourceFor(shader, source, null, openGl = false))
        }
    }

    private fun networkLines(source: String): List<String> = source.lines().filterNot { line ->
        line.isBlank() || line.startsWith("#extension ") ||
            line.startsWith("#ifdef GL_EXT_shader_explicit_arithmetic_types_float16") ||
            line == "#else" || line == "#endif" ||
            Regex("""#\s*define (V4|M4|F) """).containsMatchIn(line)
    }
}
