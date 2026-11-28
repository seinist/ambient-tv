package com.ambient.tv.games.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** A validated app-owned temporary image, not an arbitrary source path. */
class GameArtworkFile internal constructor(
    internal val file: File,
    val bytes: Long,
    internal val extension: String,
    /** Bounds-only decode performed once while staging, never while drawing a cover. */
    val width: Int,
    val height: Int,
)

internal enum class GameArtworkFailure { FORMAT, BYTES, DIMENSIONS }

/** Distinguishes a rejected image from storage/network errors without parsing diagnostics. */
internal class GameArtworkValidationException(val kind: GameArtworkFailure) : IOException(when (kind) {
    GameArtworkFailure.FORMAT -> "Artwork must be PNG, JPEG or WebP"
    GameArtworkFailure.BYTES -> "Artwork exceeds 8 MiB"
    GameArtworkFailure.DIMENSIONS -> "Artwork dimensions exceed the supported limit"
})

/**
 * Images are owned by a ROM identity. Two overlapping folders share that identity; different
 * ROMs own separate copies, so removal never deletes a user's original or another ROM's image.
 * No directories or files are touched by construction. Call all methods from an IO worker.
 */
class GameArtworkFiles(context: Context) {
    private val root = File(context.filesDir, "game_artwork")
    private val staging = File(context.cacheDir, "game_artwork_staging")

    fun stage(input: InputStream): GameArtworkFile {
        if (!staging.isDirectory && !staging.mkdirs() && !staging.isDirectory) throw IOException("Cannot create artwork staging directory")
        val file = File(staging, UUID.randomUUID().toString() + ".part")
        try {
            var count = 0L
            file.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                    if (count > MAX_IMAGE_BYTES) throw GameArtworkValidationException(GameArtworkFailure.BYTES)
                    output.write(buffer, 0, read)
                }
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val extension = when (options.outMimeType) {
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                else -> throw GameArtworkValidationException(GameArtworkFailure.FORMAT)
            }
            if (!validDimensions(options.outWidth, options.outHeight)) {
                throw GameArtworkValidationException(GameArtworkFailure.DIMENSIONS)
            }
            val (width, height) = displayedDimensions(file, options)
            return GameArtworkFile(file, count, extension, width, height)
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    fun discard(staged: GameArtworkFile) {
        if (staged.file.parentFile?.canonicalFile == staging.canonicalFile) staged.file.delete()
    }

    internal fun commit(fileId: String, kind: GameArtworkKind, staged: GameArtworkFile): String {
        require(staged.file.parentFile?.canonicalFile == staging.canonicalFile && staged.file.isFile)
        val directory = File(root, directoryKey(fileId))
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create artwork directory")
        require(directory.canonicalFile.parentFile == root.canonicalFile)
        val target = File(directory, "${kind.key}-${UUID.randomUUID()}.${staged.extension}")
        if (!staged.file.renameTo(target)) throw IOException("Cannot commit artwork")
        return target.absolutePath
    }

    /**
     * Upgrade only our previously validated local images. No arbitrary provider path, ROM or
     * network source is opened, and bounds decoding never allocates the full image bitmap.
     */
    internal fun dimensions(path: String): Pair<Int, Int>? {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val file = File(path)
        val directory = file.parentFile ?: return null
        if (!directory.name.matches(Regex("[0-9a-f]{64}")) ||
            directory.parentFile?.canonicalFile != root.canonicalFile ||
            directory.canonicalFile.parentFile != root.canonicalFile ||
            file.canonicalFile.parentFile != directory.canonicalFile ||
            !file.isFile || file.length() !in 1..MAX_IMAGE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return if (bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp") &&
            validDimensions(bounds.outWidth, bounds.outHeight)) displayedDimensions(file, bounds) else null
    }

    private fun displayedDimensions(file: File, bounds: BitmapFactory.Options): Pair<Int, Int> {
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
        val rotated = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE
        return if (rotated) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
    }

    internal fun removePath(path: String) {
        val file = File(path)
        // Only an image directly within an app-owned, digest-named ROM directory is eligible.
        val directory = file.parentFile ?: return
        if (directory.parentFile?.canonicalFile != root.canonicalFile ||
            !directory.name.matches(Regex("[0-9a-f]{64}")) ||
            directory.canonicalFile.parentFile != root.canonicalFile ||
            file.canonicalFile.parentFile != directory.canonicalFile) return
        file.delete()
        if (directory.list()?.isEmpty() == true) directory.delete()
    }

    internal fun remove(fileId: String) {
        val directory = File(root, directoryKey(fileId))
        if (directory.canonicalFile.parentFile != root.canonicalFile) return
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.canonicalFile.parentFile == directory.canonicalFile) file.delete()
        }
        directory.delete()
    }

    /** Recover abandoned images after a crash between file rename and the SQLite commit. */
    internal fun reconcile(paths: Set<String>) {
        // Android can expose the same app storage through different path aliases across
        // process starts. Compare file identities before treating a stored cover as orphaned.
        val retained = paths.mapTo(hashSetOf()) { File(it).canonicalPath }
        root.listFiles()?.forEach { directory ->
            if (!directory.name.matches(Regex("[0-9a-f]{64}")) ||
                directory.canonicalFile.parentFile != root.canonicalFile) return@forEach
            directory.listFiles()?.forEach { image ->
                if (image.canonicalPath !in retained) removePath(image.absolutePath)
            }
            if (directory.list()?.isEmpty() == true) directory.delete()
        }
        // Live stages are short-lived; don't disturb another request while reconciling.
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        staging.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        const val MAX_IMAGE_PIXELS = 16_777_216L
        const val CACHE_BYTES = 128L * 1024 * 1024

        fun validDimensions(width: Int?, height: Int?): Boolean =
            width != null && height != null && width in 1..8192 && height in 1..8192 &&
                width.toLong() * height <= MAX_IMAGE_PIXELS

        internal fun directoryKey(fileId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(fileId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
