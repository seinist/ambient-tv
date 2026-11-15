package com.ambient.tv.ui.theme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.ambient.tv.ui.display.uiHdrHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Covers the material and motion drawn together after the two accent changes are merged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlMotionRenderingTest {
    private val surfaces = listOf(
        AppSurface to AppSurfaceRaised,
        Color(0xFF090909) to Color(0xFF151515),
        Color(0xFF1A1D22) to Color(0xFF252930),
    )
    private val accents = listOf(Color(0xFF001020), Color.White, Gold)
    // Full strength at each sampled phase is stricter than the finite gesture's envelope.
    private val frames = listOf(AccentMotionFrame.Rest) +
        listOf(.15f, .5f, .85f).map { AccentMotionFrame(it, 1f) }
    private val fill = Outline.Rectangle(Rect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()))

    @Test fun focusedControlContentRemainsReadableThroughoutMaterialMotionInSdrAndHdr() {
        for (surface in surfaces) for (accent in accents) for (style in AccentStyle.entries) {
            for (headroom in listOf(1f, 2f)) {
                val palette = controlPalette(
                    surface = uiHdrHighlight(surface.first, headroom),
                    raisedSurface = uiHdrHighlight(surface.second, headroom),
                    content = uiHdrHighlight(AppOnSurface, headroom),
                    mutedContent = uiHdrHighlight(AppOnSurfaceMuted, headroom),
                    accent = uiHdrHighlight(accent, headroom),
                    style = style,
                    headroom = headroom,
                )
                val material = palette.materialBrush()
                val paints = AccentMotionPaints(palette.input.accent, style)
                // Unpressed controls are brighter. Exercise plain, selected, prominent,
                // and selected/prominent focus while the material gesture is running.
                for (state in 0..3) {
                    val colors = palette.colors(
                        focused = true,
                        selected = state and 1 != 0,
                        prominent = state and 2 != 0,
                    )
                    val foreground = controlDesignColor(colors.content, headroom)
                    for (frame in frames) {
                        val bitmap = render(headroom) {
                            drawOutline(fill, colors.container)
                            if (colors.washOpacity > 0f) {
                                drawOutline(fill, material, alpha = colors.washOpacity)
                            }
                            paints.drawOutline(this, fill, frame, emphasis = CONTROL_ACCENT_MOTION_EMPHASIS)
                        }
                        try {
                            val context = "$surface / $accent / $style / HDR $headroom / state $state / $frame"
                            for (y in SAMPLE_Y) for (x in SAMPLE_X) {
                                val pixel = bitmap.getColor(x, y)
                                assertTrue("$context at ($x,$y) must be finite", pixel.components.all { it.isFinite() })
                                assertEquals("$context must cover underlying artwork", 1f, pixel.alpha(), .001f)
                                val drawn = Color(pixel.red(), pixel.green(), pixel.blue(), pixel.alpha(),
                                    if (headroom > 1f) ColorSpaces.LinearExtendedSrgb else ColorSpaces.Srgb)
                                val background = controlDesignColor(drawn, headroom)
                                val contrast = contrastRatio(foreground, background)
                                assertTrue("$context at ($x,$y): contrast=$contrast", contrast >= 4.5f)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    @Test fun renderedMotionStaysWithinTheOpacityReservedByTheControlPalette() {
        for (style in AccentStyle.entries) {
            val paints = AccentMotionPaints(Color.White, style)
            for (frame in frames) {
                // A transparent target measures all shader passes' combined coverage,
                // including overlapping Pearl/Facet reflections, independently of hue.
                val bitmap = render(headroom = 2f) {
                    paints.drawOutline(this, fill, frame, emphasis = CONTROL_ACCENT_MOTION_EMPHASIS)
                }
                try {
                    val limit = controlAccentMotionMaxOpacity(style)
                    for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                        val opacity = bitmap.getColor(x, y).alpha()
                        assertTrue("$style / $frame at ($x,$y): opacity=$opacity, limit=$limit",
                            opacity.isFinite() && opacity >= 0f && opacity <= limit + .001f)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun render(headroom: Float, draw: DrawScope.() -> Unit): Bitmap {
        val hdr = headroom > 1f
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT,
            if (hdr) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888, true,
            ColorSpace.get(if (hdr) ColorSpace.Named.LINEAR_EXTENDED_SRGB else ColorSpace.Named.SRGB))
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()),
            Size(WIDTH.toFloat(), HEIGHT.toFloat()), draw)
        return bitmap
    }

    private companion object {
        const val WIDTH = 96
        const val HEIGHT = 32
        // Include narrow central Chrome reflections and top-edge Glass/Ceramic highlights.
        val SAMPLE_X = listOf(1, 12, 24, 31, 33, 40, 45, 48, 51, 60, 66, 75, 84, 94)
        val SAMPLE_Y = listOf(1, 3, 8, 16, 24, 30)
    }
}
