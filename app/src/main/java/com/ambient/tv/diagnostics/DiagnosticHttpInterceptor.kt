package com.ambient.tv.diagnostics

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Records failed API calls while diagnostics are on: HTTP status ≥ 400 and transport errors,
 * with method, host and path (no query string, which may carry tokens) and the duration.
 * Successful calls and calls the app cancelled itself are not logged.
 */
object DiagnosticHttpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!DiagnosticLog.enabled) return chain.proceed(chain.request())
        val request = chain.request()
        val target = "${request.method} ${request.url.scheme}://${request.url.host}:${request.url.port}${request.url.encodedPath}"
        val started = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            if (!chain.call().isCanceled()) {
                DiagnosticLog.warn("http", "$target failed after ${elapsedMs(started)} ms: ${error.javaClass.simpleName}: ${error.message}", error.cause)
            }
            throw error
        }
        if (response.code >= 400) {
            DiagnosticLog.warn("http", "$target → HTTP ${response.code} ${response.message} (${elapsedMs(started)} ms)")
        }
        return response
    }

    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000
}
