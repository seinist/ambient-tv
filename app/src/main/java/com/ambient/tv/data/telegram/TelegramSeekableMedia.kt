package com.ambient.tv.data.telegram

import com.ambient.tv.data.files.SeekableMedia
import java.io.IOException

/**
 * Adapts authenticated MTProto ranges to Ambient TV's existing private MediaFileServer.
 * A handle lives on a file-server worker, never the Compose/main thread.
 * No account credentials or Telegram file identifiers are exposed by its loopback URL.
 */
internal class TelegramSeekableMedia(
    private val media: TelegramMedia,
    private val partLoader: (Long) -> ByteArray,
    private val authorized: () -> Boolean,
    private val prefetchLoader: (Long) -> ByteArray = partLoader,
) : SeekableMedia {
    override val size: Long = media.fileSize
    private var partStart = -1L
    private var part = ByteArray(0)
    /**
     * Read-ahead like the Media3 transport: the next MTProto part downloads while this one is
     * served. Without it VLC/MPV got one part per round trip (the file server's parallel readers
     * serialised on the single part above), a few MB/s at most for a large file.
     */
    private val prefetcher = java.util.concurrent.Executors.newFixedThreadPool(3) { work ->
        Thread(work, "monogram-media-prefetch").apply { isDaemon = true }
    }
    private val prefetch = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.Future<ByteArray>>()
    private val lifecycle = Any()
    @Volatile private var closed = false
    private var readingThread: Thread? = null
    private fun checkOpen() {
        if (closed || !authorized()) throw IOException("Monogram session is no longer authorized")
    }
    private var previousPart = -1L
    private val readAhead = TelegramReadAhead(media)
    private var window = 1

    init {
        require(size > 0L && media.playableOnTelegram) { "Unavailable Monogram media" }
    }

    @Synchronized
    override fun read(position: Long, buffer: ByteArray, count: Int): Int {
        synchronized(lifecycle) {
            checkOpen()
            readingThread = Thread.currentThread()
        }
        try {
        if (position < 0 || count < 0 || count > buffer.size) throw IOException("Invalid Monogram media range")
        if (position >= size) return -1
        if (count == 0) return 0
        var copied = 0
        val wanted = minOf(count.toLong(), size - position).toInt()
        while (copied < wanted) {
            val at = position + copied
            if (partStart < 0 || at < partStart || at >= partStart + part.size) {
                val start = telegramPartOffset(at)
                val loaded = try {
                    loadPart(start)
                } catch (error: Exception) {
                    throw IOException("Authenticated Monogram read failed", error)
                }
                if (loaded.size != telegramExpectedPartSize(size, start)) {
                    throw IOException("Incomplete Monogram media range")
                }
                part = loaded
                partStart = start
                schedulePrefetch(partStart)
            }
            val within = (at - partStart).toInt()
            val amount = minOf(wanted - copied, part.size - within)
            if (amount <= 0) throw IOException("Invalid Monogram media part")
            part.copyInto(buffer, copied, within, within + amount)
            copied += amount
        }
        checkOpen()
        return copied
        } finally {
            synchronized(lifecycle) {
                readingThread = null
                if (closed) { part = ByteArray(0); partStart = -1L }
            }
        }
    }

    /** The prefetched part when it is the wanted one; a failed read-ahead is simply loaded again. */
    private fun loadPart(offset: Long): ByteArray {
        val sequential = previousPart >= 0L && offset - previousPart == TELEGRAM_MEDIA_PART_BYTES.toLong()
        if (!sequential) {
            cancelPrefetch()
            readAhead.reset()
        }
        previousPart = offset
        val began = System.nanoTime()
        val loaded = loadCurrentPart(offset)
        window = readAhead.observe(sequential, System.nanoTime() - began)
        return loaded
    }

    private fun loadCurrentPart(offset: Long): ByteArray {
        val pending = prefetch.remove(offset)
        if (pending != null) {
            // The production foreground loader coalesces with an active RPC,
            // without waiting for speculative admission. Standalone callers with
            // a single loader retain the original one-request behavior.
            if (!pending.isDone && partLoader !== prefetchLoader) {
                try { checkOpen(); return partLoader(offset) } finally { pending.cancel(true) }
            }
            try { return pending.get() }
            catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
            catch (_: java.util.concurrent.ExecutionException) { }
            catch (_: java.util.concurrent.CancellationException) { }
        }
        checkOpen()
        return partLoader(offset)
    }

    private fun schedulePrefetch(start: Long) {
        synchronized(lifecycle) {
        if (closed || prefetcher.isShutdown) return
        for (i in 1..window) {
            val offset = start + i.toLong() * TELEGRAM_MEDIA_PART_BYTES
            if (offset >= size) break
            if (!prefetch.containsKey(offset)) prefetch[offset] = prefetcher.submit(java.util.concurrent.Callable {
                checkOpen()
                prefetchLoader(offset)
            })
        }
        }
    }

    private fun cancelPrefetch() {
        prefetch.values.forEach { it.cancel(true) }
        prefetch.clear()
        (prefetcher as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }

    override fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            cancelPrefetch()
            prefetcher.shutdownNow()
            readingThread?.takeIf { it !== Thread.currentThread() }?.interrupt()
            if (readingThread == null) { part = ByteArray(0); partStart = -1L }
        }
        // Never acquire the read monitor here: a native RPC can be blocked.
        // The last read owns its byte array until it observes the closed flag.
    }
}
