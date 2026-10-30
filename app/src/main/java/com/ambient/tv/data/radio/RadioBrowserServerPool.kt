package com.ambient.tv.data.radio

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Radio Browser's all.api DNS name tracks the currently live mirrors. Keep it as the first
 * candidate even when reverse DNS (which is optional and can be unavailable on Android) fails.
 * A previously working mirror stays preferred until it fails; expired discovery is retried.
 */
@Singleton
class RadioBrowserServerPool @Inject constructor() {
    @Volatile private var cached: Cache? = null
    @Volatile private var lastHealthyHost: String? = null

    fun hosts(nowMs: Long = System.currentTimeMillis()): List<String> {
        val current = cached?.takeIf { nowMs - it.createdAtMs in 0 until CACHE_MS }
            ?: synchronized(this) {
                cached?.takeIf { nowMs - it.createdAtMs in 0 until CACHE_MS }
                    ?: Cache(nowMs, discover().shuffled()).also { cached = it }
            }
        return radioBrowserHostOrder(current.hosts, lastHealthyHost)
    }

    internal fun markSuccessful(host: String) {
        lastHealthyHost = host
    }

    /** Force DNS re-discovery after every mirror in the current list has failed. */
    internal fun clear() {
        cached = null
        lastHealthyHost = null
    }

    private fun discover(): List<String> = runCatching {
        InetAddress.getAllByName(DISCOVERY_HOST)
            .map { it.canonicalHostName.trim().lowercase() }
            .filter { host ->
                host != DISCOVERY_HOST &&
                    host.endsWith(".api.radio-browser.info") &&
                    host.none(Char::isWhitespace)
            }
            .distinct()
    }.getOrDefault(emptyList())

    private data class Cache(val createdAtMs: Long, val hosts: List<String>)

    companion object {
        const val DISCOVERY_HOST = "all.api.radio-browser.info"
        private val CACHE_MS = TimeUnit.HOURS.toMillis(1)
    }
}

/** Do not depend on legacy nl1/at1 hosts: they no longer reliably resolve. */
internal fun radioBrowserHostOrder(discovered: List<String>, lastHealthy: String?): List<String> {
    val candidates = (listOf(RadioBrowserServerPool.DISCOVERY_HOST) +
        discovered + "de1.api.radio-browser.info").distinct()
    return listOfNotNull(lastHealthy?.takeIf { it in candidates }) +
        candidates.filterNot { it == lastHealthy }
}
