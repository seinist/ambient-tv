package com.ambient.tv.ui.games

import androidx.core.content.ContextCompat
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
}
