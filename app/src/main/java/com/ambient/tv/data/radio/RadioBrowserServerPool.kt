package com.ambient.tv.data.radio

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Radio Browser explicitly asks clients to discover mirrors through all.api.radio-browser.info,
 * randomize them and retry another mirror when one is unavailable.
 */
@Singleton
class RadioBrowserServerPool @Inject constructor() {
    @Volatile private var cached: Cache? = null

    fun hosts(nowMs: Long = System.currentTimeMillis()): List<String> {
        cached?.takeIf { nowMs - it.createdAtMs < CACHE_MS && it.hosts.isNotEmpty() }?.let { return it.hosts }
        synchronized(this) {
            cached?.takeIf { nowMs - it.createdAtMs < CACHE_MS && it.hosts.isNotEmpty() }?.let { return it.hosts }
            val discovered = discover()
            val hosts = (discovered.shuffled() + FALLBACK_HOSTS).distinct()
            cached = Cache(nowMs, hosts)
            return hosts
        }
    }

    internal fun clear() {
        cached = null
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
        private val FALLBACK_HOSTS = listOf("de1.api.radio-browser.info", "nl1.api.radio-browser.info")
        private val CACHE_MS = TimeUnit.HOURS.toMillis(1)
    }
}
