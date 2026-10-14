package com.ambient.tv.ui.player

import okhttp3.Interceptor
import okhttp3.Response

/** Per-player signed-URL recovery, preserving the exact range and request headers. */
internal class StreamUrlRenewalInterceptor(private val renew: (String) -> String?) : Interceptor {
    private val replacements = LinkedHashMap<String, String>()
    private val attempted = LinkedHashMap<String, Long>()

    @Synchronized private fun current(url: String): String = replacements[url] ?: url

    @Synchronized private fun replacement(original: String, failed: String): String? {
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val original = request.url.toString()
        val url = current(original)
        val response = chain.proceed(request.newBuilder().url(url).build())
        if (response.code != 403 && response.code != 410) return response
        val fresh = replacement(original, url) ?: return response
        response.close()
        return chain.proceed(request.newBuilder().url(fresh).build())
    }
}
