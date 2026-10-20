package com.ambient.tv.ui.player.backend

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lets SurfaceHolder.surfaceDestroyed wait (bounded) until MPV's VO no longer renders into the
 * window. Android requires that no producer touches the surface after that callback returns;
 * the detach itself runs on the mpv-control thread, which must never wait on itself.
 */
internal class MpvSurfaceDetachGate {
    private val waiters = ConcurrentLinkedQueue<CountDownLatch>()
    /** The VO is attached to, or is being attached to, a window. Written on the owner thread. */
    @Volatile var inUse = false
        private set
    @Volatile private var closed = false

    /** Owner thread, before handing a window to MPV. */
    fun attaching() { inUse = true }

    /** Owner thread: VO is off every window; all current waiters may return. */
    fun detached() {
        inUse = false
        drain()
    }

    /** Owner thread: the engine is destroyed; nothing will ever render again. */
    fun closed() {
        closed = true
        inUse = false
        drain()
    }

    /**
     * Blocks the caller (never the owner thread) until [detached]/[closed] or [timeoutMs].
     * Returns true when the VO is off the window, false on timeout.
     */
    fun await(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        waiters.add(latch)
        // Re-check after publishing: a detach that finished in between never sees this latch.
        if (closed || !inUse) latch.countDown()
        return try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        finally { waiters.remove(latch) }
    }

    private fun drain() {
        while (true) (waiters.poll() ?: return).countDown()
    }
}
