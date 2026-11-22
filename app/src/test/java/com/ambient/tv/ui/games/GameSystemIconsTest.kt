package com.ambient.tv.ui.games

import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
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

    @Test fun allVectorsHaveParseablePaintedGeometryAndConsistentViewports() {
        val context = RuntimeEnvironment.getApplication()
        val namespace = "http://schemas.android.com/apk/res/android"
        for (system in GameSystems.all) {
            val id = GameSystemIcons.drawable(system.id)
            val paths = mutableListOf<String>()
            var viewportWidth = 0f
            var viewportHeight = 0f
            context.resources.getXml(id).use { parser ->
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "vector" -> {
                                viewportWidth = parser.getAttributeValue(namespace, "viewportWidth")?.toFloatOrNull() ?: 0f
                                viewportHeight = parser.getAttributeValue(namespace, "viewportHeight")?.toFloatOrNull() ?: 0f
                            }
                            "path" -> parser.getAttributeValue(namespace, "pathData")?.let(paths::add)
                        }
                    }
                    parser.next()
                }
            }
            assertEquals("Unexpected vector width for ${system.id}", 64f, viewportWidth, 0f)
            assertEquals("Unexpected vector height for ${system.id}", 64f, viewportHeight, 0f)
            assertTrue("No painted paths for ${system.id}", paths.isNotEmpty())
            paths.forEach { data ->
                val nodes = PathParser.createNodesFromPathData(data)
                assertNotNull("Invalid path in ${system.id}", nodes)
                assertTrue("Blank or non-finite path in ${system.id}",
                    nodes.isNotEmpty() && nodes.all { node -> node.params.all { it.isFinite() } })
                assertTrue("Path contains no drawn segments in ${system.id}", nodes.any { node ->
                    node.type.uppercaseChar() in "LHVCSQTA" ||
                        (node.type.uppercaseChar() == 'M' && node.params.size > 2)
                })
            }
        }
    }
}
