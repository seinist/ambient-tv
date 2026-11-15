package com.ambient.tv.ui.theme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.ambient.tv.ui.common.artworkWashAccent
import com.ambient.tv.ui.display.uiHdrHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentMaterialRenderingTest {
    @Test fun everyStyleRendersWithSdrAndHdrAccents() {
        val accents = listOf(
            Color(0xFFE5A00D), Color.Black, Color.White,
            uiHdrHighlight(Color(0xFF5DA9FF), 2f), uiHdrHighlight(Color.White, 2f),
        )
        for (accent in accents) for (style in AccentStyle.entries) {
            val bitmap = render(accent, style)
            try {
                val pixel = bitmap.getColor(40, 12)
                assertTrue("$style must draw an opaque surface", pixel.alpha() > .99f)
                assertTrue("$style must keep finite HDR components", pixel.components.all { it.isFinite() })
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun lightStaysFlatAndRetainsHdrBrightness() {
        val accent = uiHdrHighlight(Color(0xFF5DA9FF), 2f)
        val expected = accent.convert(ColorSpaces.LinearExtendedSrgb)
        val bitmap = render(accent, AccentStyle.LIGHT)
        try {
            for (x in listOf(5, 40, 74)) {
                val pixel = bitmap.getColor(x, 12)
                assertEquals(expected.red, pixel.red(), .005f)
                assertEquals(expected.green, pixel.green(), .005f)
                assertEquals(expected.blue, pixel.blue(), .005f)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun artworkWashCapsBrightnessWithoutChangingLinearHueRatios() {
        val accent = Color(1.8f, .9f, .45f, .7f, ColorSpaces.LinearExtendedSrgb)
        val wash = artworkWashAccent(accent).convert(ColorSpaces.LinearExtendedSrgb)
        assertTrue(maxOf(wash.red, wash.green, wash.blue) <= 1.001f)
        assertEquals(.5f, wash.green / wash.red, .005f)
        assertEquals(.25f, wash.blue / wash.red, .005f)
        assertEquals(accent.alpha, wash.alpha, .005f)
    }

    @Test fun artworkWashPreservesAlreadyBoundedColorsExactly() {
        for (accent in listOf(Color.Black, Color.White, Color(0xFFE5A00D),
            Color(.3f, .2f, .1f, .5f, ColorSpaces.LinearExtendedSrgb))) {
            assertEquals(accent, artworkWashAccent(accent))
        }
    }

    @Test fun progressRevealsAStationaryMaterialWithoutRecoloringExistingPixels() {
        for (style in AccentStyle.entries) {
            val short = render(Color(0xFFE5A00D), style, .35f)
            val long = render(Color(0xFFE5A00D), style, .8f)
            try {
                val before = short.getColor(12, 12)
                val after = long.getColor(12, 12)
                assertEquals("$style red must stay anchored", before.red(), after.red(), .001f)
                assertEquals("$style green must stay anchored", before.green(), after.green(), .001f)
                assertEquals("$style blue must stay anchored", before.blue(), after.blue(), .001f)
                assertEquals(0f, short.getColor(50, 12).alpha(), .001f)
                assertTrue(long.getColor(50, 12).alpha() > .99f)
            } finally {
                short.recycle()
                long.recycle()
            }
        }
    }

    private fun render(accent: Color, style: AccentStyle, fraction: Float? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(80, 24, Bitmap.Config.RGBA_F16, true,
            ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(80f, 24f),
        ) {
            val brush = accentBrush(accent, style)
            if (fraction == null) drawRect(brush)
            else drawAccentProgress(brush, fraction)
        }
        return bitmap
    }
}
