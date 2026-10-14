package com.ambient.tv.ui.common.projectm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectMRenderResolutionTest {
    @Test
    fun `unknown keys fall back to auto`() {
        assertEquals("auto", ProjectMRenderResolution.normalize(null))
        assertEquals("auto", ProjectMRenderResolution.normalize("4k"))
        assertEquals("2160", ProjectMRenderResolution.normalize("2160"))
    }

    @Test
    fun `4K is only offered on 4K displays`() {
        assertEquals(listOf("auto", "720", "1080"), ProjectMRenderResolution.options(1080))
        assertEquals(listOf("auto", "720", "1080", "1440"), ProjectMRenderResolution.options(1440))
        assertEquals(listOf("auto", "720", "1080", "1440", "2160"), ProjectMRenderResolution.options(2160))
        // Unknown display: never hide the basic choices.
        assertEquals(listOf("auto", "720", "1080"), ProjectMRenderResolution.options(0))
    }

    @Test
    fun `a stored step above the display shows its largest offered step`() {
        assertEquals("1080", ProjectMRenderResolution.displayedKey("2160", 1080))
        assertEquals("2160", ProjectMRenderResolution.displayedKey("2160", 2160))
        assertEquals("auto", ProjectMRenderResolution.displayedKey("bogus", 2160))
    }

    @Test
    fun `auto caps at 1080p and explicit choices are capped by the panel`() {
        assertEquals(1080, ProjectMRenderResolution.effectiveLines("auto", 2160, throttled = false))
        assertEquals(720, ProjectMRenderResolution.effectiveLines("auto", 720, throttled = false))
        assertEquals(2160, ProjectMRenderResolution.effectiveLines("2160", 2160, throttled = false))
        assertEquals(1080, ProjectMRenderResolution.effectiveLines("2160", 1080, throttled = false))
        assertEquals(2160, ProjectMRenderResolution.effectiveLines("2160", 0, throttled = false))
    }

    @Test
    fun `low performance mode limits every choice to 720p`() {
        assertEquals(720, ProjectMRenderResolution.effectiveLines("2160", 2160, throttled = true))
        assertEquals(720, ProjectMRenderResolution.effectiveLines("auto", 2160, throttled = true))
    }

    @Test
    fun `4K on a 1080p UI renders a real 4K buffer`() {
        // Typical Android TV: UI window 1920x1080 on a 3840x2160 display mode.
        assertEquals(3840 to 2160, ProjectMRenderResolution.bufferSize("2160", 1920, 1080, 1920, 1080, 2160, false))
        assertEquals(1920 to 1080, ProjectMRenderResolution.bufferSize("auto", 1920, 1080, 1920, 1080, 2160, false))
        assertEquals(1280 to 720, ProjectMRenderResolution.bufferSize("720", 1920, 1080, 1920, 1080, 2160, false))
    }

    @Test
    fun `auto keeps large phone panels at 1080p in either orientation`() {
        // 1440x3120 phone in landscape and in portrait.
        assertEquals(2340 to 1080, ProjectMRenderResolution.bufferSize("auto", 3120, 1440, 3120, 1440, 1440, false))
        assertEquals(1080 to 2340, ProjectMRenderResolution.bufferSize("auto", 1440, 3120, 1440, 3120, 1440, false))
    }

    @Test
    fun `smaller views keep their share of the window`() {
        // A half-height view inside a 1080p window at 4K renders 1080 lines, not 2160.
        assertEquals(3840 to 1080, ProjectMRenderResolution.bufferSize("2160", 1920, 540, 1920, 1080, 2160, false))
    }

    @Test
    fun `buffer dimensions are even and never zero`() {
        assertEquals(2 to 2, ProjectMRenderResolution.bufferSize("720", 1, 1, 1920, 1080, 1080, false))
        val (width, height) = ProjectMRenderResolution.bufferSize("1440", 1919, 1079, 1920, 1080, 2160, false)!!
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
    }

    @Test
    fun `4K HDR choice needs Android 14 and a panel above 1080p`() {
        assertEquals(true, ProjectMRenderResolution.hdr4kChoiceVisible(34, 2160))
        assertEquals(true, ProjectMRenderResolution.hdr4kChoiceVisible(35, 1440))
        assertEquals(false, ProjectMRenderResolution.hdr4kChoiceVisible(34, 1080))
        assertEquals(false, ProjectMRenderResolution.hdr4kChoiceVisible(33, 2160))
    }

    @Test
    fun `unknown geometry keeps the layout size`() {
        assertNull(ProjectMRenderResolution.bufferSize("2160", 0, 1080, 1920, 1080, 2160, false))
        assertNull(ProjectMRenderResolution.bufferSize("2160", 1920, 1080, 0, 0, 2160, false))
    }
}
