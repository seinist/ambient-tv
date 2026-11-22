package com.ambient.tv.data.radio

import com.ambient.tv.data.browse.OnlineServiceAvailability
import com.ambient.tv.data.browse.OnlineServiceReports
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RadioBrowserFailoverInterceptor internal constructor(
    private val servers: RadioBrowserServerPool,
    private val reports: OnlineServiceReports,
) : Interceptor {
    @Inject constructor(servers: RadioBrowserServerPool, onlineServices: OnlineServiceAvailability) :
        this(servers, onlineServices.reports)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // All current catalog methods are GET. Never replay a future mutating request implicitly.
        if (original.method != "GET") return chain.proceed(original)

        fun checkActive() { if (chain.call().isCanceled()) throw IOException("Canceled") }
        checkActive()
        // One token covers discovery and the entire mirror sequence. Cancelling a view or
        // finishing an older sequence must never undo another request's successful reply.
        val report = reports.begin("radio")
        val poolGeneration = servers.successGeneration
        val hosts = servers.hosts()
        var lastFailure: IOException? = null
        hosts.forEachIndexed { index, host ->
            checkActive()
            val request = original.newBuilder()
                .url(original.url.newBuilder().host(host).build())
                .build()
            try {
                val response = chain.proceed(request)
                if (chain.call().isCanceled()) {
                    response.close()
                    throw IOException("Canceled")
                }
                val live = response.networkResponse != null
                // HTTP rejection/rate limiting proves reachability too. Cached catalogue/HTTP
                // responses cannot establish it, and only usable replies prefer a mirror.
                if (live && response.code in 200..499 && response.code != 408) {
                    reports.succeeded(report)
                    if (response.isSuccessful) servers.markSuccessful(host)
                }
                // A mirror may be alive but overloaded. Try the next one for transient failures.
                val retryable = response.code == 408 || response.code == 429 || response.code in 500..599
                if (!retryable) {
                    return response
                }
                if (index == hosts.lastIndex) {
                    // 429 is rate limiting, not proof that Radio Browser is offline.
                    if (live && response.code != 429 && reports.unavailable(report)) {
                        servers.clear(poolGeneration)
                    }
                    return response
                }
                response.close()
            } catch (error: IOException) {
                // Retrofit cancels OkHttp with an IOException when navigation supersedes a
                // request. Do not try the other mirrors, report offline or discard discovery.
                if (chain.call().isCanceled()) throw error
                lastFailure = error
                if (index == hosts.lastIndex) {
                    if (reports.failed(report, error)) servers.clear(poolGeneration)
                    throw error
                }
            }
        }
        checkActive()
        val error = lastFailure ?: IOException("No Radio Browser API mirror available")
        if (reports.failed(report, error)) servers.clear(poolGeneration)
        throw error
    }
}
