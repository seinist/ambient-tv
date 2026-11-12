package com.ambient.tv.ui.performance

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundTrimMemoryCacheTest {
    @Test
    fun runningLowKeepsThreeQuartersOfTheBudget() {
        // Coil asks for half of the current size on every RUNNING_LOW.
        assertEquals(75L, foregroundTrimTarget(requested = 50L, level = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, maxSize = 100L))
        // Repeated warnings cannot cascade below the floor.
        assertEquals(75L, foregroundTrimTarget(requested = 37L, level = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, maxSize = 100L))
        // A cache already below the floor is left alone.
        assertEquals(75L, foregroundTrimTarget(requested = 20L, level = 0, maxSize = 100L))
    }

    @Test
    fun criticalAndBackgroundTrimsPassThrough() {
        for (level in listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )) {
            assertEquals(50L, foregroundTrimTarget(requested = 50L, level = level, maxSize = 100L))
        }
    }
}
