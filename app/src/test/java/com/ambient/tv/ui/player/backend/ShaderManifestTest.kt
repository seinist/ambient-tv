package com.ambient.tv.ui.player.backend

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Every pinned upstream shader in the manifest is bundled byte-for-byte and registered. */
class ShaderManifestTest {
    private val manifest = File("../SHADER_SOURCES_027.json").readText()
    private val entries = Regex("""\{[^{}]*"key":\s*"([^"]+)"[^{}]*\}""").findAll(manifest).map { it.groupValues[1] to it.value }.toList()

    private fun field(block: String, name: String) = Regex(""""$name":\s*"([^"]+)"""").find(block)?.groupValues?.get(1)

    @Test fun pinnedFilesMatchTheirHashAndRegistry() {
        assertTrue(entries.isNotEmpty())
        for ((key, block) in entries) {
            // adaptive-sharpen carries a documented local patch on top of its upstream hash.
            if (field(block, "local_patch") != null) continue
            val file = File("../" + field(block, "file")!!)
            assertTrue(key, file.isFile)
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(key, field(block, "sha256"), sha)
            assertEquals(key, file.name, MPV_IMAGE_SHADERS.getValue(key).fileName)
            assertTrue(key, File("../third_party_licenses/image_shaders/$key-notice.txt").isFile)
        }
    }

    @Test fun artCnnFamilyIsPinnedToOneRevision() {
        val revisions = entries.filter { it.first.startsWith("artcnn") }.map { field(it.second, "revision") }.toSet()
        assertEquals(setOf("8789539dfae120f2877a89bb6218026d56809612"), revisions)
    }
}
