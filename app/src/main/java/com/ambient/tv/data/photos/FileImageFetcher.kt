package com.ambient.tv.data.photos

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
import coil3.request.Options
import coil3.size.pxOrElse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * Loads Files images (SAF tree, SMB, NFS) for Coil.
 *
 * Files have no server-side thumbnails, so a grid would otherwise pull every original over the
 * network each time. Thumbnail-sized requests therefore produce a small upright JPEG once and keep
 * it in Coil's disk cache (keyed by path, size and modification time). All original reads are
 * bounded by [originals] so a large NAS folder cannot load dozens of multi-MB files at once.
 */
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
class FileImageFetcher(
    private val ref: FileImageRef,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val reader: () -> FilesPhotoSource,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val requested = maxOf(options.size.width.pxOrElse { 0 }, options.size.height.pxOrElse { 0 })
        return if (requested in 1..THUMB_MAX) thumbnail() else original()
    }

    private suspend fun original(): FetchResult {
        val bytes = originals.withPermit { reader().read(ref) }
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = com.ambient.tv.data.files.FilePaths.mime(ref.path).takeIf { it.startsWith("image/") },
            dataSource = DataSource.NETWORK,
        )
    }

    private suspend fun thumbnail(): FetchResult {
        val diskCache = imageLoader.diskCache
        val key = "file-thumb:${ref.sourceId}:${ref.path}:${ref.version}"
        if (diskCache != null && options.diskCachePolicy.readEnabled) {
            diskCache.openSnapshot(key)?.let { snapshot ->
                return SourceFetchResult(
                    source = ImageSource(snapshot.data, diskCache.fileSystem, key, snapshot),
                    mimeType = "image/jpeg",
                    dataSource = DataSource.DISK,
                )
            }
        }
        val original = originals.withPermit { reader().read(ref) }
        val thumb = withContext(Dispatchers.Default) { makeThumbnail(original) }
            ?: return SourceFetchResult(ImageSource(Buffer().write(original), options.fileSystem), null, DataSource.NETWORK)
        if (diskCache != null && options.diskCachePolicy.writeEnabled) {
            diskCache.openEditor(key)?.let { editor ->
                runCatching {
                    diskCache.fileSystem.write(editor.data) { write(thumb) }
                    editor.commit()
                }.onFailure { editor.abort() }
            }
        }
        return SourceFetchResult(ImageSource(Buffer().write(thumb), options.fileSystem), "image/jpeg", DataSource.NETWORK)
    }

    /** Downsampled, EXIF-rotated JPEG of at most [THUMB_EDGE] px; null when not decodable here. */
    private fun makeThumbnail(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val upright = applyOrientation(decoded, orientation)
        return ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
            out.toByteArray()
        }
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

    class Factory(private val reader: () -> FilesPhotoSource) : Fetcher.Factory<FileImageRef> {
        override fun create(data: FileImageRef, options: Options, imageLoader: ImageLoader): Fetcher =
            FileImageFetcher(data, options, imageLoader, reader)
    }

    private companion object {
        const val THUMB_MAX = 720
        const val THUMB_EDGE = 640
        /** Concurrent original reads from local/SMB/NFS storage. */
        val originals = Semaphore(3)
    }
}

/** Memory-cache key per image version (sizes are added by Coil). */
class FileImageKeyer : coil3.key.Keyer<FileImageRef> {
    override fun key(data: FileImageRef, options: Options): String = "file-image:${data.sourceId}:${data.path}:${data.version}"
}
