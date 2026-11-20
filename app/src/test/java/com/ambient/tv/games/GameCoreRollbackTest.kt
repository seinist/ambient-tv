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
    private fun elf(marker: Int): ByteArray = ByteBuffer.allocate(0x80).order(ByteOrder.LITTLE_ENDIAN).run {
        put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        putLong(0x20, 0x40)
        putShort(0x36, 0x38)
        putShort(0x38, 1)
        putInt(0x40, 1)
        putLong(0x40 + 0x30, 16_384)
        put(0x70, marker.toByte())
        array()
    }

    @Test fun aCoreUpgradeKeepsOnePreviousBuildForRollback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = GameCoreManager(context, GameCoreChannel())
        val core = GameCore("rollback_test", "Rollback fixture", "MIT")
        manager.remove(core)
        val old = elf(4)
        val replacement = elf(5)
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
}
