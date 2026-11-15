package com.ambient.tv.ui.display

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class UiResolutionPopupPositionTest {
    @Test fun alignmentUsesTheAnchorInRenderPixelsAndReversesOnlyHorizontalRtlOffsets() {
        val provider = UiPopupAlignmentPositionProvider(Alignment.TopStart, IntOffset(12, 20))
        val anchor = IntRect(100, 200, 500, 260)
        val content = IntSize(160, 80)
        assertEquals(IntOffset(112, 220), provider.calculatePosition(anchor, IntSize(1920, 1080), LayoutDirection.Ltr, content))
        assertEquals(IntOffset(328, 220), provider.calculatePosition(anchor, IntSize(1920, 1080), LayoutDirection.Rtl, content))
    }

    @Test fun centerAlignmentKeepsIndependentAnchorAndContentRounding() {
        val provider = UiPopupAlignmentPositionProvider(Alignment.TopCenter, IntOffset(0, 48))
        assertEquals(IntOffset(900, 48), provider.calculatePosition(IntRect(0, 0, 1921, 1080),
            IntSize(1921, 1080), LayoutDirection.Ltr, IntSize(121, 36)))
    }

    @Test fun clippingKeepsThePopupOnScreenWithoutRescalingProviderCoordinates() {
        val anchor = IntRect(1750, 900, 1900, 1000)
        val provider = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                assertEquals(anchor, anchorBounds)
                assertEquals(IntSize(1920, 1080), windowSize)
                assertEquals(IntSize(300, 200), popupContentSize)
                return IntOffset(1750, 1020)
            }
        }
        assertEquals(IntOffset(1620, 880), uiPopupPosition(provider, anchor, IntSize(1920, 1080),
            LayoutDirection.Ltr, IntSize(300, 200), clippingEnabled = true))
        assertEquals(IntOffset(1750, 1020), uiPopupPosition(provider, anchor, IntSize(1920, 1080),
            LayoutDirection.Ltr, IntSize(300, 200), clippingEnabled = false))
    }

    @Test fun oversizedContentAndNegativePositionsDoNotCreateInvalidClampRanges() {
        val provider = UiPopupAlignmentPositionProvider(Alignment.TopStart, IntOffset(-30, -20))
        assertEquals(IntOffset.Zero, uiPopupPosition(provider, IntRect(0, 0, 1280, 720), IntSize(1280, 720),
            LayoutDirection.Ltr, IntSize(1500, 800), clippingEnabled = true))
    }

    @Test fun native4kFallbackConvertsDestinationDimensionsBeforeUsingThe1080pAnchor() {
        val sourceAnchor = IntRect(100, 200, 500, 260)
        val provider = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                assertEquals(sourceAnchor, anchorBounds)
                assertEquals(IntSize(1920, 1080), windowSize)
                assertEquals(IntSize(160, 80), popupContentSize)
                return UiPopupAlignmentPositionProvider(Alignment.TopStart, IntOffset(12, 20))
                    .calculatePosition(anchorBounds, windowSize, layoutDirection, popupContentSize)
            }
        }
        assertEquals(IntOffset(224, 440), uiPopupPosition(provider, sourceAnchor, IntSize(3840, 2160),
            LayoutDirection.Ltr, IntSize(320, 160), clippingEnabled = true, sourceToDestinationScale = 2f))
    }

    @Test fun native1080pOverlayKeepsThePositionOfAnEmbedded4kAnchor() {
        val sourceAnchor = IntRect(200, 400, 1000, 520)
        val provider = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                assertEquals(sourceAnchor, anchorBounds)
                assertEquals(IntSize(3840, 2160), windowSize)
                assertEquals(IntSize(320, 160), popupContentSize)
                return UiPopupAlignmentPositionProvider(Alignment.TopStart, IntOffset(24, 40))
                    .calculatePosition(anchorBounds, windowSize, layoutDirection, popupContentSize)
            }
        }
        assertEquals(IntOffset(112, 220), uiPopupPosition(provider, sourceAnchor, IntSize(1920, 1080),
            LayoutDirection.Ltr, IntSize(160, 80), clippingEnabled = true, sourceToDestinationScale = .5f))
    }
}
