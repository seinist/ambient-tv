package com.ambient.tv.romm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RommCoreDataTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true }

    private fun File.put(path: String, text: String, modified: Long): File =
        File(this, path).also { it.parentFile?.mkdirs(); it.writeText(text); it.setLastModified(modified) }

    @Test fun onlyTheCoresOwnDataIsTracked() {
        assertTrue(RommCoreData.tracked("PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN"))
        assertTrue(RommCoreData.tracked("User/GC/USA/Card A/01-GALE-MarioSunshine.gci"))
        assertTrue(RommCoreData.tracked("Sonic Adventure.A1.bin"))
        // The player's in-game saves sync on their own.
        assertFalse(RommCoreData.tracked("0123456789abcdef0123456789abcdef01234567.srm"))
        assertFalse(RommCoreData.tracked("0123456789abcdef0123456789abcdef01234567.srm.before-server"))
        // Caches, logs, settings, hidden and unsafe paths stay on the device.
        assertFalse(RommCoreData.tracked("User/ShaderCache/GALE01.uidcache"))
        assertFalse(RommCoreData.tracked("User/Config/Dolphin.ini"))
        assertFalse(RommCoreData.tracked("PSP/SYSTEM/CACHE/x.bin"))
        assertFalse(RommCoreData.tracked("dolphin.log"))
        assertFalse(RommCoreData.tracked(".hidden/x"))
        assertFalse(RommCoreData.tracked("../escape.bin"))
        assertFalse(RommCoreData.tracked("/absolute.bin"))
        assertFalse(RommCoreData.tracked("a\\b.bin"))
        assertFalse(RommCoreData.tracked("card.bin.partial"))
        assertTrue(RommCoreData.isBundle(RommCoreData.FILE_NAME))
        assertFalse(RommCoreData.isBundle("game.srm"))
    }

    @Test fun whatAGameWritesIsFoundByComparingTheFolder() {
        val root = temporary.newFolder("saves")
        root.put("shared.mcd", "a", 1_000L)
        root.put("User/Cache/skip.bin", "x", 1_000L)
        val before = RommCoreData.scan(root)
        assertEquals(setOf("shared.mcd"), before.keys)
        root.put("shared.mcd", "ab", 2_000L)
        root.put("PSP/SAVEDATA/GAME/DATA.BIN", "save", 2_000L)
        root.put("User/Cache/skip.bin", "xy", 2_000L)
        assertEquals(setOf("shared.mcd", "PSP/SAVEDATA/GAME/DATA.BIN"),
            RommCoreData.changed(before, RommCoreData.scan(root)))
    }

    @Test fun theArchiveCarriesDatesAndANewerCopyIsKept() {
        val device = temporary.newFolder("device")
        device.put("card.mcd", "device A", 5_000L)
        device.put("PSP/SAVEDATA/GAME/DATA.BIN", "progress", 7_000L)
        val paths = setOf("card.mcd", "PSP/SAVEDATA/GAME/DATA.BIN")
        val digest = RommCoreData.digest(device, paths)
        val archive = RommCoreData.pack(device, paths, json)!!

        val other = temporary.newFolder("other")
        other.put("card.mcd", "newer on B", 9_000L)
        val unpacked = RommCoreData.unpack(archive, other, json)
        assertEquals(paths, unpacked.paths)
        assertTrue(unpacked.keptNewer)
        assertEquals("newer on B", File(other, "card.mcd").readText())
        val written = File(other, "PSP/SAVEDATA/GAME/DATA.BIN")
        assertEquals("progress", written.readText())
        assertEquals(7_000L, written.lastModified())

        val fresh = temporary.newFolder("fresh")
        val again = RommCoreData.unpack(archive, fresh, json)
        assertFalse(again.keptNewer)
        // Same files with the same dates: nothing to send back.
        assertEquals(digest, RommCoreData.digest(fresh, paths))
        File(fresh, "card.mcd").setLastModified(6_000L)
        assertNotEquals(digest, RommCoreData.digest(fresh, paths))
    }

    @Test fun anArchiveCannotWriteOutsideTheSaveFolder() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                listOf("../outside.bin", "0123456789abcdef0123456789abcdef01234567.srm", "ok.bin").forEach { name ->
                    zip.putNextEntry(ZipEntry(name).apply { time = 4_000L })
                    zip.write(name.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val root = temporary.newFolder("root")
        val unpacked = RommCoreData.unpack(bytes, root, json)
        assertEquals(setOf("ok.bin"), unpacked.paths)
        assertFalse(File(root.parentFile, "outside.bin").exists())
        assertFalse(File(root, "0123456789abcdef0123456789abcdef01234567.srm").exists())
    }

    @Test fun nothingToPackGivesNoArchive() {
        assertNull(RommCoreData.pack(temporary.newFolder("empty"), setOf("missing.bin"), json))
    }
}
