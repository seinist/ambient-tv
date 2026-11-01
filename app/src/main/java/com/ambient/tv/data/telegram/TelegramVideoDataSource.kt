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
import kotlinx.coroutines.runBlocking

/** Seekable Media3 transport. The authenticated Monogram port owns MTProto, session and DRM policy. */
@UnstableApi
class TelegramVideoDataSource(
    private val repository: TelegramRepository,
    private val media: TelegramMedia,
) : BaseDataSource(false) {
    class Factory(private val repository: TelegramRepository, private val media: TelegramMedia) :
        DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramVideoDataSource(repository, media)
    }

    private var sourceUri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var partStart = -1L
    private var part = ByteArray(0)
    /** One-part read-ahead: the next 512 KiB MTProto part downloads while this one is decoded. */
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetch: Pair<Long, Deferred<ByteArray>>? = null

    override fun open(dataSpec: DataSpec): Long {
        if (!media.playableOnTelegram) throw IOException("Protected or externally hosted media")
        if (media.fileSize <= 0) throw IOException("Telegram media size unknown")
        if (dataSpec.position > media.fileSize) throw IOException("Seek beyond Telegram file end")
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) media.fileSize - position
        else minOf(dataSpec.length, media.fileSize - position)
        sourceUri = dataSpec.uri
        part = ByteArray(0)
        partStart = -1L
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (sourceUri == null) throw IOException("Data source closed")
        if (partStart < 0 || position !in partStart until (partStart + part.size)) {
            partStart = telegramPartOffset(position)
            part = fetchPart(partStart)
            if (part.isEmpty() || part.size > TELEGRAM_MEDIA_PART_BYTES)
                throw IOException("Invalid Telegram media part")
            schedulePrefetch(partStart + TELEGRAM_MEDIA_PART_BYTES)
        }
        val relative = (position - partStart).toInt()
        val count = minOf(length, part.size - relative, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (count <= 0) throw IOException("Incomplete Telegram media part")
        part.copyInto(buffer, offset, relative, relative + count)
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    private fun fetchPart(offset: Long): ByteArray {
        val pending = prefetch?.takeIf { it.first == offset }?.second
        if (pending == null) cancelPrefetch() else prefetch = null
        return try {
            runBlocking {
                // A failed read-ahead is retried directly instead of failing playback.
                pending?.let { runCatching { it.await() }.getOrNull() }
                    ?: repository.mediaPart(media.chatId, media.messageId, offset)
            }
        } catch (error: Exception) {
            throw IOException("Telegram media read failed", error)
        }
    }

    private fun schedulePrefetch(offset: Long) {
        if (offset >= media.fileSize || offset >= position + remaining) return
        prefetch = offset to prefetchScope.async {
            repository.mediaPart(media.chatId, media.messageId, offset)
        }
    }

    private fun cancelPrefetch() {
        prefetch?.second?.cancel()
        prefetch = null
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
