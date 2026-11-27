package com.ambient.tv.games.metadata

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameArtworkFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun storageContext(root: File): Context = object : ContextWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
    ) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }

    private fun image(files: GameArtworkFiles, id: String): File {
        // The same native-decoder-verified 4x3 RGB PNG used by the catalog integration tests.
        val bytes = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAQAAAADCAIAAAA7ljmRAAAAFElEQVR4nGPUSljAAANMDEgAhQMAKzgBMKX6+B0AAAAASUVORK5CYII=")
        val staged = files.stage(bytes.inputStream())
        return File(files.commit(id, GameArtworkKind.COVER, staged))
    }

    @Test fun committedCoverSurvivesReconciliationByANewStorageInstance() {
        val context = storageContext(temporary.newFolder("app"))
        val cover = image(GameArtworkFiles(context), "game")
        val originalBytes = cover.readBytes()
        assertEquals(File(context.filesDir, "game_artwork").canonicalFile, requireNotNull(cover.parentFile?.parentFile).canonicalFile)

        val reopened = GameArtworkFiles(context)
        reopened.reconcile(setOf(cover.absolutePath))

        assertTrue("A committed cover remains available after reopening storage", cover.isFile)
        assertArrayEquals(originalBytes, cover.readBytes())
        assertEquals(4 to 3, reopened.dimensions(cover.absolutePath))
    }

    @Test fun equivalentAppDirectoryAliasesRetainTheStoredCoverInBothDirections() {
        for (storedViaAlias in listOf(true, false)) {
            val root = temporary.newFolder("app-$storedViaAlias")
            val alias = File(temporary.root, "app-alias-$storedViaAlias")
            Files.createSymbolicLink(alias.toPath(), root.toPath())
            val storedContext = storageContext(if (storedViaAlias) alias else root)
            val reopenedContext = storageContext(if (storedViaAlias) root else alias)
            val cover = image(GameArtworkFiles(storedContext), "game")
            val reopenedPath = File(File(reopenedContext.filesDir, "game_artwork"),
                "${cover.parentFile!!.name}/${cover.name}")
            assertNotEquals(cover.absolutePath, reopenedPath.absolutePath)
            assertEquals(cover.canonicalFile, reopenedPath.canonicalFile)

            val reopened = GameArtworkFiles(reopenedContext)
            reopened.reconcile(setOf(cover.absolutePath))

            assertTrue("A canonical alias must not make a referenced cover orphaned", cover.isFile)
            assertTrue(reopenedPath.isFile)
            assertEquals(4 to 3, reopened.dimensions(cover.absolutePath))
            Files.delete(alias.toPath())
        }
    }

    @Test fun reconciliationDeletesOnlyUnreferencedOwnedImages() {
        val context = storageContext(temporary.newFolder("app"))
        val files = GameArtworkFiles(context)
        val retained = image(files, "game")
        val replaced = image(files, "game")
        val orphan = image(files, "orphan")
        val original = temporary.newFile("original-cover.png").apply { writeBytes(retained.readBytes()) }

        GameArtworkFiles(context).reconcile(setOf(retained.absolutePath))

        assertTrue(retained.isFile)
        assertFalse(replaced.exists())
        assertFalse(orphan.exists())
        assertFalse("An empty orphan directory is removed", orphan.parentFile!!.exists())
        assertTrue("The original outside app-owned storage is never touched", original.isFile)
    }
}
