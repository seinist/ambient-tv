package com.ambient.tv.ui.mobile

import org.junit.Assert.*
import org.junit.Test

class DeviceUiPolicyTest {
    @Test fun televisionKeepsItsUiInEveryNightMode() {
        listOf(0x00, 0x10, 0x20, 0x30).forEach { night -> assertTrue(isTelevisionMode(0x04 or night)) }
    }

    @Test fun phoneAndOtherNonTvModesDoNotSelectTelevision() {
        // Normal, desk, car, appliance, watch, VR; neither orientation nor input device participates.
        listOf(0x01, 0x02, 0x03, 0x05, 0x06, 0x07).forEach { type ->
            assertFalse(isTelevisionMode(type))
            assertFalse(isTelevisionMode(type or 0x20))
        }
    }

    @Test fun wideLayoutRequiresBothHandheldDeviceAndLandscapeBounds() {
        assertTrue(isWideHandheld(handheld = true, widthDp = 800, heightDp = 360))
        assertFalse(isWideHandheld(handheld = true, widthDp = 360, heightDp = 800))
        assertFalse(isWideHandheld(handheld = false, widthDp = 800, heightDp = 360))
    }

    @Test fun tabletIsHandheldWithAtLeast600dpSmallestWidth() {
        assertTrue(isTabletFormFactor(handheld = true, smallestScreenWidthDp = 600))
        assertTrue(isTabletFormFactor(handheld = true, smallestScreenWidthDp = 800))
        assertFalse(isTabletFormFactor(handheld = true, smallestScreenWidthDp = 599))
        assertFalse(isTabletFormFactor(handheld = false, smallestScreenWidthDp = 800))
    }

    @Test fun detailStacksOnlyBelowTabletWidthIncludingPhoneLandscape() {
        assertTrue(handheldDetailStacks(411))
        assertTrue(handheldDetailStacks(599))
        assertFalse(handheldDetailStacks(600))
        assertFalse(handheldDetailStacks(891))
    }

    @Test fun settingsSideIndexStaysOffPhonesEvenInLandscape() {
        assertFalse(settingsUsesSideIndex(tablet = false, screenWidthDp = 891))
        assertFalse(settingsUsesSideIndex(tablet = true, screenWidthDp = 599))
        assertTrue(settingsUsesSideIndex(tablet = true, screenWidthDp = 800))
    }

    @Test fun tabletHeroTracksViewportInsteadOfAFixedBlock() {
        val tall = tabletHeroHeightDp(1280, 390)
        val short = tabletHeroHeightDp(700, 390)
        assertTrue(tall > short)
        assertTrue(tall > 390)
        assertTrue(short < 390)
        assertTrue(short in 224..434)
        assertTrue(tabletHeroHeightDp(1280, 470) > tall)
    }

    @Test fun seekUsesTheLatestPlaybackPositionAfterDragging() {
        assertEquals(53_000L, handheldSeekDelta(.75f, 7_000L, 80_000L))
        assertEquals(51_000L, handheldSeekDelta(.75f, 9_000L, 80_000L))
    }

    @Test fun seekClampsBothGestureEnds() {
        assertEquals(-30_000L, handheldSeekDelta(-.5f, 30_000, 90_000))
        assertEquals(60_000L, handheldSeekDelta(1.5f, 30_000, 90_000))
    }

    @Test fun seekSupportsLongDurationsWithoutIntegerOverflow() {
        assertEquals(5_000_000_000L, handheldSeekDelta(.5f, 1_000_000_000, 12_000_000_000))
    }

    @Test fun emptyTimelineCannotSeekForward() {
        assertEquals(0L, handheldSeekDelta(.5f, 0, 0))
        assertEquals(0L, handheldSeekDelta(.5f, 0, -1))
    }

    @Test fun portraitKeepsStatusBarAndCutoutOnEveryEdge() {
        val padding = handheldContentEdgeInsets(
            landscape = false,
            status = EdgeInsets(left = 0, top = 80, right = 0, bottom = 0),
            cutout = EdgeInsets(left = 0, top = 120, right = 0, bottom = 0),
            navigation = EdgeInsets(left = 0, top = 0, right = 0, bottom = 48),
            ime = EdgeInsets(),
        )
        assertEquals(EdgeInsets(left = 0, top = 120, right = 0, bottom = 48), padding)
    }

    @Test fun landscapeDropsSideStatusBarAndCutoutButKeepsRealBars() {
        // Portrait top status bar (80) and notch (100) are reported on the left in landscape.
        // Padding for them is the dead column. A navigation bar on the right and the top status
        // bar that stayed on the long edge must still be cleared.
        val padding = handheldContentEdgeInsets(
            landscape = true,
            status = EdgeInsets(left = 80, top = 36, right = 0, bottom = 0),
            cutout = EdgeInsets(left = 100, top = 0, right = 0, bottom = 0),
            navigation = EdgeInsets(left = 0, top = 0, right = 48, bottom = 0),
            ime = EdgeInsets(left = 0, top = 0, right = 0, bottom = 200),
        )
        assertEquals(EdgeInsets(left = 0, top = 36, right = 48, bottom = 200), padding)
    }

    @Test fun landscapeSideNavigationBarIsNotRemovedWithTheCutout() {
        val padding = handheldContentEdgeInsets(
            landscape = true,
            status = EdgeInsets(left = 80),
            cutout = EdgeInsets(left = 80),
            navigation = EdgeInsets(left = 120),
            ime = EdgeInsets(),
        )
        assertEquals(120, padding.left)
        assertEquals(0, padding.right)
    }

    @Test fun landscapeCutoutOnTheRightDoesNotLetterboxEitherSide() {
        val padding = handheldContentEdgeInsets(
            landscape = true,
            status = EdgeInsets(right = 64, top = 24),
            cutout = EdgeInsets(right = 96),
            navigation = EdgeInsets(bottom = 16),
            ime = EdgeInsets(),
        )
        assertEquals(EdgeInsets(left = 0, top = 24, right = 0, bottom = 16), padding)
    }
}
