package com.ambient.tv.ui.player.backend

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Private abstract Unix socket; the demux worker checks our UID too.
 * No permissions, TCP port, URL or native library dependency is introduced.
 */
internal class VlcTimedMetadataReceiver private constructor(
    val socketName: String,
    private val server: LocalServerSocket,
    private val onEvent: (VlcTimedMetadataPacket.Message) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val connectionLock = Any()
    private var active: LocalSocket? = null

    private fun start() {
        thread(name = "vlc-timed-metadata", isDaemon = true) {
            while (!closed.get()) {
                val client = try { server.accept() } catch (_: Exception) { break }
                val registered = synchronized(connectionLock) {
                    if (closed.get()) false else {
                        active = client
                        true
                    }
                }
                if (!registered) {
                    runCatching { client.close() }
                    break
                }
                try {
                    client.use {
                        if (closed.get() || it.peerCredentials.uid != Process.myUid()) return@use
                        it.soTimeout = 500
                        val event = VlcTimedMetadataPacket.read(it.inputStream)
                        if (!closed.get()) onEvent(event)
                    }
                } catch (_: Exception) {
                    // Incomplete or invalid event: discard only this connection.
                } finally {
                    synchronized(connectionLock) { if (active === client) active = null }
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        val client = synchronized(connectionLock) { active.also { active = null } }
        runCatching { client?.close() }
    }

    companion object {
        fun open(onEvent: (VlcTimedMetadataPacket.Message) -> Unit): VlcTimedMetadataReceiver? = runCatching {
            val name = "ambient-fel-" + UUID.randomUUID().toString().replace("-", "")
            VlcTimedMetadataReceiver(name, LocalServerSocket(name), onEvent).also { it.start() }
        }.getOrNull()
    }
}
