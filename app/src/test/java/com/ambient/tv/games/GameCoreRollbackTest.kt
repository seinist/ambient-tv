package com.ambient.tv.games

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GameCoreRollbackTest {
    private fun elf(marker: Int, abi: String? = "arm64-v8a"): ByteArray =
        ByteBuffer.allocate(0x90).order(ByteOrder.LITTLE_ENDIAN).run {
            val is64 = abi in setOf("arm64-v8a", "x86_64")
            val machine = when (abi) {
                "arm64-v8a" -> 183
                "armeabi-v7a" -> 40
                "x86_64" -> 62
                "x86" -> 3
                else -> 183
            }
            put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(),
                (if (is64) 2 else 1).toByte(), 1, 1))
            putShort(0x10, 3) // ET_DYN
            putShort(0x12, machine.toShort())
            if (is64) {
                putLong(0x20, 0x40)
                putShort(0x36, 0x38)
                putShort(0x38, 1)
                putInt(0x40, 1)
                putLong(0x40 + 0x30, 16_384)
            } else {
                putInt(0x1c, 0x34)
                putShort(0x2a, 0x20)
                putShort(0x2c, 1)
                putInt(0x34, 1)
                putInt(0x34 + 0x1c, 16_384)
            }
            put(0x78, marker.toByte())
            array()
        }

    @Test fun aCoreUpgradeKeepsOnePreviousBuildForRollback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = GameCoreManager(context, GameCoreChannel())
        val core = GameCore("rollback_test", "Rollback fixture", "MIT")
        manager.remove(core)
        val old = elf(4, manager.abi)
        val replacement = elf(5, manager.abi)
        try {
            manager.install(core, old.inputStream(), zipped = false, origin = "test-one")
            assertFalse(manager.canRollback(core))
            manager.install(core, replacement.inputStream(), zipped = false, origin = "test-two")
            assertTrue(manager.canRollback(core))
            assertArrayEquals(replacement, manager.installed(core)!!.readBytes())
            assertTrue(manager.rollback(core))
            assertArrayEquals(old, manager.installed(core)!!.readBytes())
            assertFalse(manager.canRollback(core))
            assertTrue(manager.info(core)!!.origin == "test-one")
        } finally {
            manager.remove(core)
        }
    }
    @Test fun experimentalCoresKeepSeparateRollbackAndStableFiles() = runBlocking {
        val manager = GameCoreManager(ApplicationProvider.getApplicationContext<Context>(), GameCoreChannel())
        val core = GameCore("profile_fixture", "Profile fixture", "MIT")
        val profile = GameCoreSource.HOTPATHS
        try {
            val stable = elf(1, manager.abi)
            val candidate = elf(2, manager.abi)
            manager.install(core, stable.inputStream(), false, "stable")
            val origin = "${GameCoreManager.SOURCE_EXPERIMENT_PREFIX}core-experiments-core-hotpaths--"
            manager.install(core, candidate.inputStream(), false, "${origin}1", profile)
            manager.install(core, elf(3, manager.abi).inputStream(), false, "${origin}2", profile)
            assertTrue(manager.rollback(core, profile))
            assertArrayEquals(candidate, manager.installed(core, profile)!!.readBytes())
            assertArrayEquals(stable, manager.installed(core)!!.readBytes())
            assertFalse(manager.canRollback(core))
            manager.remove(core, profile)
            assertTrue(manager.installed(core, profile) == null)
            assertArrayEquals(stable, manager.installed(core)!!.readBytes())
        } finally {
            manager.remove(core, profile)
            manager.remove(core)
        }
    }

    @Test fun explicitSourcesRejectOtherOriginsForPlaybackAndRollback() = runBlocking {
        val manager = GameCoreManager(ApplicationProvider.getApplicationContext<Context>(), GameCoreChannel())
        val core = GameCore("source_fixture", "Source fixture", "MIT")
        val signed = GameCoreSource.AMBIENT
        val buildbot = GameCoreSource.BUILDBOT
        try {
            manager.install(core, elf(1, manager.abi).inputStream(), false, GameCoreManager.SOURCE_BUILDBOT)
            assertTrue(manager.installed(core, buildbot) != null)
            assertTrue(manager.installed(core, signed) == null)
            assertTrue(manager.info(core, signed)?.origin == GameCoreManager.SOURCE_BUILDBOT)

            manager.install(core, elf(2, manager.abi).inputStream(), false, "${GameCoreManager.SOURCE_AMBIENT_PREFIX}cores-2")
            assertTrue(manager.installed(core, signed) != null)
            assertTrue(manager.installed(core, buildbot) == null)
            assertFalse(manager.rollback(core, signed))
            assertTrue(manager.rollback(core, buildbot))
            assertTrue(manager.installed(core, buildbot) != null)
        } finally {
            manager.remove(core)
        }
    }

    @Test fun failedAtomicRollbackPreservesThePreviousCore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = GameCoreManager(context, GameCoreChannel())
        val core = GameCore("rollback_failure_test", "Rollback error fixture", "MIT")
        manager.remove(core)
        val old = elf(7, manager.abi)
        try {
            manager.install(core, old.inputStream(), zipped = false, origin = "old")
            manager.install(core, elf(8, manager.abi).inputStream(), zipped = false, origin = "new")
            val active = manager.file(core)
            assertTrue(active.delete())
            assertTrue(active.mkdir())
            java.io.File(active, "block-rename").writeText("marker")
            assertFalse(manager.rollback(core))
            assertTrue(java.io.File(active, "block-rename").isFile)
            assertTrue(manager.canRollback(core))
            assertArrayEquals(old,
                java.io.File(active.parentFile, "${core.id}_libretro_android.previous.so").readBytes())
        } finally {
            manager.file(core).deleteRecursively()
            manager.remove(core)
        }
    }

    @Test fun mismatchedAbiOrWrongZipCoreCannotBeInstalled() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = GameCoreManager(context, GameCoreChannel())
        val core = GameCore("core_import_test", "test", "MIT")
        try {
            val binary = java.io.File.createTempFile("wrong-abi-", ".so")
            try {
                binary.writeBytes(elf(7, "arm64-v8a"))
                assertTrue(runCatching {
                    GameCoreManager.checkElf(binary, 16_384, "x86")
                }.exceptionOrNull() is GameCoreIncompatibleException)
            } finally { binary.delete() }

            val archive = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(archive).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("unrelated_libretro_android.so"))
                zip.write(elf(8, manager.abi))
                zip.closeEntry()
            }
            assertTrue(runCatching {
                manager.install(core, archive.toByteArray().inputStream(), zipped = true, origin = "bad-zip")
            }.exceptionOrNull() is GameCoreIncompatibleException)
            assertFalse(manager.installed(core)?.isFile == true)
        } finally { manager.remove(core) }
    }

}
