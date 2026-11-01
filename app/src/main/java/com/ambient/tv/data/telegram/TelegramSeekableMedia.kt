package com.ambient.tv.data.telegram

import com.ambient.tv.data.files.SeekableMedia
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * Adapts authenticated MTProto ranges to Ambient TV's existing private MediaFileServer.
 * A handle lives on a file-server worker, never the Compose/main thread.
 * No account credentials or Telegram file identifiers are exposed by its loopback URL.
 */
internal class TelegramSeekableMedia(
    private val repository: TelegramRepository,
    private val media: TelegramMedia,
) : SeekableMedia {
    override val size: Long = media.fileSize
    private var partStart = -1L
    private var part = ByteArray(0)

    init {
        require(size > 0L && media.playableOnTelegram) { "Unavailable Monogram media" }
    }

    @Synchronized
    override fun read(position: Long, buffer: ByteArray, count: Int): Int {
        if (position < 0 || count < 0) throw IOException("Invalid Monogram media range")
        if (position >= size) return -1
        if (count == 0) return 0
        var copied = 0
        val wanted = minOf(count.toLong(), size - position).toInt()
        while (copied < wanted) {
            val at = position + copied
            if (partStart < 0 || at < partStart || at >= partStart + part.size) {
                partStart = telegramPartOffset(at)
                part = try {
                    runBlocking { repository.mediaPart(media.chatId, media.messageId, partStart) }
                } catch (error: Exception) {
                    throw IOException("Authenticated Monogram read failed", error)
                }
                if (part.isEmpty() || part.size > TELEGRAM_MEDIA_PART_BYTES) {
                    throw IOException("Incomplete Monogram media range")
                }
            }
            val within = (at - partStart).toInt()
            val amount = minOf(wanted - copied, part.size - within)
            if (amount <= 0) throw IOException("Invalid Monogram media part")
            part.copyInto(buffer, copied, within, within + amount)
            copied += amount
        }
        return copied
    }

    @Synchronized
    override fun close() {
        part = ByteArray(0)
        partStart = -1L
    }
}
