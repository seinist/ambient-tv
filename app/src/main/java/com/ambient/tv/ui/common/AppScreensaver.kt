package com.ambient.tv.ui.common

import com.ambient.tv.ui.display.screensaverHdrBackground

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.player.backend.effectiveScreensaverQualityKey
import kotlinx.coroutines.delay

@Composable
fun AppScreensaver(
    modeKey: String,
    showClock: Boolean,
    showDate: Boolean,
    use24HourClock: Boolean,
    useArtwork: Boolean,
    artworkIntervalSeconds: Int,
    dimPercent: Int,
    qualityKey: String,
    modifier: Modifier = Modifier,
) {
    val performance = LocalPerformanceProfile.current
    val brightness = dimPercent.coerceIn(20, 100) / 100f
    // Sparsam: Economy budget for animated shaders (saved quality restored when leaving Low).
    val effectiveQualityKey = effectiveScreensaverQualityKey(qualityKey, performance.forceScreensaverEconomy)
    val hdrShaderActive = remember { mutableStateOf(false) }
    val hdrBackground = Modifier.screensaverHdrBackground(
        eligible = modeKey != "oled" && !performance.forceScreensaverEconomy,
        shaderActive = hdrShaderActive,
    )
    // The background shader already lifts highlights. A bitmap gain underneath it would be
    // applied twice and, because the shader skips pixels already above SDR white, out of order:
    // slightly darker pixels would end brighter than brighter ones. Bitmaps only take the plain
    // UI gain when the shader is not running ("Always HDR").
    val uiHeadroom = com.ambient.tv.ui.display.LocalUiHdrState.current?.headroom ?: 1f
    val bitmapHeadroom = if (hdrShaderActive.value) 1f else uiHeadroom
    Box(modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().then(hdrBackground)) {
            when (modeKey) {
                "media" -> MediaBackground(useArtwork, artworkIntervalSeconds, brightness, bitmapHeadroom)
                "photos" -> PhotoBackground(artworkIntervalSeconds, brightness, bitmapHeadroom)
                "oled" -> Unit
                else -> ShaderScene.forKey(modeKey)
                    ?.let { scene ->
                        ShaderBackground(
                            scene,
                            ShaderQuality.forKey(effectiveQualityKey),
                            brightness,
                        ) { AmbientBackground(brightness) }
                    }
                    ?: AmbientBackground(brightness)
            }
        }
        if (showClock) ScreensaverClock(showDate, use24HourClock, modeKey == "oled", brightness)
    }
}

@Composable
private fun ScreensaverClock(showDate: Boolean, use24HourClock: Boolean, oled: Boolean, brightness: Float) {
    // Only the minute display needs recomposition. Background motion uses its own draw clock.
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = LocalDateTime.now()
        }
    }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val timeFormatter = remember(locale, use24HourClock) {
        DateTimeFormatter.ofPattern(if (use24HourClock) "HH:mm" else "h:mm", locale)
    }
    val dateFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEEE · d. MMMM", locale) }
    val periodFormatter = remember(locale) { DateTimeFormatter.ofPattern("a", locale) }
    val timeText = remember(now, timeFormatter) { now.format(timeFormatter) }
    val dateText = remember(now.toLocalDate(), dateFormatter) { now.format(dateFormatter) }
    val periodText = if (use24HourClock) null else now.format(periodFormatter)
    val density = LocalDensity.current
    var clockSize by remember { mutableStateOf(IntSize.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val margin = with(density) { 28.dp.toPx() }
        val target = if (clockSize == IntSize.Zero) 0f to 0f else ScreensaverPresentation.offset(
            now.hour * 60 + now.minute, width, height,
            clockSize.width.toFloat(), clockSize.height.toFloat(), margin, oled,
        )
        val x by animateFloatAsState(target.first, tween(ScreensaverPresentation.DRIFT_MS, easing = FastOutSlowInEasing), label = "clockDriftX")
        val y by animateFloatAsState(target.second, tween(ScreensaverPresentation.DRIFT_MS, easing = FastOutSlowInEasing), label = "clockDriftY")
        val safeX = ((width - clockSize.width) / 2f - margin).coerceAtLeast(0f)
        val safeY = ((height - clockSize.height) / 2f - margin).coerceAtLeast(0f)
        val typeSize = (min(maxWidth.value * 0.18f / density.fontScale, maxHeight.value * 0.28f / density.fontScale))
            .coerceIn(32f, if (oled) 94f else 132f)
        ClockBlock(timeText, dateText, periodText, showDate, oled, brightness, typeSize,
            Modifier.align(Alignment.Center)
                // Also clamp the in-flight animation after a window/font-size change.
                .offset { IntOffset(x.coerceIn(-safeX, safeX).roundToInt(), y.coerceIn(-safeY, safeY).roundToInt()) }
                .onSizeChanged { clockSize = it })
    }
}

@Composable
private fun AmbientBackground(brightness: Float) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var started = 0L
        while (true) {
            withFrameNanos { frame ->
                if (started == 0L) started = frame
                phase.floatValue = ((frame - started) % 180_000_000_000L) / 180_000_000_000f * (2 * PI).toFloat()
            }
            // Slow ambient motion needs no 60/120-Hz render loop. At most about 30 updates/s.
            delay(32)
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = phase.floatValue
        drawRect(Color(0xFF080B10).copy(alpha = brightness))
        val p1 = Offset(size.width * (0.28f + 0.14f * sin(t)), size.height * (0.42f + 0.18f * cos(t)))
        val p2 = Offset(size.width * (0.72f + 0.12f * cos(t)), size.height * (0.56f + 0.15f * sin(t * 2)))
        drawRect(Brush.radialGradient(listOf(primary.copy(alpha = 0.20f * brightness), Color.Transparent), p1, size.minDimension * 0.72f))
        drawRect(Brush.radialGradient(listOf(secondary.copy(alpha = 0.13f * brightness), Color.Transparent), p2, size.minDimension * 0.66f))
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.40f)))
    }
}

@Composable
private fun MediaBackground(useArtwork: Boolean, intervalSeconds: Int, brightness: Float, hdrHeadroom: Float) {
    val artwork = ScreensaverArtworkRegistry.urls
    if (!useArtwork || artwork.isEmpty()) {
        AmbientBackground(brightness)
        return
    }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(intervalSeconds, artwork.size) {
        while (true) {
            delay(intervalSeconds.coerceIn(10, 300) * 1000L)
            index = (index + 1) % artwork.size.coerceAtLeast(1)
        }
    }
    val model = artwork.getOrNull(index % artwork.size)
    Crossfade(targetState = model, animationSpec = tween(2400), label = "screensaverArtwork") { url ->
        var loaded by remember(url) { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            // Failed/uncached artwork must not leave an empty black preview. No backend query.
            if (!loaded) AmbientBackground(brightness)
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = com.ambient.tv.ui.display.uiHdrBitmapColorFilter(hdrHeadroom),
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = brightness }
                    .drawWithContent { drawContent(); drawRect(Color.Black.copy(alpha = 0.30f)) },
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                Color.Black.copy(alpha = 0.10f), Color.Transparent, Color.Black.copy(alpha = 0.50f),
            ))))
        }
    }
}

/**
 * Photo screensaver: the chosen album/library (or the newest photos of all libraries), shuffled,
 * with a crossfade and a slow Ken Burns drift. Falls back to the ambient scene without photos.
 */
@Composable
private fun PhotoBackground(intervalSeconds: Int, brightness: Float, hdrHeadroom: Float) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val photos by produceState<List<com.ambient.tv.data.photos.PhotoEntry>?>(null) {
        val deps = com.ambient.tv.ui.photos.PhotoLaunch.deps(context)
        val key = deps.appPreferences().screensaverPhotoAlbumKey
        val repository = deps.photoRepository()
        val list = if (key.isBlank()) repository.recent(80)
        else repository.listRecursive(key, 600).getOrNull().orEmpty()
        value = list.filter { it.kind == com.ambient.tv.data.photos.PhotoKind.PHOTO }.shuffled()
    }
    val list = photos
    if (list.isNullOrEmpty()) {
        AmbientBackground(brightness)
        return
    }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }.coerceIn(640, 3840)
    val heightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }.coerceIn(360, 2160)
    var index by remember { mutableIntStateOf(0) }
    val holdMs = intervalSeconds.coerceIn(8, 300) * 1000L
    LaunchedEffect(list) {
        while (true) {
            delay(holdMs)
            index = (index + 1) % list.size
        }
    }
    Crossfade(targetState = list[index % list.size], animationSpec = tween(2000), label = "screensaverPhoto") { entry ->
        val zoom = remember(entry.id) { androidx.compose.animation.core.Animatable(1f) }
        LaunchedEffect(entry.id) { zoom.animateTo(1.12f, tween((holdMs + 2000).toInt(), easing = androidx.compose.animation.core.LinearEasing)) }
        val drift = if (entry.id.hashCode() and 1 == 0) 1f else -1f
        AsyncImage(
            model = coil3.request.ImageRequest.Builder(context).data(entry.imageModel(widthPx, heightPx)).size(widthPx, heightPx).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = com.ambient.tv.ui.display.uiHdrBitmapColorFilter(hdrHeadroom),
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = brightness
                scaleX = zoom.value
                scaleY = zoom.value
                translationX = drift * (zoom.value - 1f) * size.width * 0.25f
            }.drawWithContent { drawContent(); drawRect(Color.Black.copy(alpha = 0.18f)) },
        )
    }
}

@Composable
private fun ClockBlock(
    timeText: String, dateText: String, periodText: String?, showDate: Boolean,
    oled: Boolean, brightness: Float, typeSize: Float, modifier: Modifier = Modifier,
) {
    val timeColor = Color(0xFFF0F2F5).copy(alpha = (if (oled) 0.36f else 0.92f) * brightness)
    val detailColor = Color(0xFFD9E0E8).copy(alpha = (if (oled) 0.23f else 0.64f) * brightness)
    val face = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraLight,
        fontSize = typeSize.sp,
        lineHeight = (typeSize * 1.12f).sp,
        letterSpacing = (-typeSize * 0.025f).sp,
        fontFeatureSettings = "tnum",
        shadow = if (oled) null else Shadow(Color.Black.copy(alpha = 0.30f), Offset(0f, 2f), 14f),
    )
    Column(modifier.padding(horizontal = 24.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Crossfade(timeText, animationSpec = tween(500), label = "clockMinute") { text ->
                Text(text, style = face, color = timeColor, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
            }
            if (periodText != null) Text(periodText,
                modifier = Modifier.padding(start = 12.dp, bottom = (typeSize * 0.15f).dp),
                fontSize = (typeSize * 0.14f).coerceIn(12f, 18f).sp,
                fontWeight = FontWeight.Medium, letterSpacing = 1.sp, color = detailColor)
        }
        if (showDate) {
            Spacer(Modifier.height(8.dp))
            Text(dateText, fontSize = (typeSize * 0.13f).coerceIn(12f, 17f).sp,
                fontWeight = FontWeight.Normal, letterSpacing = 1.3.sp,
                color = detailColor, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
