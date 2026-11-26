package com.ambient.tv.ui.player

import okhttp3.Interceptor
import okhttp3.Response

/** Shared bounded per-player URL renewal state, independent of the selected HTTP transport. */
internal class StreamUrlRenewalPolicy(private val renew: (String) -> String?) {
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
            flight.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
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

/** OkHttp recovery, preserving the exact range and request headers. */
internal class StreamUrlRenewalInterceptor(private val renew: (String) -> String?) : Interceptor {
    private val policy = StreamUrlRenewalPolicy(renew)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val original = request.url.toString()
        val url = policy.current(original)
        val response = chain.proceed(request.newBuilder().url(url).build())
        if (response.code != 403 && response.code != 410) return response
        val fresh = policy.replacement(original, url) ?: return response
        response.close()
        return chain.proceed(request.newBuilder().url(fresh).build())
    }
}
