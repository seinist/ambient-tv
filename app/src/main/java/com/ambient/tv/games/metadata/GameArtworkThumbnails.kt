package com.ambient.tv.games.metadata

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.intercept.Interceptor
import coil3.key.Keyer
import coil3.request.ImageResult
import coil3.request.Options
import coil3.size.pxOrElse
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.Path.Companion.toOkioPath

/** A stored game cover drawn at card size; see [GameArtworkThumbInterceptor]. */
data class GameArtworkThumb(val file: File)

/**
 * Card-sized requests for stored game artwork read a small cached copy instead of the original.
 *
 * Covers are validated up to 8 MiB / 16 MP and Coil keeps no disk copy of local files, so every
 * memory-cache miss (opening the library again, scrolling back, a cache trimmed by a game)
 * decoded each full original again and the grid filled in card by card. The first card-sized
 * load now stores a downscaled copy in the shared image disk cache, like Files thumbnails.
 * Stored covers are written once under a new name, so the path alone identifies the picture.
 * Detail pages and other large requests keep the original.
 */
class GameArtworkThumbInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val file = gameArtworkLocalFile(chain.request.data) ?: return chain.proceed()
        val edge = maxOf(chain.size.width.pxOrElse { 0 }, chain.size.height.pxOrElse { 0 })
        if (edge !in 1..GAME_ARTWORK_THUMB_MAX_REQUEST) return chain.proceed()
        return chain.withRequest(chain.request.newBuilder().data(GameArtworkThumb(file)).build()).proceed()
    }
}

/**
 * A cover stored by [GameArtworkFiles] (File, absolute path or file URI), or null. Matched by
 * its app-owned layout rather than one absolute root: Android can name the same app storage
 * through different aliases (/data/user/0, /data/data) across process starts.
 */
internal fun gameArtworkLocalFile(data: Any?): File? {
    val path = when (data) {
        is File -> data.path
        is String -> when {
            data.startsWith("file://") -> android.net.Uri.parse(data).path
            data.startsWith("/") -> data
            else -> null
        }
        is android.net.Uri -> data.takeIf { it.scheme == "file" }?.path
        else -> null
    } ?: return null
    return if (storedArtworkPath.matches(path)) File(path) else null
}

class GameArtworkThumbKeyer : Keyer<GameArtworkThumb> {
    override fun key(data: GameArtworkThumb, options: Options): String = thumbKey(data.file)
}

class GameArtworkThumbFetcher(
    private val data: GameArtworkThumb,
    private val options: Options,
    private val imageLoader: ImageLoader,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val key = thumbKey(data.file)
        val diskCache = imageLoader.diskCache
        if (diskCache != null && options.diskCachePolicy.readEnabled) {
            diskCache.openSnapshot(key)?.let { snapshot ->
                return SourceFetchResult(
                    source = ImageSource(snapshot.data, diskCache.fileSystem, key, snapshot),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
        }
        val thumb = generation.withPermit { withContext(Dispatchers.IO) { makeGameArtworkThumb(data.file) } }
            ?: return original()
        if (diskCache != null && options.diskCachePolicy.writeEnabled) {
            diskCache.openEditor(key)?.let { editor ->
                runCatching {
                    diskCache.fileSystem.write(editor.data) { write(thumb) }
                    editor.commit()
                }.onFailure { editor.abort() }
            }
        }
        return SourceFetchResult(ImageSource(Buffer().write(thumb), options.fileSystem), null, DataSource.DISK)
    }

    /** Already small, unreadable here or not decodable: the original, as before. */
    private fun original(): FetchResult {
        if (!data.file.isFile) throw IOException("Game artwork is missing")
        return SourceFetchResult(ImageSource(data.file.toOkioPath(), options.fileSystem), null, DataSource.DISK)
    }

    class Factory : Fetcher.Factory<GameArtworkThumb> {
        override fun create(data: GameArtworkThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            GameArtworkThumbFetcher(data, options, imageLoader)
    }

    private companion object {
        /** A whole grid entering at once must not decode every original in parallel. */
        val generation = Semaphore(2)
    }
}

/** files/game_artwork/<SHA-256 of the ROM id>/<kind>-<uuid>.<ext>; no other directory or "..". */
private val storedArtworkPath = Regex("""^/[^\u0000]*/files/game_artwork/[0-9a-f]{64}/[A-Za-z0-9_.-]+$""")

/** Largest requested card edge served from the small copy; larger requests use the original. */
internal const val GAME_ARTWORK_THUMB_MAX_REQUEST = 720
/** Longest edge of the stored copy: never below a served request, so cards are not upscaled. */
internal const val GAME_ARTWORK_THUMB_EDGE = 720

private fun thumbKey(file: File) = "game-artwork-thumb:v1:${file.path}"

/** Downscaled, upright JPEG (PNG when transparent); null when the original is small enough already. */
internal fun makeGameArtworkThumb(file: File): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || longEdge <= GAME_ARTWORK_THUMB_EDGE) return null
    var sample = 1
    while (longEdge / (sample * 2) >= GAME_ARTWORK_THUMB_EDGE) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scale = GAME_ARTWORK_THUMB_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(decoded,
        (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        else decoded
    if (scaled !== decoded) decoded.recycle()
    val orientation = try {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
    val upright = applyOrientation(scaled, orientation)
    if (upright !== scaled) scaled.recycle()
    return ByteArrayOutputStream().use { out ->
        val transparent = upright.hasAlpha() && hasTransparentPixel(upright)
        upright.compress(if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, out)
        upright.recycle()
        out.toByteArray()
    }
}

private fun hasTransparentPixel(bitmap: Bitmap): Boolean {
    val row = IntArray(bitmap.width)
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        if (row.any { (it ushr 24) != 0xFF }) return true
    }
    return false
}

private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
