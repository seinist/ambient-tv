package com.ambient.tv.update

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DexMetadataTest {
    @get:Rule val folder = TemporaryFolder()

    private fun apk(vararg entries: Pair<String, ByteArray>): File = folder.newFile("app.apk").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) }
        }
    }

    @Test fun profileAndMetadataBecomePrimaryEntries() {
        val file = apk("classes.dex" to byteArrayOf(1), "assets/dexopt/baseline.prof" to byteArrayOf(2, 3), "assets/dexopt/baseline.profm" to byteArrayOf(4))
        val out = ByteArrayOutputStream()
        assertTrue(writeDexMetadata(file, out))
        val dm = entries(out.toByteArray())
        assertEquals(setOf("primary.prof", "primary.profm"), dm.keys)
        assertArrayEquals(byteArrayOf(2, 3), dm.getValue("primary.prof"))
        assertArrayEquals(byteArrayOf(4), dm.getValue("primary.profm"))
    }

    @Test fun apkWithoutProfileWritesNothing() {
        val out = ByteArrayOutputStream()
        assertFalse(writeDexMetadata(apk("classes.dex" to byteArrayOf(1)), out))
        assertEquals(0, out.size())
    }
}
