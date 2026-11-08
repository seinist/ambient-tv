package com.ambient.tv.ui.player

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3ComputeDispatchTest {
    @Test fun linkedUniformLocationsAreResolvedOnceAndKeepOptimizedOutValues() {
        val queried = mutableListOf<String>()
        val locations = mapOf("uLUMA" to 4, "uFEATURE" to 7,
            "uSize_LUMA" to -1, "uSize_FEATURE" to 9, "uOutputSize" to -1)
        val uniforms = Media3ComputeUniformLocations(listOf("LUMA", "FEATURE")) { name ->
            queried += name
            locations.getValue(name)
        }
        repeat(3) {
            assertArrayEquals(intArrayOf(4, 7), uniforms.samplers)
            assertArrayEquals(intArrayOf(-1, 9), uniforms.inputSizes)
            assertEquals(-1, uniforms.outputSize)
        }
        assertEquals(locations.keys, queried.toSet())
        assertEquals(locations.size, queried.size)
    }

    @Test fun allPinnedModelsResolveFreshTexturesAndExactDispatchesAcrossOddResize() {
        val files = File("src/main/res/raw").listFiles()!!.filter {
            it.name.startsWith("quality_artcnn") || it.name.startsWith("quality_cunny_")
        }
        assertEquals(MEDIA3_NEURAL_COMPUTE_SCALERS.size, files.size)
        for (file in files) {
            val key = file.nameWithoutExtension.removePrefix("quality_").substringBeforeLast("_")
            val passes = requireNotNull(media3ComputePasses(key, file.readText()))
            val previousTextures = mutableSetOf<Int>()
            val snapshots = mutableListOf<Pair<Array<Media3ComputeDispatch>, List<List<Int>>>>()
            for ((generation, dimensions) in listOf(16 to 12, 19 to 13, 16 to 12).withIndex()) {
                val (width, height) = dimensions
                var nextTexture = generation * 1000 + 1
                val textures = mutableMapOf("LUMA" to Media3ComputeTexture(nextTexture++, width, height))
                var final: Media3ComputeTexture? = null
                for (pass in passes) {
                    val texture = Media3ComputeTexture(nextTexture++,
                        width * pass.widthMultiplier, height * pass.heightMultiplier)
                    if (pass.save != null) textures[pass.save] = texture else final = texture
                }
                val finalTexture = requireNotNull(final)
                val dispatches = media3ComputeDispatches(passes, textures, finalTexture)
                assertEquals(file.name, passes.size, dispatches.size)
                dispatches.forEachIndexed { index, dispatch ->
                    val pass = passes[index]
                    assertEquals(file.name, pass.bindings.map(textures::getValue), dispatch.inputs.toList())
                    assertEquals(file.name, pass.save?.let(textures::getValue) ?: finalTexture, dispatch.output)
                    val outWidth = width * pass.widthMultiplier
                    val outHeight = height * pass.heightMultiplier
                    assertEquals(outWidth, dispatch.output.width)
                    assertEquals(outHeight, dispatch.output.height)
                    assertEquals((outWidth + pass.tileWidth - 1) / pass.tileWidth, dispatch.groupsX)
                    assertEquals((outHeight + pass.tileHeight - 1) / pass.tileHeight, dispatch.groupsY)
                    assertTrue(file.name, dispatch.inputs.none { it.texture in previousTextures })
                    assertTrue(file.name, dispatch.output.texture !in previousTextures)
                }
                previousTextures += textures.values.map { it.texture }
                previousTextures += finalTexture.texture
                // The frame plan must keep resolved IDs, independent of the setup map's lifetime.
                val snapshot = textureIds(dispatches)
                snapshots += dispatches to snapshot
                textures.clear()
                snapshots.forEach { (plan, ids) -> assertEquals(ids, textureIds(plan)) }
            }
        }
    }

    private fun textureIds(dispatches: Array<Media3ComputeDispatch>): List<List<Int>> =
        dispatches.map { it.inputs.map(Media3ComputeTexture::texture) + it.output.texture }
}
