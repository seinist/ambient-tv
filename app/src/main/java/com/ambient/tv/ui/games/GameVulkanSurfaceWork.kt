package com.ambient.tv.ui.games

/**
 * Render-thread-local WSI bookkeeping. An Android Surface object can survive resize;
 * callers must supply a fresh revision for every real surface lifecycle callback.
 * Probe metadata and attachment ownership have independent lifetimes.
 */
internal class GameVulkanSurfaceWork<C> {
    private data class Formats<C>(val revision: Long, val capabilities: C, val bits: Int)
    private var formats: Formats<C>? = null
    private var attachment: Long? = null

    fun cachedFormats(revision: Long, capabilities: C): Int? =
        formats?.takeIf { it.revision == revision && it.capabilities == capabilities }?.bits

    fun rememberFormats(revision: Long, capabilities: C, bits: Int) {
        // An unsuccessful probe may be transient; do not persist a negative result.
        formats = if (bits != 0) Formats(revision, capabilities, bits) else null
    }

    fun needsAttachment(revision: Long): Boolean = attachment != revision
    fun attached(revision: Long) { attachment = revision }
    fun detached() { attachment = null; formats = null }
}
