package com.ambient.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import com.ambient.tv.ui.display.uiHdrHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlColorsTest {
    private val surfaces = listOf(
        AppSurface to AppSurfaceRaised,
        Color(0xFF090909) to Color(0xFF151515),
        Color(0xFF1A1D22) to Color(0xFF252930),
    )
    private val accents = AccentOptions.values.toList() + listOf(
        Color.Black, Color.White, Color(0xFF020202), Color(0xFF240040),
        Color(0xFF001A1A), Color(0xFF003366), Color.Yellow, Color.Cyan,
    )

    private fun palette(
        surfaces: Pair<Color, Color>,
        accent: Color,
        style: AccentStyle,
        headroom: Float = 1f,
    ) = controlPalette(
        surface = uiHdrHighlight(surfaces.first, headroom),
        raisedSurface = uiHdrHighlight(surfaces.second, headroom),
        content = uiHdrHighlight(AppOnSurface, headroom),
        mutedContent = uiHdrHighlight(AppOnSurfaceMuted, headroom),
        accent = uiHdrHighlight(accent, headroom),
        style = style,
        headroom = headroom,
    )

    private fun ControlPalette.state(index: Int) = colors(
        selected = index and 1 != 0,
        prominent = index and 2 != 0,
        focused = index and 4 != 0,
        pressed = index and 8 != 0,
    )

    /** White static and animated stops bound every actual style, even in linear HDR. */
    private fun worstWash(fill: Color, washOpacity: Float, headroom: Float, style: AccentStyle): Color {
        val opacity = 1f - (1f - washOpacity) * (1f - controlAccentMotionMaxOpacity(style))
        val linear = controlDesignColor(fill, headroom).convert(ColorSpaces.LinearExtendedSrgb)
        return Color(
            linear.red * (1f - opacity) + opacity,
            linear.green * (1f - opacity) + opacity,
            linear.blue * (1f - opacity) + opacity,
            1f,
            ColorSpaces.LinearExtendedSrgb,
        ).convert(ColorSpaces.Srgb)
    }

    @Test fun everyEnabledStateKeepsLightContentReadableAboveBrightArtworkAndAllMaterials() {
        for (surface in surfaces) for (accent in accents) for (style in AccentStyle.entries) {
            for (headroom in listOf(1f, 1.5f, 2f)) {
                val palette = palette(surface, accent, style, headroom)
                for (state in 0..15) {
                    val colors = palette.state(state)
                    val context = "$surface / $accent / $style / HDR $headroom / state $state"
                    assertEquals(context, 1f, colors.container.alpha, 0f)
                    assertEquals(context, uiHdrHighlight(AppOnSurface, headroom), colors.content)
                    val background = worstWash(colors.container, colors.washOpacity, headroom, style)
                    val foreground = controlDesignColor(colors.content, headroom)
                    assertTrue(context, contrastRatio(foreground, background) >= 4.5f)
                    // The opaque base prevents any underlying poster/video from changing it.
                    val designFill = controlDesignColor(colors.container, headroom)
                    assertEquals(context, designFill, designFill.compositeOver(Color.White))
                }
            }
        }
    }

    @Test fun focusSelectionPressAndAccentTransitionsStayReadable() {
        val transitions = listOf(0 to 4, 1 to 5, 2 to 6, 3 to 7, 4 to 12, 5 to 13, 6 to 14, 7 to 15)
        for (surface in surfaces) for (style in AccentStyle.entries) for (headroom in listOf(1f, 2f)) {
            // Covers neutral focus, selection tint, prominent actions and theme-color changes.
            val dark = palette(surface, Color(0xFF120020), style, headroom)
            val bright = palette(surface, Color.White, style, headroom)
            for ((fromIndex, toIndex) in transitions) {
                for ((fromPalette, toPalette) in listOf(dark to dark, bright to bright, dark to bright)) {
                    val from = fromPalette.state(fromIndex)
                    val to = toPalette.state(toIndex)
                    for (step in 0..20) {
                        val fraction = step / 20f
                        val fill = lerp(from.container, to.container, fraction)
                        // The old/new brush can change before either animated state settles.
                        val opacity = maxOf(from.washOpacity, to.washOpacity)
                        val background = worstWash(fill, opacity, headroom, style)
                        assertTrue(
                            "$style / HDR $headroom / $fromIndex -> $toIndex / $fraction",
                            contrastRatio(AppOnSurface, background) >= 4.5f,
                        )
                    }
                }
            }
        }
    }

    @Test fun selectionSurvivesFocusAndFocusHasItsOwnHighContrastOutline() {
        for (surface in surfaces) for (accent in accents) for (style in AccentStyle.entries) {
            val palette = palette(surface, accent, style)
            val idle = palette.colors(focused = false)
            val focused = palette.colors(focused = true)
            val selected = palette.colors(focused = false, selected = true)
            val selectedFocused = palette.colors(focused = true, selected = true)
            val prominent = palette.colors(focused = false, prominent = true)
            assertNotEquals(idle.container, prominent.container)
            assertNotEquals(focused.container, selectedFocused.container)
            assertEquals(focused.outline, selectedFocused.outline)
            assertEquals(AppOnSurface.copy(alpha = .94f), focused.outline)
            val background = worstWash(selectedFocused.container, selectedFocused.washOpacity, 1f, style)
            assertTrue(contrastRatio(focused.outline.compositeOver(background), background) >= 3f)
            val selectedBackground = worstWash(selected.container, selected.washOpacity, 1f, style)
            assertTrue(
                "Selected chip edge: $surface / $accent / $style",
                contrastRatio(selected.outline.compositeOver(selectedBackground), selectedBackground) >= 3f,
            )
        }
    }

    @Test fun disabledControlsKeepOnlyASubduedOutlineWhenFocusIsRetained() {
        for (style in AccentStyle.entries) {
            val palette = palette(surfaces.first(), Gold, style)
            val disabled = palette.colors(focused = true, selected = true, enabled = false, prominent = true)
            assertEquals(1f, disabled.container.alpha, 0f)
            assertEquals(.55f, disabled.content.alpha, .001f)
            assertEquals(AppOnSurface.copy(alpha = .65f), disabled.outline)
            assertEquals(0f, disabled.washOpacity, 0f)
            assertEquals(Color.Transparent, palette.colors(focused = false, enabled = false).outline)
            assertTrue(palette.colors(focused = true).outline.alpha > disabled.outline.alpha)
        }
    }

    @Test fun hdrFocusIsLiftedOnceAndMaterialStopsRemainInsideTheDisplayHeadroom() {
        for (headroom in listOf(1.25f, 1.5f, 2f)) for (style in AccentStyle.entries) {
            val palette = palette(surfaces.last(), Color.Cyan, style, headroom)
            val colors = palette.colors(focused = true, selected = true, prominent = true)
            val expected = uiHdrHighlight(AppOnSurface, headroom)
            assertEquals(expected.red, colors.outline.red, 0f)
            assertEquals(expected.green, colors.outline.green, 0f)
            assertEquals(expected.blue, colors.outline.blue, 0f)
            for ((_, stop) in palette.materialStops) {
                val linear = stop.convert(ColorSpaces.LinearExtendedSrgb)
                assertTrue(linear.red <= headroom + .002f)
                assertTrue(linear.green <= headroom + .002f)
                assertTrue(linear.blue <= headroom + .002f)
                assertTrue(linear.red >= 0f && linear.green >= 0f && linear.blue >= 0f)
            }
        }
    }
}
