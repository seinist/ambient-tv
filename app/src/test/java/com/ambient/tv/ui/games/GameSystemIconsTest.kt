package com.ambient.tv.ui.games

import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.Canvas
import com.ambient.tv.games.GameSystems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every registered console must inflate its own original, theme-tintable vector. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GameSystemIconsTest {
    @Test fun oneUniqueVectorPerSupportedSystem() {
        val registered = GameSystems.all.map { it.id }.toSet()
        assertEquals(registered, GameSystemIcons.supportedSystemIds)
        assertEquals(registered.size, registered.map(GameSystemIcons::drawable).toSet().size)
    }

    @Test fun allVectorsInflateAndHaveDrawnBounds() {
        val context = RuntimeEnvironment.getApplication()
        GameSystems.all.forEach { system ->
            val resource = GameSystemIcons.drawable(system.id)
            assertEquals(system.id, "drawable", context.resources.getResourceTypeName(resource))
            val art = ContextCompat.getDrawable(context, resource)
            assertNotNull("Cannot inflate vector for " + system.id, art)
            assertTrue("Missing icon width for " + system.id, art!!.intrinsicWidth > 0)
            assertTrue("Missing icon height for " + system.id, art.intrinsicHeight > 0)
        }
    }

    @Test fun allSystemVectorsRemainLegibleAt24And32DpWithoutClipping() {
        val context = RuntimeEnvironment.getApplication()
        for (system in GameSystems.all) {
            for (size in listOf(24, 32, 64)) {
                val icon = ContextCompat.getDrawable(context, GameSystemIcons.drawable(system.id))!!
                    .mutate()
                // Tint makes all foreground material equally visible, including dimmed surfaces.
                icon.setTint(android.graphics.Color.WHITE)
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                icon.setBounds(0, 0, size, size)
                icon.draw(Canvas(bitmap))
                var pixels = 0
                var minX = size
                var maxX = -1
                var minY = size
                var maxY = -1
                for (y in 0 until size) for (x in 0 until size) {
                    if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 40) {
                        pixels++
                        minX = minOf(minX, x)
                        maxX = maxOf(maxX, x)
                        minY = minOf(minY, y)
                        maxY = maxOf(maxY, y)
                    }
                }
                assertTrue("Empty or indistinct icon for ${system.id} at ${size}dp", pixels >= size)
                assertTrue("Icon clipped horizontally for ${system.id}", minX > 0 && maxX < size - 1)
                assertTrue("Icon clipped vertically for ${system.id}", minY > 0 && maxY < size - 1)
            }
        }
    }
}
