package com.ambient.tv.games.metadata

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameArtworkGeometryTest {
    @Test fun importedJpegUsesDisplayedExifOrientationForItsStoredBounds() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val original = File(context.cacheDir, "rotated-cover.jpg")
        val bitmap = Bitmap.createBitmap(12, 4, Bitmap.Config.ARGB_8888)
        try {
            original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            ExifInterface(original.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val files = GameArtworkFiles(context)
            val staged = original.inputStream().use(files::stage)
            assertEquals(4, staged.width)
            assertEquals(12, staged.height)
            val owned = files.commit("game", GameArtworkKind.COVER, staged)
            assertEquals(4 to 12, files.dimensions(owned))
            assertNull(files.dimensions(original.absolutePath))
            assertTrue(original.isFile)
            files.removePath(owned)
        } finally { bitmap.recycle(); original.delete() }
    }
}
