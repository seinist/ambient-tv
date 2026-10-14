package com.ambient.tv.branding

import android.content.res.AssetManager
import android.graphics.Picture
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Vector drawing commands are cached by SVG and accent, never rasterized to a PNG. */
private object BrandSvgPictures {
    private val pictures = LruCache<String, Picture>(12)
    private val sources = LruCache<String, String>(3)

    fun cached(asset: String, color: String): Picture? = pictures.get("$asset:$color")

    suspend fun load(assets: AssetManager, asset: String, color: String): Picture =
        withContext(Dispatchers.IO) {
            cached(asset, color) ?: run {
                val source = sources.get(asset) ?: assets.open("branding/$asset")
                    .bufferedReader().use { it.readText() }.also { sources.put(asset, it) }
                // Only explicit currentColor tokens change. This also preserves the
                // white wordmark and dark lettering inside the colored TV badge.
                SVG.getFromString(source.replace("currentColor", color))
                    .renderToPicture()
                    .also { pictures.put("$asset:$color", it) }
            }
        }
}

@Composable
internal fun rememberBrandSvgPainter(asset: String, tint: Color): Painter {
    val assets = LocalContext.current.assets
    val color = "#" + (tint.toArgb() and 0x00ffffff).toString(16).padStart(6, '0')
    val picture by produceState(BrandSvgPictures.cached(asset, color), assets, asset, color) {
        try {
            value = BrandSvgPictures.load(assets, asset, color)
        } catch (error: IOException) {
            Timber.e(error, "Could not load branding SVG: %s", asset)
        } catch (error: SVGParseException) {
            Timber.e(error, "Could not parse branding SVG: %s", asset)
        }
    }
    return remember(picture) { BrandSvgPainter(picture) }
}

private class BrandSvgPainter(private val picture: Picture?) : Painter() {
    override val intrinsicSize: Size = picture?.let {
        Size(it.width.toFloat(), it.height.toFloat())
    } ?: Size.Unspecified

    override fun DrawScope.onDraw() {
        val drawing = picture ?: return
        if (drawing.width <= 0 || drawing.height <= 0) return
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val checkpoint = native.save()
            try {
                native.scale(size.width / drawing.width, size.height / drawing.height)
                native.drawPicture(drawing)
            } finally {
                native.restoreToCount(checkpoint)
            }
        }
    }
}
