package com.ambient.tv.data.telegram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

/** Seekable Media3 transport. The authenticated Monogram port owns MTProto, session and DRM policy. */
@UnstableApi
class TelegramVideoDataSource(
    private val repository: TelegramRepository,
    private val media: TelegramMedia,
    private val accountRevision: Long = repository.playbackRevision(),
    private val mediaRevision: Long = repository.playbackMediaRevision(media),
) : BaseDataSource(false) {
    class Factory(private val repository: TelegramRepository, private val media: TelegramMedia) :
        DataSource.Factory {
        private val revision = repository.playbackRevision()
        private val mediaRevision = repository.playbackMediaRevision(media)
        override fun createDataSource(): DataSource = TelegramVideoDataSource(repository, media, revision, mediaRevision)
    }
    private fun requireCurrentAccount() {
        if (!repository.isPlaybackMediaCurrent(media, accountRevision, mediaRevision))
            throw IOException("Monogram account or media changed")
    }

    private var sourceUri: Uri? = null
    private var identityValidated = false
    private var position = 0L
    private var remaining = 0L
    private var partStart = -1L
    private var part = ByteArray(0)
    /** Bounded sequential read-ahead; random seeks discard the previous window. */
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetch = LinkedHashMap<Long, Deferred<ByteArray>>()
    private val prefetchGroups = ArrayList<Deferred<List<ByteArray>>>()
    private var previousPart = -1L
    private val readAhead = TelegramReadAhead(media)
    private var window = 1

    override fun open(dataSpec: DataSpec): Long {
        requireCurrentAccount()
        if (!media.playableOnTelegram) throw IOException("Protected or externally hosted media")
        if (media.fileSize <= 0) throw IOException("Telegram media size unknown")
        if (dataSpec.position > media.fileSize) throw IOException("Seek beyond Telegram file end")
        if (!identityValidated) {
            try { runBlocking { repository.validatePlaybackMedia(media, accountRevision, mediaRevision) } }
            catch (failure: Exception) { throw IOException("Monogram media identity validation failed", failure) }
            identityValidated = true
        }
        cancelPrefetch()
        previousPart = -1L
        readAhead.reset()
        window = 1
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) media.fileSize - position
        else minOf(dataSpec.length, media.fileSize - position)
        sourceUri = dataSpec.uri
        part = ByteArray(0)
        partStart = -1L
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        // Warm the exact requested first block as the extractor starts.
        // On an immediate read the repository coalesces or promotes it.
        if (remaining > 0) {
            val first = telegramPartOffset(position)
            prefetch[first] = prefetchScope.async {
                requireCurrentAccount()
                repository.mediaPart(media.chatId, media.messageId, first,
                    telegramExpectedPartSize(media.fileSize, first), accountRevision, speculative = true,
                    nativeMediaKey = media.nativeMediaKey, expectedMediaRevision = mediaRevision)
            }
        }
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        requireCurrentAccount()
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (sourceUri == null) throw IOException("Data source closed")
        if (partStart < 0 || position !in partStart until (partStart + part.size)) {
            val start = telegramPartOffset(position)
            val loaded = fetchPart(start)
            if (loaded.size != telegramExpectedPartSize(media.fileSize, start))
                throw IOException("Incomplete Telegram media part")
            // Publish offset and bytes together only after a successful validated load.
            part = loaded
            partStart = start
            schedulePrefetch(partStart)
        }
        val relative = (position - partStart).toInt()
        val count = minOf(length, part.size - relative, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (count <= 0) throw IOException("Incomplete Telegram media part")
        requireCurrentAccount()
        part.copyInto(buffer, offset, relative, relative + count)
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    private fun fetchPart(offset: Long): ByteArray {
        val sequential = previousPart >= 0L && offset - previousPart == TELEGRAM_MEDIA_PART_BYTES.toLong()
        val pending = prefetch.remove(offset)
        if (!sequential) {
            // open() has already cancelled the old window; keep its exact start
            // warmup alive until the foreground joins the same repository key.
            if (previousPart >= 0L) cancelPrefetch()
            readAhead.reset()
        }
        previousPart = offset
        val began = System.nanoTime()
        return try {
            runBlocking {
                // A failed read-ahead is retried directly instead of failing playback.
                // An unfinished speculative job may be queued on the two-lane
                // gate. Join the repository from the foreground instead.
                val buffered = pending?.takeIf { it.isCompleted }?.let { runCatching { it.await() }.getOrNull() }
                coroutineContext.ensureActive()
                requireCurrentAccount()
                try {
                    buffered ?: repository.mediaPart(media.chatId, media.messageId, offset,
                        telegramExpectedPartSize(media.fileSize, offset), accountRevision,
                        nativeMediaKey = media.nativeMediaKey, expectedMediaRevision = mediaRevision)
                } finally { pending?.cancel() }
            }.also { window = readAhead.observe(sequential, System.nanoTime() - began) }
        } catch (error: Exception) {
            throw IOException("Telegram media read failed", error)
        }
    }

    private fun schedulePrefetch(start: Long) {
        prefetchGroups.removeAll { it.isCompleted }
        val offsets = (1..window).map { start + it.toLong() * TELEGRAM_MEDIA_PART_BYTES }
            .takeWhile { it < media.fileSize && it < position + remaining }
            .filterNot { it in prefetch }
        val pair = offsets.take(2).takeIf { it.size == 2 && it[1] - it[0] == TELEGRAM_MEDIA_PART_BYTES.toLong() }
        if (pair != null) {
            val group = prefetchScope.async {
                requireCurrentAccount()
                repository.prefetchAdjacent(media, pair[0], accountRevision, mediaRevision)
            }
            prefetchGroups.add(group)
            pair.forEachIndexed { index, at -> prefetch[at] = prefetchScope.async { group.await()[index] } }
        }
        for (offset in offsets) {
            if (offset !in prefetch) prefetch[offset] = prefetchScope.async {
                requireCurrentAccount()
                repository.mediaPart(media.chatId, media.messageId, offset, telegramExpectedPartSize(media.fileSize, offset), accountRevision, speculative = true, nativeMediaKey = media.nativeMediaKey, expectedMediaRevision = mediaRevision)
            }
        }
    }

    private fun cancelPrefetch() {
        prefetch.values.forEach { it.cancel() }
        prefetch.clear()
        prefetchGroups.forEach { it.cancel() }
        prefetchGroups.clear()
    }

    override fun getUri(): Uri? = sourceUri
    override fun close() {
        cancelPrefetch()
        val hadSource = sourceUri != null
        sourceUri = null
        part = ByteArray(0)
        partStart = -1L
        remaining = 0L
        if (hadSource) transferEnded()
    }
}

const val TELEGRAM_MEDIA_PART_BYTES = 512 * 1024
fun telegramPartOffset(position: Long): Long {
    require(position >= 0)
    return position / TELEGRAM_MEDIA_PART_BYTES * TELEGRAM_MEDIA_PART_BYTES
}

internal fun telegramExpectedPartSize(size: Long, offset: Long): Int =
    (size - offset).coerceIn(0L, TELEGRAM_MEDIA_PART_BYTES.toLong()).toInt()


/**
 * A small bounded adaptive window. Use the actual consumption bitrate when
 * available and foreground wait time, rather than blindly opening more lanes
 * on every sequential part. Random seeks reset the estimator.
 */
internal class TelegramReadAhead(
    media: TelegramMedia,
    private val maxWindow: Int = if (Runtime.getRuntime().maxMemory() < 192L * 1024 * 1024) 2 else 3,
) {
    private val bytesPerSecond = if (media.durationMs > 0L && media.fileSize > 0L)
        media.fileSize.toDouble() * 1000.0 / media.durationMs else 0.0
    private var waitMs = 0.0
    private var consecutive = 0

    fun reset() { waitMs = 0.0; consecutive = 0 }

    fun observe(sequential: Boolean, elapsedNanos: Long): Int {
        if (!sequential) {
            reset()
            return 1
        }
        val sample = (elapsedNanos.coerceAtLeast(0L) / 1_000_000.0).coerceAtMost(2_000.0)
        waitMs = if (consecutive == 0) sample else waitMs * 0.75 + sample * 0.25
        consecutive++
        // A 350ms cushion plus twice the observed wait. Keep two-part
        // read-ahead for sustained unknown-bitrate sequential streams.
        val requested = if (bytesPerSecond > 0.0) {
            kotlin.math.ceil(bytesPerSecond * (0.35 + waitMs / 500.0) / TELEGRAM_MEDIA_PART_BYTES).toInt()
        } else if (consecutive >= 2 && waitMs >= 20.0) 3 else 2
        return requested.coerceIn(1, maxWindow.coerceIn(1, 3))
    }
}
