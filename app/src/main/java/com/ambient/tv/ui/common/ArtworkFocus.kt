package com.ambient.tv.ui.common

import coil3.request.allowHardware
import coil3.request.bitmapConfig
import android.content.Context
import android.graphics.Bitmap
import android.media.FaceDetector
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Crops so that the focus point ([x], [y] as fractions of the image) sits as close to the
 * container's center as the image bounds allow. Unlike a BiasAlignment this is independent of
 * how much of the image the crop leaves visible.
 */
@Immutable
data class FocusPointAlignment(val x: Float, val y: Float) : Alignment {
    override fun align(size: IntSize, space: IntSize, layoutDirection: LayoutDirection): IntOffset =
        IntOffset(axis(size.width, space.width, x), axis(size.height, space.height, y))

    private fun axis(content: Int, container: Int, focus: Float): Int =
        if (content <= container) {
            (container - content) / 2
        } else {
            (container / 2f - focus * content).roundToInt().coerceIn(container - content, 0)
        }
}

/** Faces in film backdrops mostly sit in the upper third; the image center usually cuts them. */
private val DefaultArtworkFocus = Offset(0.5f, 0.3f)

/** [ready] turns true once [alignment] is final, so the image can be shown without moving. */
@Immutable
data class ArtworkFocusCrop(val alignment: Alignment, val ready: Boolean)

/**
 * Crop for backdrops shown in a container of a different shape (wide banners, portrait phones).
 * Once the image has loaded and the crop is known to hide a large part of it ([visibleFraction] of
 * the cropped axis, null until loaded), faces are detected in a downscaled copy and the crop is
 * centered on them; otherwise it uses [DefaultArtworkFocus]. Tiles show the loaded image immediately; transitioning backdrops may wait for
 * [ArtworkFocusCrop.ready] to avoid a crop shift during their fade. Results are cached per image, so
 * revisited artwork is ready as soon as it has loaded.
 */
@Composable
fun rememberArtworkFocusCrop(model: String?, headers: Map<String, String>, visibleFraction: Float?, imageRevision: String? = null): ArtworkFocusCrop {
    val context = LocalContext.current.applicationContext
    val identity = remember(model, headers, imageRevision) { model?.let { artworkFocusKey(it, headers, imageRevision) } }
    var focus by remember(identity) { mutableStateOf(identity?.let { ArtworkFocusStore.cachedMemory(it) }) }
    var detectionDone by remember(identity) { mutableStateOf(false) }
    val worthDetecting = model != null && visibleFraction != null && visibleFraction < 0.85f
    LaunchedEffect(identity, worthDetecting) {
        if (!worthDetecting || focus != null) return@LaunchedEffect
        val imageModel = model ?: return@LaunchedEffect
        val imageKey = identity ?: return@LaunchedEffect
        // Database open/read and selective pruning must not block TV focus.
        focus = withContext(Dispatchers.IO) { ArtworkFocusStore.cached(context, imageKey) }
        // A slow device shows the default crop rather than holding the image back.
        if (focus == null) focus = withTimeoutOrNull(1_500) { ArtworkFocusStore.detect(context, imageModel, headers, imageKey) }
        detectionDone = true
    }
    val target = focus?.takeIf { it.isSpecified } ?: DefaultArtworkFocus
    val ready = visibleFraction != null && (!worthDetecting || focus != null || detectionDone)
    return ArtworkFocusCrop(FocusPointAlignment(target.x, target.y), ready)
}

/** Share of the image's cropped axis that [ContentScale.Crop] leaves visible in [container]. */
fun cropVisibleFraction(image: Size, container: IntSize): Float? {
    if (!image.isSpecified || image.width <= 0f || image.height <= 0f || container.width <= 0 || container.height <= 0) return null
    val imageRatio = image.width / image.height
    val containerRatio = container.width.toFloat() / container.height
    return minOf(imageRatio / containerRatio, containerRatio / imageRatio)
}

/** Per-image focus points. [Offset.Unspecified] records "analyzed, no faces". Keys are hashes,
 * so the image URLs (which can carry access tokens) never reach disk. */
private object ArtworkFocusStore {
    private const val ANALYSIS_SIZE = 640
    private val memory = LruCache<String, Offset>(256)
    private val analysisGate = Mutex()
    @Volatile private var disk: ArtworkFocusCache? = null

    private fun cache(context: Context): ArtworkFocusCache = disk ?: synchronized(this) {
        disk ?: ArtworkFocusCache(context).also { disk = it }
    }

    fun cachedMemory(key: String): Offset? = memory.get(key)

    fun cached(context: Context, key: String): Offset? {
        memory.get(key)?.let { return it }
        val stored = try { cache(context).get(key) } catch (error: android.database.sqlite.SQLiteException) {
            Timber.w("Artwork analysis cache read failed: %s", error.javaClass.simpleName)
            null
        } ?: return null
        val focus = if (stored.x == null || stored.y == null) Offset.Unspecified else Offset(stored.x, stored.y)
        memory.put(key, focus)
        return focus
    }

    suspend fun detect(context: Context, url: String, headers: Map<String, String>, key: String): Offset? = analysisGate.withLock {
        // Several visible layers can request one image. Do not decode/analyze it twice, and keep
        // face detection from competing with the two visible-artwork decoder slots.
        memory.get(key)?.let { return@withLock it }
        val focus = try {
            val bitmap = loadAnalysisBitmap(context, url, headers) ?: return@withLock null
            withContext(Dispatchers.Default) { faceFocus(bitmap) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Artwork face detection failed: %s", e.javaClass.simpleName)
            return@withLock null
        }
        withContext(Dispatchers.IO) {
            try {
                cache(context).put(key, if (focus.isSpecified) ArtworkFocusPoint(focus.x, focus.y) else ArtworkFocusPoint(null, null))
            } catch (error: android.database.sqlite.SQLiteException) {
                Timber.w("Artwork analysis cache write failed: %s", error.javaClass.simpleName)
            }
        }
        memory.put(key, focus)
        focus
    }

    private suspend fun loadAnalysisBitmap(context: Context, url: String, headers: Map<String, String>): Bitmap? {
        // Runs after the banner itself loaded, so this is served from Coil's disk cache.
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(ANALYSIS_SIZE)
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .requestHeaders(headers)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        return (result.image as? coil3.BitmapImage)?.bitmap
    }

    /** Weighted center of the dominant faces, or [Offset.Unspecified] when there are none. */
    private fun faceFocus(source: Bitmap): Offset {
        // FaceDetector only accepts RGB_565 bitmaps of even width.
        val evenWidth = source.width and 1.inv()
        val bitmap = when {
            evenWidth < 2 || source.height < 2 -> return Offset.Unspecified
            source.config == Bitmap.Config.RGB_565 && evenWidth == source.width -> source
            else -> Bitmap.createBitmap(source, 0, 0, evenWidth, source.height)
                .copy(Bitmap.Config.RGB_565, false)
        }
        val faces = arrayOfNulls<FaceDetector.Face>(8)
        val count = FaceDetector(bitmap.width, bitmap.height, faces.size).findFaces(bitmap, faces)
        val found = faces.take(count).filterNotNull()
            .filter { it.confidence() >= FaceDetector.Face.CONFIDENCE_THRESHOLD }
        val largest = found.maxOfOrNull { it.eyesDistance() } ?: return Offset.Unspecified
        // Background faces would pull the crop away from the leads.
        val dominant = found.filter { it.eyesDistance() >= largest * 0.45f }
        val point = android.graphics.PointF()
        var weightSum = 0f
        var x = 0f
        var y = 0f
        dominant.forEach { face ->
            face.getMidPoint(point)
            val eyes = face.eyesDistance()
            val weight = eyes * eyes
            x += point.x * weight
            // The midpoint lies between the eyes; the face's center is a bit lower.
            y += (point.y + eyes * 0.35f) * weight
            weightSum += weight
        }
        return Offset(
            (x / weightSum / bitmap.width).coerceIn(0f, 1f),
            (y / weightSum / bitmap.height).coerceIn(0f, 1f),
        )
    }

}
