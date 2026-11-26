package com.ambient.tv.ui.player

import okhttp3.Interceptor
import okhttp3.Response

/** Shared bounded per-player URL renewal state, independent of the selected HTTP transport. */
internal class StreamUrlRenewalPolicy(private val renew: (String) -> String?) {
    private val replacements = LinkedHashMap<String, String>()
    private val attempted = LinkedHashMap<String, Long>()

    @Synchronized fun current(url: String): String = replacements[url] ?: url

    @Synchronized fun replacement(original: String, failed: String): String? {
        current(original).takeIf { it != failed }?.let { return it }
        val now = System.nanoTime()
        attempted[failed]?.let { if (now - it < 30_000_000_000L) return null }
        attempted[failed] = now
        while (attempted.size > 64) attempted.remove(attempted.keys.first())
        val fresh = runCatching { renew(failed) }.getOrNull()?.takeIf { it.isNotBlank() && it != failed } ?: return null
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
