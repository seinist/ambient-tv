package com.ambient.tv.games

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.ambient.tv.data.files.FileAccess
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.files.SeekableMedia
import com.swordfish.libretrodroid.VirtualFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.IOException
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One open, read-only libretro content file. No ROM bytes are copied to the app's storage or
 * loaded into a ByteArray. The FD is owned by LibretroDroid after it detaches it; until then this
 * object closes it on cancellation. The optional proxy's onRelease closes the remote connection.
 */
class GameDirectContent internal constructor(
    val virtualFiles: List<VirtualFile>,
    val bytes: Long,
) : Closeable {
    constructor(virtualFile: VirtualFile, bytes: Long) : this(listOf(virtualFile), bytes)

    override fun close() {
        // GLRetroView detaches descriptors when its native VFS takes ownership.
        // Closing detached PFDs is harmless; native VFSFile objects close the original FDs.
        virtualFiles.forEach { runCatching { it.fileDescriptor.close() } }
    }
}

/**
 * A bounded random-read window around a seekable media source, used only by a proxy-FD worker.
 * Retains at most 4 * 256 KiB of cached ROM data plus one lazy short-read scratch block by default.
 * It is intentionally independent from Android for testing offsets, boundaries and network reads.
 */
internal class GameReadWindows(
    private val media: SeekableMedia,
    private val blockBytes: Int = 256 * 1024,
    private val maxBlocks: Int = 4,
) {
    init {
        require(blockBytes in 4096..(1024 * 1024) && maxBlocks in 1..16)
    }

    private class Block(val bytes: ByteArray, var length: Int = 0)
    private val blocks = LinkedHashMap<Long, Block>(maxBlocks + 1, .75f, true)
    private var shortReadScratch: ByteArray? = null

    val size: Long get() = media.size

    /** Sequential fills with readahead; short network reads are retried until EOF. Single worker. */
    fun read(offset: Long, into: ByteArray, requested: Int): Int {
        require(offset >= 0 && requested >= 0 && requested <= into.size)
        if (requested == 0 || offset >= size) return 0
        var done = 0
        val count = minOf(requested.toLong(), size - offset).toInt()
        while (done < count) {
            val at = offset + done
            val start = at - at % blockBytes
            val block = blocks[start] ?: load(start).also { blocks[start] = it }
            val inside = (at - start).toInt()
            if (inside >= block.length) throw IOException("Unexpected end of ROM at byte $at")
            val part = minOf(count - done, block.length - inside)
            block.bytes.copyInto(into, done, inside, inside + part)
            done += part
        }
        return done
    }

    private fun load(start: Long): Block {
        // Remove the old key before overwriting its storage, including if the read throws.
        val block = if (blocks.size == maxBlocks) {
            val iterator = blocks.entries.iterator()
            val eldest = iterator.next().value
            iterator.remove()
            eldest
        } else {
            Block(ByteArray(blockBytes))
        }
        block.length = 0
        val expected = minOf(blockBytes.toLong(), size - start).toInt()
        // The common full read goes directly into the cache, with no temporary copy.
        var done = 0
        while (done < expected) {
            val remaining = expected - done
            val target = if (done == 0) block.bytes else
                shortReadScratch ?: ByteArray(blockBytes).also { shortReadScratch = it }
            val n = media.read(start + done, target, remaining)
            if (n <= 0) break
            if (n > remaining) throw IOException("Invalid ROM read length: $n > $remaining")
            if (done != 0) target.copyInto(block.bytes, done, 0, n)
            done += n
        }
        // A partial tail or premature EOF never exposes bytes from the previous key.
        block.length = done
        return block
    }
}

@Singleton
class GameDirectAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: FileAccess,
) {
    /**
     * Opens a seekable descriptor on the original file. SAF documents use their actual provider
     * descriptor, while SMB/NFS/WebDAV/SFTP use a kernel FUSE proxy backed by FileAccess.read().
     * No content is cached on disk; each proxy owns a bounded 1 MiB LRU plus lazy scratch.
     */
    fun open(source: FileSource, path: String, virtualPath: String): GameDirectContent {
        if (source.kind == "local") {
            val descriptor = context.contentResolver.openFileDescriptor(files.uri(source, path), "r")
                ?: throw IOException("Document provider did not return a descriptor")
            try {
                // Some providers return a pipe instead of a seekable file. Those cannot serve ISO reads.
                Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
                val length = Os.fstat(descriptor.fileDescriptor).st_size
                if (length <= 0L) throw IOException("Document has no seekable length")
                return GameDirectContent(VirtualFile(virtualPath, descriptor), length)
            } catch (error: Exception) {
                descriptor.close()
                throw IOException("Original document is not seekable", error)
            }
        }

        val media = files.open(source, path)
        var worker: HandlerThread? = null
        try {
            if (media.size <= 0L) throw IOException("Remote ROM has no known size")
            val thread = HandlerThread("game-rom-reads").also { it.start() }
            worker = thread
            val reader = GameReadWindows(media)
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = reader.size
                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    try {
                        return reader.read(offset, data, size)
                    } catch (error: Exception) {
                        throw ErrnoException("Game ROM read", OsConstants.EIO).apply { initCause(error) }
                    }
                }
                override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
                    throw ErrnoException("Game ROM is read-only", OsConstants.EROFS)

                override fun onRelease() {
                    try { media.close() } finally { thread.quitSafely() }
                }
            }
            val storage = context.getSystemService(StorageManager::class.java)
                ?: throw IOException("StorageManager unavailable")
            val descriptor = storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper),
            )
            return GameDirectContent(VirtualFile(virtualPath, descriptor), media.size)
        } catch (error: Exception) {
            worker?.quitSafely()
            runCatching { media.close() }
            throw IOException("Cannot open a seekable ROM proxy", error)
        }
    }
}
