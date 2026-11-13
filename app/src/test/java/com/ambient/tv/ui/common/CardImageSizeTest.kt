package com.ambient.tv.ui.common

import androidx.compose.ui.unit.Constraints
import coil3.size.Dimension
import coil3.size.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class CardImageSizeTest {
    @Test fun fourKInterfaceDecodesCardsAtTheirSizeOnA1080pInterface() {
        // A 284 dp card at density 4 (4K UI) decodes as at density 2.
        assertEquals(Size(568, 320), cardImage1080Size(1136, 640, density = 4f))
        // At or below 1080p density nothing changes.
        assertEquals(Size(568, 320), cardImage1080Size(568, 320, density = 2f))
        assertEquals(Size(426, 240), cardImage1080Size(426, 240, density = 1.5f))
    }

    @Test fun unboundedSidesStayUndefined() {
        assertEquals(Size(Dimension(300), Dimension.Undefined), cardImage1080Size(600, Constraints.Infinity, density = 4f))
    }
}
