package com.ambient.tv.data.telegram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
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
            part = try {
                runBlocking { repository.mediaPart(media.chatId, media.messageId, partStart) }
            } catch (error: Exception) {
                throw IOException("Telegram media read failed", error)
            }
            if (part.isEmpty() || part.size > TELEGRAM_MEDIA_PART_BYTES)
                throw IOException("Invalid Telegram media part")
        }
        val relative = (position - partStart).toInt()
        val count = minOf(length, part.size - relative, remaining.toInt().coerceAtMost(Int.MAX_VALUE))
        if (count <= 0) throw IOException("Incomplete Telegram media part")
        part.copyInto(buffer, offset, relative, relative + count)
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = sourceUri
    override fun close() {
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
