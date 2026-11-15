package com.ambient.tv.ui.display

import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class UiRenderViewConfigurationTest {
    private val windowConfiguration = object : ViewConfiguration {
        override val longPressTimeoutMillis = 650L
        override val doubleTapTimeoutMillis = 350L
        override val doubleTapMinTimeMillis = 45L
        override val touchSlop = 12f
        override val handwritingSlop = 4f
        override val handwritingGestureLineMargin = 18f
        override val minimumFlingVelocity = 50f
        override val maximumFlingVelocity = 8_000f
        override val minimumTouchTargetSize = DpSize(48.dp, 52.dp)
    }

    @Test fun downsampledAndNativeUiKeepTheSamePhysicalGestureThresholds() {
        for (renderScale in listOf(0.5f, 1f, 2f)) {
            val rendered = UiRenderViewConfiguration(windowConfiguration, renderScale)
            // SurfaceFlinger applies the inverse render scale to positions and distances.
            assertEquals(windowConfiguration.touchSlop, rendered.touchSlop / renderScale, 0f)
            assertEquals(windowConfiguration.handwritingSlop, rendered.handwritingSlop / renderScale, 0f)
            assertEquals(windowConfiguration.handwritingGestureLineMargin,
                rendered.handwritingGestureLineMargin / renderScale, 0f)
            assertEquals(windowConfiguration.minimumFlingVelocity, rendered.minimumFlingVelocity / renderScale, 0f)
            assertEquals(windowConfiguration.maximumFlingVelocity, rendered.maximumFlingVelocity / renderScale, 0f)
            assertEquals(windowConfiguration.minimumTouchTargetSize, rendered.minimumTouchTargetSize)
            assertEquals(windowConfiguration.longPressTimeoutMillis, rendered.longPressTimeoutMillis)
            assertEquals(windowConfiguration.doubleTapTimeoutMillis, rendered.doubleTapTimeoutMillis)
            assertEquals(windowConfiguration.doubleTapMinTimeMillis, rendered.doubleTapMinTimeMillis)
        }
    }

    @Test fun anUnboundedFlingLimitDoesNotOverflowWhenTheUiRendersAboveWindowResolution() {
        val unbounded = object : ViewConfiguration by windowConfiguration {
            override val maximumFlingVelocity = Float.MAX_VALUE
        }
        assertEquals(Float.MAX_VALUE, UiRenderViewConfiguration(unbounded, 2f).maximumFlingVelocity, 0f)
    }
}
