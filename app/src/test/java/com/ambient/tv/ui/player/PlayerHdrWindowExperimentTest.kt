package com.ambient.tv.ui.player

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlayerHdrWindowExperimentTest {
    @Test fun overrideRestoresExactPreviousValueOnExitAndBackground() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val window = controller.get().window
            for (original in listOf(true, false)) {
                window.attributes = window.attributes.apply { setHdrConversionEnabled(original) }
                val experiment = PlayerHdrWindowExperiment(window)
                experiment.update("title", true)
                experiment.requested = true
                experiment.update("title", true)
                assertFalse(window.attributes.isHdrConversionEnabled)
                experiment.restore() // background
                assertEquals(original, window.attributes.isHdrConversionEnabled)
                experiment.update("title", true) // resumed
                assertFalse(window.attributes.isHdrConversionEnabled)
                experiment.update(null, false) // exit playback
                assertFalse(experiment.requested)
                assertEquals(original, window.attributes.isHdrConversionEnabled)
            }
        }
    }

    @Test fun experimentDoesNotLeakIntoAnotherTitleOrUnsupportedDisplay() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val window = controller.get().window
            val experiment = PlayerHdrWindowExperiment(window)
            experiment.update("first", true)
            experiment.requested = true
            experiment.update("first", true)
            experiment.update("second", true)
            assertFalse(experiment.requested)
            assertTrue(window.attributes.isHdrConversionEnabled)
            experiment.requested = true
            experiment.update("second", true)
            experiment.update("second", false)
            assertFalse(experiment.available)
            assertTrue(window.attributes.isHdrConversionEnabled)
        }
    }
}
