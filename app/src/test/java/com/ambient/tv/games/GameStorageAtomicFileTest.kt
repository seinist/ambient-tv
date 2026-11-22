package com.ambient.tv.games

import android.app.Application
import androidx.core.util.AtomicFile
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Real AndroidX transactions and GameStorage readers at deterministic write boundaries.
 * The split writer exposes those boundaries without timing or IO mocks; GameStorage.write's
 * AtomicFile import remains a separate wiring check rather than being inferred by these tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class GameStorageAtomicFileTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun readerKeepsTheCompletePreviousSaveUntilCommit() {
        val storage = GameStorage(RuntimeEnvironment.getApplication())
        val file = files.newFile("auto.state")
        val previous = ByteArray(513) { (it * 7).toByte() }
        val replacement = ByteArray(1025) { (it * 11 + 9).toByte() }
        storage.write(file, previous)

        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        var committed = false
        try {
            out.write(replacement, 0, 97)
            out.flush()
            assertArrayEquals(previous, storage.read(file))

            out.write(replacement, 97, replacement.size - 97)
            out.flush()
            // Even a complete buffer is private until the transaction publishes it.
            assertArrayEquals(previous, storage.read(file))

            atomic.finishWrite(out)
            committed = true
            assertArrayEquals(replacement, storage.read(file))
        } finally {
            if (!committed) atomic.failWrite(out)
        }
    }

    @Test fun abortingAPartialWritePreservesTheCompletePreviousSave() {
        val storage = GameStorage(RuntimeEnvironment.getApplication())
        val file = files.newFile("auto.state")
        val previous = ByteArray(513) { (it * 7).toByte() }
        storage.write(file, previous)

        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(byteArrayOf(9, 8, 7))
            out.flush()
            assertArrayEquals(previous, storage.read(file))
        } finally {
            atomic.failWrite(out)
        }
        assertArrayEquals(previous, storage.read(file))
    }
}
