package com.ambient.tv.ui.player

/** Shared bounded per-player URL renewal state, independent of the selected HTTP transport. */
internal class StreamUrlRenewalPolicy(
    private val waiterTimeoutMs: Long = 30_000L,
    private val renew: (String) -> String?,
) {
    init { require(waiterTimeoutMs > 0) }
    private val replacements = LinkedHashMap<String, String>()
    private val attempted = LinkedHashMap<String, Long>()
    private val inFlight = HashMap<String, java.util.concurrent.CompletableFuture<String?>>()

    @Synchronized fun current(url: String): String = replacements[url] ?: url

    fun replacement(original: String, failed: String): String? {
        val (flight, owner) = synchronized(this) {
            current(original).takeIf { it != failed }?.let { return it }
            inFlight[failed]?.let { return@synchronized it to false }
            val now = System.nanoTime()
            attempted[failed]?.let { if (now - it < 30_000_000_000L) return null }
            if (inFlight.size >= 64) return null
            attempted[failed] = now
            while (attempted.size > 64) attempted.remove(attempted.keys.first())
            java.util.concurrent.CompletableFuture<String?>().also { inFlight[failed] = it } to true
        }
        // A provider refresh may perform network I/O. Never hold the cache monitor
        // during that refresh or while another range request waits for the same URL.
        if (owner) {
            val fresh = runCatching { renew(failed) }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != failed }
            synchronized(this) {
                val result = storeReplacement(original, failed, fresh)
                flight.complete(fresh)
                inFlight.remove(failed)
                return result
            }
        }
        val fresh = try {
            flight.get(waiterTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: java.util.concurrent.TimeoutException) {
            // Do not cancel the shared refresh or start a duplicate provider request.
            return null
        } catch (_: java.util.concurrent.ExecutionException) {
            return null
        }
        return synchronized(this) { storeReplacement(original, failed, fresh) }
    }

    /** Called under the monitor; never overwrite a newer successful replacement. */
    private fun storeReplacement(original: String, failed: String, fresh: String?): String? {
        current(original).takeIf { it != failed }?.let { return it }
        if (fresh == null) return null
        replacements[original] = fresh
        while (replacements.size > 64) replacements.remove(replacements.keys.first())
        return fresh
    }
}

