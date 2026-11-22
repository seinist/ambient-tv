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
class RadioBrowserServerPool internal constructor(private val discovery: () -> List<String>) {
    @Inject constructor() : this(::discoverRadioBrowserHosts)
    @Volatile private var cached: Cache? = null
    @Volatile private var lastHealthyHost: String? = null
    private val discoveryLock = Any()
    @Volatile internal var successGeneration: Long = 0L
        private set

    fun hosts(nowMs: Long = System.currentTimeMillis()): List<String> {
        val current = cached?.takeIf { nowMs - it.createdAtMs in 0 until CACHE_MS }
            ?: synchronized(discoveryLock) {
                cached?.takeIf { nowMs - it.createdAtMs in 0 until CACHE_MS }
                    ?: Cache(nowMs, discovery().shuffled()).also { cached = it }
            }
        return radioBrowserHostOrder(current.hosts, lastHealthyHost)
    }

    @Synchronized internal fun markSuccessful(host: String) {
        lastHealthyHost = host
        successGeneration++
    }

    /** An older failing request must not erase a mirror another request has just recovered. */
    @Synchronized internal fun clear(expectedSuccessGeneration: Long) {
        if (successGeneration != expectedSuccessGeneration) return
        cached = null
        lastHealthyHost = null
    }

    private data class Cache(val createdAtMs: Long, val hosts: List<String>)

    companion object {
        const val DISCOVERY_HOST = "all.api.radio-browser.info"
        private val CACHE_MS = TimeUnit.HOURS.toMillis(1)
    }
}

private fun discoverRadioBrowserHosts(): List<String> = runCatching {
    InetAddress.getAllByName(RadioBrowserServerPool.DISCOVERY_HOST)
        .map { it.canonicalHostName.trim().lowercase() }
        .filter { host ->
            host != RadioBrowserServerPool.DISCOVERY_HOST &&
                host.endsWith(".api.radio-browser.info") &&
                host.none(Char::isWhitespace)
        }
        .distinct()
}.getOrDefault(emptyList())

/** Do not depend on legacy nl1/at1 hosts: they no longer reliably resolve. */
internal fun radioBrowserHostOrder(discovered: List<String>, lastHealthy: String?): List<String> {
    val candidates = (listOf(RadioBrowserServerPool.DISCOVERY_HOST) +
        discovered + "de1.api.radio-browser.info").distinct()
    return listOfNotNull(lastHealthy?.takeIf { it in candidates }) +
        candidates.filterNot { it == lastHealthy }
}
