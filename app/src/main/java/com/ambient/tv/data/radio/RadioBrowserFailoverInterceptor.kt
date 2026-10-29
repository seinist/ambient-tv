package com.ambient.tv.data.radio

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RadioBrowserFailoverInterceptor @Inject constructor(
    private val servers: RadioBrowserServerPool,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // All current catalog methods are GET. Never replay a future mutating request implicitly.
        if (original.method != "GET") return chain.proceed(original)

        val hosts = servers.hosts()
        var lastFailure: IOException? = null
        hosts.forEachIndexed { index, host ->
            val request = original.newBuilder()
                .url(original.url.newBuilder().host(host).build())
                .build()
            try {
                val response = chain.proceed(request)
                val retryable = response.code in 500..599
                if (!retryable || index == hosts.lastIndex) return response
                response.close()
            } catch (error: IOException) {
                lastFailure = error
                if (index == hosts.lastIndex) throw error
            }
        }
        throw lastFailure ?: IOException("No Radio Browser API mirror available")
    }
}
