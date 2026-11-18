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
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.ambient.tv.ui.display.uiHdrHighlight
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentMotionRenderingTest {
    private val gold = Color(0xFFE5A00D)

    @Test fun everyStyleRendersFiniteSdrAndHdrMotionForFillsAndStrokes() {
        val accents = listOf(gold, Color.Black, Color.White,
            uiHdrHighlight(Color(0xFF5DA9FF), 2f), uiHdrHighlight(Color.White, 2f))
        for (accent in accents) for (style in AccentStyle.entries) {
            val paints = AccentMotionPaints(accent, style)
            for (phase in listOf(.15f, .5f, .85f)) for (drawStyle in listOf(Fill, Stroke(2f))) {
                val rendered = outline(accent, style, paints, AccentMotionFrame(phase, .8f), drawStyle)
                try {
                    val solid = rendered.getColor(if (drawStyle == Fill) 40 else 4, 12)
                    assertTrue("$style $drawStyle at $phase", solid.alpha() > .99f)
                    for (x in listOf(4, 12, 40, 74)) for (y in listOf(4, 12, 19)) {
                        assertTrue("$style must keep finite HDR components",
                            rendered.getColor(x, y).components.all { it.isFinite() })
                    }
                } finally {
                    rendered.recycle()
                }
            }
        }
    }

    @Test fun everyStyleKeepsADistinctBorderForFocusAndSelection() {
        val focusedSignatures = linkedMapOf<List<Int>, AccentStyle>()
        val selectedSignatures = linkedMapOf<List<Int>, AccentStyle>()
        for (style in AccentStyle.entries) {
            val palette = controlPalette(AppSurface, AppSurfaceRaised, AppOnSurface,
                AppOnSurfaceMuted, gold, style)
            val focused = render {
                drawOutline(Outline.Rectangle(Rect(4f, 4f, 76f, 20f)),
                    palette.focusBorderBrush(), style = Stroke(3f))
            }
            val selected = render {
                drawOutline(Outline.Rectangle(Rect(4f, 4f, 76f, 20f)),
                    palette.selectedBorderBrush(), style = Stroke(2f))
            }
            try {
                // A sparse top/left-edge sample missed distinctions visible elsewhere on
                // the full 2–3px outline, falsely reporting identical material borders.
                fun Bitmap.signature() = buildList {
                    for (y in 2 until height - 2) for (x in 2 until width - 2) {
                        add(getColor(x, y).toArgb())
                    }
                }
                assertTrue("$style selected must remain thinner than focused", !focused.sameAs(selected))
                val focusedSignature = focused.signature()
                val selectedSignature = selected.signature()
                val identicalFocus = focusedSignatures.putIfAbsent(focusedSignature, style)
                val identicalSelection = selectedSignatures.putIfAbsent(selectedSignature, style)
                assertTrue("$style focus duplicated $identicalFocus", identicalFocus == null)
                assertTrue("$style selection duplicated $identicalSelection", identicalSelection == null)
            } finally {
                focused.recycle()
                selected.recycle()
            }
        }
        assertEquals("Focus materials must not collapse into a flat common border",
            AccentStyle.entries.size, focusedSignatures.size)
        assertEquals("Selection must also reflect the configured material",
            AccentStyle.entries.size, selectedSignatures.size)
    }

    @Test fun movingReflectionIsVisibleAboveTheOpaqueFocusBorder() {
        val bounds = Outline.Rectangle(Rect(4f, 4f, 76f, 20f))
        // Glow's gesture is its breathing halo, drawn by the control itself.
        for (style in AccentStyle.entries.filter { it != AccentStyle.GLOW }) {
            val paints = AccentMotionPaints(gold, style)
            val static = render {
                drawOutline(bounds, accentFocusBorderBrush(gold, style), style = Stroke(3f))
            }
            val moving = render {
                drawOutline(bounds, accentFocusBorderBrush(gold, style), style = Stroke(3f))
                // Match the production order: opaque base first, transient motion last.
                paints.drawOutline(this, bounds, AccentMotionFrame(.4f, 1f),
                    emphasis = 1f, drawStyle = Stroke(3f))
            }
            try {
                var strongestDifference = 0f
                for (y in 3..21) for (x in 3..77) {
                    val before = static.getColor(x, y)
                    val after = moving.getColor(x, y)
                    strongestDifference = maxOf(strongestDifference,
                        abs(before.red() - after.red()), abs(before.green() - after.green()),
                        abs(before.blue() - after.blue()))
                }
                assertTrue("$style focused border must show its optional moving reflection",
                    strongestDifference > .003f)
            } finally {
                static.recycle()
                moving.recycle()
            }
        }
    }

    @Test fun restAndInvalidFramesPreserveTheStaticMaterialExactlyAfterCachedMotion() {
        val inactive = listOf(null, AccentMotionFrame.Rest, AccentMotionFrame(.5f, 0f),
            AccentMotionFrame(.5f, -1f), AccentMotionFrame(Float.NaN, 1f),
            AccentMotionFrame(.5f, Float.NaN), AccentMotionFrame(Float.POSITIVE_INFINITY, 1f),
            AccentMotionFrame(.5f, Float.POSITIVE_INFINITY))
        for (accent in listOf(gold, uiHdrHighlight(Color(0xFF5DA9FF), 2f))) {
            for (style in AccentStyle.entries) {
                val paints = AccentMotionPaints(accent, style)
                outline(accent, style, paints, AccentMotionFrame(.8f, 1f)).recycle()
                val baseline = outline(accent, style, null, null)
                try {
                    for (frame in inactive) {
                        val actual = outline(accent, style, paints, frame)
                        try {
                            assertTrue("$style with $frame must be exactly static", baseline.sameAs(actual))
                        } finally {
                            actual.recycle()
                        }
                    }
                } finally {
                    baseline.recycle()
                }
            }
        }
    }

    @Test fun motionNeverChangesTheProgressFootprintForRtlOrInvalidFractions() {
        val fractions = listOf(-1f, 0f, .01f, .3f, .8f, 1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)
        for (style in AccentStyle.entries) {
            val paints = AccentMotionPaints(gold, style)
            for (fromRight in listOf(false, true)) for (fraction in fractions) {
                val baseline = progress(gold, style, null, null, fraction, fromRight)
                try {
                    for (phase in listOf(.2f, .8f)) {
                        val actual = progress(gold, style, paints, AccentMotionFrame(phase, 1f), fraction, fromRight)
                        try {
                            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                                assertEquals("$style fraction=$fraction rtl=$fromRight at ($x,$y)",
                                    baseline.getColor(x, y).alpha() > 0f, actual.getColor(x, y).alpha() > 0f)
                            }
                        } finally {
                            actual.recycle()
                        }
                    }
                } finally {
                    baseline.recycle()
                }
            }
        }
    }

    @Test fun cachedPaintsUpdateTheirTransformAndKeepFullTrackCoordinates() {
        for (style in AccentStyle.entries) {
            val paints = AccentMotionPaints(gold, style)
            val firstFrame = AccentMotionFrame(.2f, .8f)
            val laterFrame = AccentMotionFrame(.8f, .8f)
            val first = progress(gold, style, paints, firstFrame, 1f)
            val later = progress(gold, style, paints, laterFrame, 1f)
            val fresh = progress(gold, style, AccentMotionPaints(gold, style), laterFrame, 1f)
            val shorter = progress(gold, style, paints, laterFrame, .35f)
            val replay = progress(gold, style, paints, firstFrame, 1f)
            try {
                assertTrue("$style cached paints must match a fresh shader", later.sameAs(fresh))
                assertTrue("$style revisiting a frame must not accumulate transforms", first.sameAs(replay))
                if (style in listOf(AccentStyle.SATIN, AccentStyle.GLASS, AccentStyle.CHROME)) {
                    assertFalse("$style reflection must actually move", first.sameAs(later))
                }
                assertColorEquals("$style must not resize its reflection with progress",
                    later.getColor(12, 12), shorter.getColor(12, 12))
            } finally {
                listOf(first, later, fresh, shorter, replay).forEach(Bitmap::recycle)
            }
        }
    }

    @Test fun lightMotionChangesBrightnessWhileRemainingUniform() {
        val paints = AccentMotionPaints(gold, AccentStyle.LIGHT)
        val baseline = progress(gold, AccentStyle.LIGHT, paints, AccentMotionFrame.Rest, 1f)
        val moving = progress(gold, AccentStyle.LIGHT, paints, AccentMotionFrame(.5f, 1f), 1f)
        try {
            val center = moving.getColor(40, 12)
            for (x in listOf(12, 28, 52, 68)) {
                assertColorEquals("Light stays a solid color", center, moving.getColor(x, 12))
            }
            val original = baseline.getColor(40, 12)
            val difference = maxOf(abs(center.red() - original.red()), abs(center.green() - original.green()),
                abs(center.blue() - original.blue()))
            assertTrue("Light has a visible but small brightness gesture", difference in .001f..0.1f)
        } finally {
            baseline.recycle()
            moving.recycle()
        }
    }

    @Test fun whiteAccentsHaveVisibleMotionInEveryStyleIncludingHdr() {
        for (accent in listOf(Color.White, uiHdrHighlight(Color.White, 2f))) {
            for (style in AccentStyle.entries.filter { it != AccentStyle.GLOW }) {
                val paints = AccentMotionPaints(accent, style)
                val baseline = progress(accent, style, paints, AccentMotionFrame.Rest, 1f)
                val moving = progress(accent, style, paints, AccentMotionFrame(.5f, 1f), 1f)
                try {
                    var changed = false
                    for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                        val before = baseline.getColor(x, y)
                        val after = moving.getColor(x, y)
                        if (maxOf(abs(before.red() - after.red()), abs(before.green() - after.green()),
                                abs(before.blue() - after.blue())) > .0001f) changed = true
                    }
                    assertTrue("$style must remain visible with white in ${accent.colorSpace.name}", changed)
                } finally {
                    baseline.recycle()
                    moving.recycle()
                }
            }
        }
    }

    @Test fun glowLeavesBorderAndSurfaceToItsBreathingHalo() {
        val paints = AccentMotionPaints(gold, AccentStyle.GLOW)
        for (drawStyle in listOf(Fill, Stroke(3f))) {
            val baseline = outline(gold, AccentStyle.GLOW, null, null, drawStyle)
            val moving = outline(gold, AccentStyle.GLOW, paints, AccentMotionFrame(.5f, 1f), drawStyle)
            try {
                assertTrue("Glow draws no sheen ($drawStyle)", baseline.sameAs(moving))
            } finally {
                baseline.recycle()
                moving.recycle()
            }
        }
        assertEquals(0f, accentMotionPeakAlpha(AccentStyle.GLOW, stroke = true), 0f)
    }

    @Test fun everyAnimatedStyleHasItsOwnBorderGesture() {
        val bounds = Outline.Rectangle(Rect(4f, 4f, 76f, 20f))
        val gestures = linkedMapOf<List<Int>, AccentStyle>()
        for (style in AccentStyle.entries.filter { it != AccentStyle.GLOW }) {
            val paints = AccentMotionPaints(gold, style)
            // The gesture alone, on a transparent target: compare where and how it lights the border.
            val frames = listOf(.25f, .4f, .7f).map { phase ->
                render { paints.drawOutline(this, bounds, AccentMotionFrame(phase, 1f), drawStyle = Stroke(3f)) }
            }
            try {
                val signature = frames.flatMap { bitmap ->
                    buildList { for (y in 0 until HEIGHT step 2) for (x in 0 until WIDTH step 2) add(bitmap.getColor(x, y).toArgb()) }
                }
                val duplicate = gestures.putIfAbsent(signature, style)
                assertTrue("$style gesture duplicates $duplicate", duplicate == null)
            } finally {
                frames.forEach(Bitmap::recycle)
            }
        }
    }

    private fun outline(
        accent: Color,
        style: AccentStyle,
        paints: AccentMotionPaints?,
        frame: AccentMotionFrame?,
        drawStyle: DrawStyle = Fill,
    ): Bitmap = render {
        val outline = Outline.Rectangle(Rect(4f, 4f, 76f, 20f))
        drawOutline(outline, accentBrush(accent, style), style = drawStyle)
        paints?.drawOutline(this, outline, frame, drawStyle = drawStyle)
    }

    private fun progress(
        accent: Color,
        style: AccentStyle,
        paints: AccentMotionPaints?,
        frame: AccentMotionFrame?,
        fraction: Float,
        fromRight: Boolean = false,
    ): Bitmap = render {
        drawAccentProgress(accentBrush(accent, style), fraction, fromRight)
        if (paints != null) drawAccentProgressMotion(paints, frame, fraction, fromRight)
    }

    private fun render(draw: DrawScope.() -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGBA_F16, true,
            ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()),
            Size(WIDTH.toFloat(), HEIGHT.toFloat()), draw)
        return bitmap
    }

    private fun assertColorEquals(message: String, expected: android.graphics.Color, actual: android.graphics.Color) {
        assertEquals("$message red", expected.red(), actual.red(), .0001f)
        assertEquals("$message green", expected.green(), actual.green(), .0001f)
        assertEquals("$message blue", expected.blue(), actual.blue(), .0001f)
        assertEquals("$message alpha", expected.alpha(), actual.alpha(), .0001f)
    }

    private companion object {
        const val WIDTH = 80
        const val HEIGHT = 24
    }
}
