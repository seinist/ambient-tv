package com.ambient.tv.ui.player.backend

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ShaderProvenanceTest {
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private val manifest = File("../SHADER_SOURCES_027.json").readText()

    private fun entry(key: String) = Regex("""\{[^{}]*"key":\s*"$key"[^{}]*\}""").find(manifest)!!.value
    private fun field(block: String, name: String) = Regex(""""$name":\s*"([^"]+)"""").find(block)!!.groupValues[1]

    @Test fun patchedAdaptiveSharpenMatchesItsManifestHash() {
        val block = entry("adaptive")
        assertEquals(field(block, "sha256"), sha(File("../" + field(block, "file"))))
        assertTrue(field(block, "local_patch").contains("SHIELD"))
    }

    @Test fun cunnyFastDsIsByteIdenticalToTheCurrentUpstreamPin() {
        val block = entry("cunny_fast_ds")
        assertEquals("906031bb00c15dd6a6bbbaa21c0eb0b724ca8437", field(block, "revision"))
        val file = File("../" + field(block, "file"))
        assertEquals(field(block, "sha256"), sha(file))
        assertEquals(file.name, MPV_IMAGE_SHADERS.getValue("cunny_fast_ds").fileName)
    }
}
