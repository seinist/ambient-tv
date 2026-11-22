package com.ambient.tv.games

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameShaderPackArchiveTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
    private val fragment = """
        precision highp float;
        uniform sampler2D texture;
        varying highp vec2 coords;
        void main() { gl_FragColor = texture2D(texture, coords); }
    """.trimIndent().toByteArray()

    private fun signedZip(
        fragmentBytes: ByteArray = fragment,
        filename: String = "shaders/pixel.frag",
        signedHash: String = GameShaderPackArchive.digest(fragment),
        signed: Boolean = true,
        add: Pair<String, ByteArray>? = null,
    ): ByteArray {
        val metadata = GameShaderPackManifest(
            id = "sample",
            title = "Sample",
            presets = listOf(GameShaderPackPreset(
                id = "pixel", title = "Pixel", category = "scaling",
                sha256 = signedHash, license = "MIT", sourceUrl = "https://example.org/source",
            )),
        )
        val manifest = Json.encodeToString(metadata).toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(if (signed) manifest else "unrelated".toByteArray())
            sign()
        }
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                fun entry(name: String, data: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
                entry("manifest.json", manifest)
                entry("manifest.sig", sig)
                entry(filename, fragmentBytes)
                if (add != null) entry(add.first, add.second)
            }
        }.toByteArray()
    }

    private fun verify(bytes: ByteArray) =
        GameShaderPackArchive.verify(ByteArrayInputStream(bytes), publicKey)

    @Test fun acceptsVettedSourceAndPinnedSignature() {
        val verified = verify(signedZip())
        assertEquals("sample", verified.manifest.id)
        assertEquals("pixel", verified.manifest.presets.single().id)
        assertEquals(fragment.toList(), verified.shaderSources.values.single().toList())
    }

    @Test fun rejectsUntrustedOrTamperedShaderSources() {
        assertThrows(IOException::class.java) { verify(signedZip(signed = false)) }
        assertThrows(IOException::class.java) { verify(signedZip(fragmentBytes = fragment + byteArrayOf(1))) }
        assertThrows(IOException::class.java) { verify(signedZip(signedHash = "0".repeat(64))) }
    }

    @Test fun blocksTraversalUnknownFilesAndOversizedAssets() {
        assertThrows(IOException::class.java) { verify(signedZip(filename = "../escape.frag")) }
        assertThrows(IOException::class.java) { verify(signedZip(filename = "shaders/../pixel.frag")) }
        assertThrows(IOException::class.java) {
            verify(signedZip(add = "unlisted.frag" to fragment))
        }
        assertThrows(IOException::class.java) {
            verify(signedZip(fragmentBytes = ByteArray(16 * 1024 + 1) { 88 }))
        }
        assertTrue(GameShaderPackArchive.MAX_ARCHIVE_BYTES < 1024 * 1024)
    }

    @Test fun missingTrustedKeyFailsClosed() {
        assertThrows(IOException::class.java) {
            GameShaderPackArchive.verify(ByteArrayInputStream(signedZip()), "")
        }
    }
}
