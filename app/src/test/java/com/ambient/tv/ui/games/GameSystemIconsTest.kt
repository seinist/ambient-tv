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
import org.xmlpull.v1.XmlPullParser

/**
 * The JVM test can validate Android vector XML resources but Robolectric does not
 * actually rasterize them in a Canvas: raster checks belong in androidTest.
 */
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

    @Test fun allVectorsHaveRealDistinctGeometryNotBlankPlaceholders() {
        val context = RuntimeEnvironment.getApplication()
        val namespace = "http://schemas.android.com/apk/res/android"
        for (system in GameSystems.all) {
            val id = GameSystemIcons.drawable(system.id)
            val paths = mutableListOf<String>()
            var viewport = 0f
            context.resources.getXml(id).use { parser ->
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "vector" -> viewport =
                                parser.getAttributeValue(namespace, "viewportWidth")?.toFloatOrNull() ?: 0f
                            "path" -> parser.getAttributeValue(namespace, "pathData")?.let(paths::add)
                        }
                    }
                    parser.next()
                }
            }
            assertEquals("Unexpected vector scale for ${system.id}", 64f, viewport)
            assertTrue("Too little geometry for ${system.id}", paths.size >= 4)
            assertTrue("Repeated or blank paths for ${system.id}",
                paths.all { it.isNotBlank() && it.startsWith("M") } &&
                    paths.distinct().size >= 4)
        }
    }
}
