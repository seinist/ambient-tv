package com.ambient.tv.ui.player.backend

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lets SurfaceHolder.surfaceDestroyed wait (bounded) until MPV's VO no longer renders into the
 * window. Android requires that no producer touches the surface after that callback returns;
 * the detach itself runs on the mpv-control thread, which must never wait on itself.
 */
internal class MpvSurfaceDetachGate {
    private val waiters = mutableListOf<CountDownLatch>()
    private var requestedDetach = 0L
    private var completedDetach = 0L
    /** The VO is attached to, or is being attached to, a window. Written on the owner thread. */
    @Volatile var inUse = false
        private set
    @Volatile private var closed = false

    /** Owner thread, before handing a window to MPV. */
    @Synchronized fun attaching() { if (!closed) inUse = true }

    /** Caller thread, before enqueuing detach: includes an attach already taken from the queue. */
    @Synchronized fun requestDetach(): Long = ++requestedDetach

    /** Owner thread: VO is off every window; all current waiters may return. */
    @Synchronized fun detached(ticket: Long = requestedDetach) {
        inUse = false
        completedDetach = maxOf(completedDetach, ticket)
        if (completedDetach >= requestedDetach) drain()
    }

    /** Owner thread: the engine is destroyed; nothing will ever render again. */
    @Synchronized fun closed() {
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
        synchronized(this) {
            waiters.add(latch)
            if (closed || (!inUse && completedDetach >= requestedDetach)) latch.countDown()
        }
        return try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        finally { synchronized(this) { waiters.remove(latch) } }
    }

    private fun drain() {
        waiters.forEach { it.countDown() }
        waiters.clear()
    }
}
