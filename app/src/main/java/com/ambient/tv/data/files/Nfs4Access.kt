package com.ambient.tv.data.files

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/** Read-only NFSv4.0/TCP, AUTH_SYS. RFC 7530 COMPOUND with PUTROOTFH/LOOKUP through the server's
 * pseudo filesystem; no MOUNT, RPCbind, client id, OPEN state, delegations or write operations.
 * READ uses the anonymous stateid (RFC 7530 §9.1.4.3), which servers accept for plain reads. */
internal class Nfs4Access {
    private class Compound(val ops: Int) {
        val body = NfsXdr().apply { string(""); int(0); int(ops) }
    }
    private class Result(val reply: NfsXdr) {
        /** Consumes one operation header; a failed operation ends the reply (RFC 7530 §16.2.3). */
        fun op(expected: Int) {
            val op = reply.int(); val status = reply.int()
            if (op != expected) throw IOException("NFSv4 reply out of order")
            check(status)
        }
    }

    private class Connection(val source: FileSource) : Closeable {
        val rpc = NfsRpc(source, source.port)
        fun compound(ops: Int, build: NfsXdr.() -> Unit): Result {
            var attempt = 0
            while (true) {
                val call = Compound(ops); call.body.build()
                val reply = rpc.call(NFS_PROGRAM, 4, 1, call.body)
                val status = reply.int()
                // DELAY / GRACE (e.g. right after a Wake-on-LAN boot): the server asks to retry later.
                if ((status == 10008 || status == 10013) && ++attempt <= 5) { Thread.sleep(1000L * attempt); continue }
                reply.opaque(1024); val count = reply.int()
                // No results at all (e.g. MINOR_VERS_MISMATCH): only the overall status explains why.
                if (count == 0) { check(status); throw IOException("Empty NFSv4 reply") }
                require(count <= ops)
                return Result(reply)
            }
        }
        /** PUTROOTFH + LOOKUP per component of export + path; symbolic links are never followed. */
        fun NfsXdr.walk(path: String): Int {
            val parts = components(source.share, path)
            int(OP_PUTROOTFH); parts.forEach { int(OP_LOOKUP); string(it) }
            return 1 + parts.size
        }
        fun Result.skipWalk(path: String) { op(OP_PUTROOTFH); repeat(components(source.share, path).size) { op(OP_LOOKUP) } }
        fun resolve(path: String): Pair<ByteArray, Attr> {
            val steps = components(source.share, path).size + 1
            val result = compound(steps + 2) { walk(path); int(OP_GETFH); int(OP_GETATTR); bitmap(ATTRS) }
            result.skipWalk(path)
            result.op(OP_GETFH); val handle = result.reply.opaque(128)
            result.op(OP_GETATTR); val attr = attributes(result.reply)
            if (attr.type == NF4LNK) throw IOException("NFS symbolic links are not followed")
            return handle to attr
        }
        override fun close() = rpc.close()
    }

    private data class Attr(val type: Int, val size: Long, val modified: Long, val error: Int = 0)

    fun list(source: FileSource, relative: String, limit: Int, accept: (FileEntry) -> Boolean): FileListing = Connection(source.validated()).use { connection ->
        val (handle, attr) = connection.resolve(relative)
        if (attr.type != NF4DIR) throw IOException("Not a directory")
        val entries = mutableListOf<FileEntry>()
        var cookie = 0L; var verifier = ByteArray(8); var eof = false; var pages = 0
        while (!eof) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (++pages > 10000) throw IOException("Directory too large")
            val previousCookie = cookie
            val result = connection.compound(2) {
                int(OP_PUTFH); opaque(handle)
                int(OP_READDIR); long(cookie); raw(verifier); int(16 * 1024); int(64 * 1024); bitmap(ATTRS or RDATTR_ERROR)
            }
            result.op(OP_PUTFH); result.op(OP_READDIR)
            val reply = result.reply
            verifier = reply.raw(8)
            while (reply.int() != 0) {
                cookie = reply.long(); val name = reply.string(); val entryAttr = attributes(reply)
                if (entryAttr.error != 0) continue
                val child = runCatching { FilePaths.child(relative, name) }.getOrNull() ?: continue
                if (entryAttr.type != NF4REG && entryAttr.type != NF4DIR) continue
                val entry = FileEntry(source.id, child, name, entryAttr.type == NF4DIR, entryAttr.size, entryAttr.modified, FilePaths.mime(name))
                if (!entry.directory && !accept(entry)) continue
                if (entries.size >= limit) return@use FileListing(entries, true)
                entries += entry
            }
            eof = reply.int() != 0
            if (!eof && cookie == previousCookie) throw IOException("Directory did not advance")
        }
        FileListing(entries, false)
    }

    fun stat(source: FileSource, path: String): FileEntry = Connection(source.validated()).use { connection ->
        val attr = connection.resolve(path).second
        require(attr.type == NF4REG || attr.type == NF4DIR)
        FileEntry(source.id, path, path.substringAfterLast('/').ifEmpty { source.name }, attr.type == NF4DIR, attr.size, attr.modified, FilePaths.mime(path))
    }

    fun open(source: FileSource, path: String): SeekableMedia {
        val connection = Connection(source.validated())
        try {
            val (handle, attr) = connection.resolve(path); require(attr.type == NF4REG)
            return object : SeekableMedia {
                override val size = attr.size
                // File handles are valid on any connection: parallel reads each use their own socket.
                override val parallelReads = true
                private val spare = ConcurrentLinkedQueue<Connection>().apply { add(connection) }
                @Volatile private var closed = false

                override fun read(position: Long, buffer: ByteArray, count: Int): Int {
                    if (closed) throw IOException("closed")
                    val requested = minOf(count, NFS_MAX_READ)
                    val conn = spare.poll() ?: Connection(source)
                    var reusable = false
                    try {
                        val result = conn.compound(2) {
                            int(OP_PUTFH); opaque(handle)
                            int(OP_READ); int(0); raw(ByteArray(12)); long(position); int(requested)
                        }
                        result.op(OP_PUTFH); result.op(OP_READ)
                        result.reply.int(); val data = result.reply.opaque(requested)
                        data.copyInto(buffer)
                        reusable = true
                        return if (data.isEmpty()) -1 else data.size
                    } finally {
                        // A connection left mid-reply (error, interrupt) is out of step: never reuse it.
                        if (reusable && !closed) spare.add(conn) else conn.close()
                        if (closed) spare.forEach { runCatching { it.close() } }
                    }
                }
                override fun close() {
                    closed = true
                    while (true) { val conn = spare.poll() ?: break; runCatching { conn.close() } }
                }
            }
        } catch (e: Throwable) { connection.close(); throw e }
    }

    private companion object {
        const val NFS_PROGRAM = 100003
        const val OP_GETATTR = 9; const val OP_GETFH = 10; const val OP_LOOKUP = 15
        const val OP_PUTFH = 22; const val OP_PUTROOTFH = 24; const val OP_READ = 25; const val OP_READDIR = 26
        const val NF4REG = 1; const val NF4DIR = 2; const val NF4LNK = 5
        // Attribute numbers: type 1, size 4, rdattr_error 11, time_modify 53.
        const val ATTRS = (1L shl 1) or (1L shl 4) or (1L shl 53)
        const val RDATTR_ERROR = 1L shl 11

        fun components(export: String, path: String): List<String> {
            val parts = (FilePaths.relative(export).split('/') + FilePaths.relative(path).split('/')).filter(String::isNotEmpty)
            require(parts.size <= 64) { "Path too deep" }
            return parts
        }

        fun NfsXdr.bitmap(bits: Long) { int(2); int(bits.toInt()); int((bits ushr 32).toInt()) }

        fun check(status: Int) {
            when (status) {
                0 -> Unit
                1, 13 -> throw SecurityException("NFS access denied")
                10016 -> throw SecurityException("NFS requires unsupported authentication") // WRONGSEC
                10021 -> throw IOException("Server does not offer NFSv4.0") // MINOR_VERS_MISMATCH
                10008, 10013 -> throw IOException("NFS server busy, try again") // DELAY, GRACE
                10029 -> throw IOException("NFS symbolic links are not followed") // SYMLINK
                else -> throw IOException("NFS status $status")
            }
        }

        /** fattr4: the values follow in attribute-number order for exactly the bits the server set. */
        fun attributes(reply: NfsXdr): Attr {
            val words = reply.int(); require(words in 0..8)
            var bits = 0L
            repeat(words) { index -> val word = reply.int().toLong() and 0xffffffffL; if (index < 2) bits = bits or (word shl (32 * index)) }
            val values = NfsXdr(reply.opaque(4096))
            var type = 0; var size = 0L; var modified = 0L; var error = 0
            if (bits and (1L shl 1) != 0L) type = values.int()
            if (bits and (1L shl 4) != 0L) size = values.long().also { require(it >= 0) }
            if (bits and RDATTR_ERROR != 0L) error = values.int()
            if (bits and (1L shl 53) != 0L) { val seconds = values.long(); val nanos = values.int(); modified = seconds * 1000 + nanos / 1_000_000 }
            return Attr(type, size, modified, error)
        }
    }
}
