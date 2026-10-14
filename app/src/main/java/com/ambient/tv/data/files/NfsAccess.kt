package com.ambient.tv.data.files

import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Read-only NFSv3/TCP, AUTH_SYS. RFC 1813 + RFC 5531. No mount, root privileges,
 * background executor, native library or write procedure on the Android device.
 * Sources set to NFSv4 go to [Nfs4Access]. */
class NfsAccess(private val rpcBindPort: Int = 111) {
    private val v4 = Nfs4Access()
    private inner class Session(val source: FileSource) : Closeable {
        private val mountPort: Int = if (source.nfsMountPort > 0) source.nfsMountPort else NfsRpc(source, rpcBindPort).use { rpc ->
            rpc.call(100000, 2, 3, NfsXdr().apply { int(100005); int(3); int(6); int(0) }).int().also { require(it in 1..65535) }
        }
        val root: ByteArray = NfsRpc(source, mountPort).use { rpc ->
            val reply = rpc.call(100005, 3, 1, NfsXdr().apply { string(source.share) }); reply.ok()
            reply.opaque(64).also {
                val count = reply.int(); require(count in 0..32)
                val auth = List(count) { reply.int() }; require(auth.isEmpty() || 1 in auth) { "NFS requires unsupported authentication" }
            }
        }
        val rpc = NfsRpc(source, source.port)
        fun lookup(path: String): ByteArray {
            var handle = root
            for (name in FilePaths.relative(path).split('/').filter(String::isNotEmpty)) {
                val reply = rpc.call(100003, 3, 3, NfsXdr().apply { opaque(handle); string(name) }); reply.ok()
                handle = reply.opaque(64)
                val attr = reply.postAttributes()
                if (attr?.type == 5) throw IOException("NFS symbolic links are not followed")
            }
            return handle
        }
        fun attributes(handle: ByteArray): NfsAttributes {
            val reply = rpc.call(100003, 3, 1, NfsXdr().apply { opaque(handle) }); reply.ok(); return reply.attributes()
        }
        override fun close() { rpc.close() }
    }
    fun list(source: FileSource, relative: String, limit: Int, accept: (FileEntry) -> Boolean = FileEntry::playable): FileListing =
        if (source.nfsVersion == 4) v4.list(source, relative, limit, accept) else Session(source.validated()).use { session ->
        val handle = session.lookup(relative); val entries = mutableListOf<FileEntry>()
        var cookie = 0L; var verifier = ByteArray(8); var eof = false; var pages = 0; var plus = true
        while (!eof) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (++pages > 10000) throw IOException("Directory too large")
            val previousCookie = cookie
            val reply = if (plus) session.rpc.call(100003, 3, 17, NfsXdr().apply { opaque(handle); long(cookie); raw(verifier); int(16 * 1024); int(64 * 1024) })
                else session.rpc.call(100003, 3, 16, NfsXdr().apply { opaque(handle); long(cookie); raw(verifier); int(32 * 1024) })
            if (plus && reply.status() == NFS3ERR_NOTSUPP) { plus = false; pages--; continue } // e.g. unfsd: plain READDIR + GETATTR
            reply.ok(); reply.postAttributes(); verifier = reply.raw(8)
            while (reply.int() != 0) {
                reply.long(); val name = reply.string(); cookie = reply.long()
                var attr = if (plus) reply.postAttributes() else null
                val childHandle = if (plus && reply.int() != 0) reply.opaque(64) else null
                if (name == "." || name == "..") continue
                // NFS allows names (e.g. with ':') the app refuses as paths: skip just that entry.
                val child = runCatching { FilePaths.child(relative, name) }.getOrNull() ?: continue
                if (attr == null) attr = session.attributes(childHandle ?: session.lookup(child))
                if (attr.type !in setOf(1, 2)) continue
                val entry = FileEntry(source.id, child, name, attr.type == 2, attr.size, attr.modified, FilePaths.mime(name))
                if (!entry.directory && !accept(entry)) continue
                if (entries.size >= limit) return@use FileListing(entries, true)
                entries += entry
            }
            eof = reply.int() != 0
            if (!eof && cookie == previousCookie) throw IOException("Directory did not advance")
        }
        FileListing(entries, false)
    }
    fun stat(source: FileSource, path: String): FileEntry = if (source.nfsVersion == 4) v4.stat(source, path) else Session(source.validated()).use { session ->
        val attr = session.attributes(session.lookup(path))
        require(attr.type == 1 || attr.type == 2)
        FileEntry(source.id, path, path.substringAfterLast('/').ifEmpty { source.name }, attr.type == 2, attr.size, attr.modified, FilePaths.mime(path))
    }
    fun open(source: FileSource, path: String): SeekableMedia {
        if (source.nfsVersion == 4) return v4.open(source, path)
        val session = Session(source.validated())
        try {
            val handle = session.lookup(path); val attr = session.attributes(handle); require(attr.type == 1)
            return object : SeekableMedia {
                override val size = attr.size
                // File handles are valid on any connection: parallel reads each use their own socket.
                override val parallelReads = true
                private val spare = java.util.concurrent.ConcurrentLinkedQueue<NfsRpc>().apply { add(session.rpc) }
                @Volatile private var closed = false

                override fun read(position: Long, buffer: ByteArray, count: Int): Int {
                    if (closed) throw IOException("closed")
                    // Servers clamp a larger request to their maximum (rtmax) and return less.
                    val requested = minOf(count, MAX_READ)
                    val rpc = spare.poll() ?: NfsRpc(source, source.port)
                    var reusable = false
                    try {
                        val reply = rpc.call(100003, 3, 6, NfsXdr().apply { opaque(handle); long(position); int(requested) })
                        reply.ok(); reply.postAttributes(); val read = reply.int(); reply.int(); val data = reply.opaque(requested)
                        require(read == data.size); data.copyInto(buffer)
                        reusable = true
                        return if (read == 0) -1 else read
                    } finally {
                        // A connection left mid-reply (error, interrupt) is out of step: never reuse it.
                        if (reusable && !closed) spare.add(rpc) else rpc.close()
                        if (closed) spare.forEach { runCatching { it.close() } }
                    }
                }
                override fun close() {
                    closed = true
                    while (true) { val rpc = spare.poll() ?: break; runCatching { rpc.close() } }
                    session.close()
                }
            }
        } catch (e: Throwable) { session.close(); throw e }
    }
    companion object { private const val MAX_READ = NFS_MAX_READ }
}

internal const val NFS_MAX_RECORD = 4 * 1024 * 1024
internal const val NFS_MAX_READ = 1024 * 1024
private const val NFS3ERR_NOTSUPP = 10004
private val NFS_XIDS = AtomicInteger()

internal data class NfsAttributes(val type: Int, val size: Long, val modified: Long)
internal class NfsXdr(bytes: ByteArray = byteArrayOf()) {
    val output = ByteArrayOutputStream(); private val writer = DataOutputStream(output)
    private val input = DataInputStream(ByteArrayInputStream(bytes))
    fun int(value: Int) { writer.writeInt(value) }
    fun long(value: Long) { writer.writeLong(value) }
    fun raw(value: ByteArray) { writer.write(value) }
    fun opaque(value: ByteArray) { int(value.size); raw(value); repeat((4 - value.size % 4) % 4) { writer.writeByte(0) } }
    fun string(value: String) = opaque(value.toByteArray(Charsets.UTF_8))
    fun int(): Int = input.readInt()
    fun long(): Long = input.readLong()
    fun raw(size: Int): ByteArray { require(size in 0..NFS_MAX_RECORD); return ByteArray(size).also(input::readFully) }
    fun opaque(max: Int = NFS_MAX_RECORD): ByteArray { val n = int(); require(n in 0..max); val b = raw(n); raw((4 - n % 4) % 4); return b }
    fun string() = String(opaque(1024), Charsets.UTF_8)
    fun attributes(): NfsAttributes {
        val type = int(); repeat(4) { int() }; val size = long(); require(size >= 0)
        long(); int(); int(); long(); long(); long()
        val seconds = int().toLong() and 0xffffffffL; val nanos = int(); long()
        return NfsAttributes(type, size, seconds * 1000 + nanos / 1_000_000)
    }
    fun postAttributes(): NfsAttributes? = if (int() != 0) attributes() else null
    /** Reads the status without consuming it, so [ok] still sees it. */
    fun status(): Int { input.mark(4); return int().also { input.reset() } }
    fun ok() { val status = int(); if (status == 13 || status == 1) throw SecurityException("NFS access denied"); if (status != 0) throw IOException("NFS status $status") }
}
internal class NfsRpc(private val source: FileSource, port: Int) : Closeable {
    private val socket = Socket()
    init { try { socket.connect(InetSocketAddress(source.host, port), 4000); socket.soTimeout = 8000; socket.tcpNoDelay = true } catch (e: Throwable) { socket.close(); throw e } }
    fun call(program: Int, version: Int, procedure: Int, body: NfsXdr): NfsXdr {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val xid = NFS_XIDS.incrementAndGet()
        val auth = NfsXdr().apply { int((System.currentTimeMillis() / 1000).toInt()); string("android-tv"); int(source.nfsUid); int(source.nfsGid); int(0) }
        val message = NfsXdr().apply {
            int(xid); int(0); int(2); int(program); int(version); int(procedure)
            int(1); opaque(auth.output.toByteArray()); int(0); int(0); raw(body.output.toByteArray())
        }.output.toByteArray()
        val output = DataOutputStream(socket.getOutputStream()); output.writeInt(message.size or Int.MIN_VALUE); output.write(message); output.flush()
        val input = DataInputStream(socket.getInputStream()); val record = ByteArrayOutputStream()
        var fragments = 0
        while (true) {
            require(++fragments <= 128)
            val marker = input.readInt(); val length = marker and Int.MAX_VALUE
            require(length <= NFS_MAX_RECORD - record.size())
            val fragment = ByteArray(length); input.readFully(fragment); record.write(fragment)
            if (marker < 0) break
        }
        val reply = NfsXdr(record.toByteArray())
        require(reply.int() == xid && reply.int() == 1)
        if (reply.int() != 0) throw SecurityException("RPC authentication rejected")
        reply.int(); reply.opaque(400)
        if (reply.int() != 0) throw IOException("RPC procedure unavailable")
        return reply
    }
    override fun close() = socket.close()
}

