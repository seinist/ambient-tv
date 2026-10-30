package com.ambient.tv.ui.common

import androidx.compose.runtime.collectAsState
import coil3.request.allowHardware
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.ambient.tv.ui.theme.LocalAccentModeKey
import com.ambient.tv.ui.theme.Gold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A lightweight artwork accent sampler. It samples the decoded Coil bitmap sparsely, ignores
 * very dark/washed-out pixels and returns the most saturated useful color. The sampler uses a
 * bounded software thumbnail; Coil can reuse encoded disk/network data from artwork requests. */
@Composable
fun rememberArtworkAccent(model: Any?): Color {
    return rememberArtworkSampledAccent(
        model = model,
        enabled = LocalAccentModeKey.current == "artwork",
        fallback = Gold,
    )
}

/** Samples artwork for a local visual treatment, independently of the global accent setting. */
@Composable
fun rememberArtworkSampledAccent(
    model: Any?,
    enabled: Boolean,
    fallback: Color,
    headers: Map<String, String> = emptyMap(),
): Color {
    if (!enabled || model == null) return fallback
    val context = LocalContext.current
    val painterModel = remember(model, headers, context) {
        (if (model is ImageRequest) model.newBuilder(context) else ImageRequest.Builder(context).data(model))
            .size(96, 64)
            .precision(coil3.size.Precision.EXACT)
            .allowHardware(false)
            .requestHeaders(artworkHeadersForModel(model, headers))
            .build()
    }
    val painter = rememberAsyncImagePainter(model = painterModel)
    var accent by remember(model, headers, fallback) { mutableStateOf(fallback) }
    val state by painter.state.collectAsState()
    LaunchedEffect(state, model, headers, fallback) {
        val success = state as? AsyncImagePainter.State.Success ?: return@LaunchedEffect
        val bitmap = (success.result.image as? coil3.BitmapImage)?.bitmap ?: return@LaunchedEffect
        accent = withContext(Dispatchers.Default) {
            runCatching {
                val map = bitmap.asImageBitmap().toPixelMap()
                val stepX = (map.width / 12).coerceAtLeast(1)
                val stepY = (map.height / 8).coerceAtLeast(1)
                var best = fallback
                var bestScore = 0f
                var y = 0
                while (y < map.height) {
                    var x = 0
                    while (x < map.width) {
                        val c = map[x, y]
                        val max = maxOf(c.red, c.green, c.blue)
                        val min = minOf(c.red, c.green, c.blue)
                        val saturation = if (max <= 0f) 0f else (max - min) / max
                        val luminance = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
                        val score = saturation * (1f - kotlin.math.abs(luminance - 0.55f))
                        if (luminance in 0.20f..0.86f && saturation > 0.22f && score > bestScore) {
                            best = c.copy(alpha = 1f)
                            bestScore = score
                        }
                        x += stepX
                    }
                    y += stepY
                }
                best
            }.getOrDefault(fallback)
        }
    }
    return com.ambient.tv.ui.theme.accessibleAccent(accent, androidx.tv.material3.MaterialTheme.colorScheme.surface)
}
