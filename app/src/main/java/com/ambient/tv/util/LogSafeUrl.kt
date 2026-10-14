package com.ambient.tv.util

private val URL = Regex("(\\w+://)([^/\\s?\"']+)([^\\s?\"']*)(\\?[^\\s\"']*)?")

/**
 * Makes URLs in log text safe for logcat. Queries are dropped: signed stream URLs carry the client
 * IP, PoTokens, signatures and server tokens there. googlevideo also puts them into path segments
 * (/ip/…/expire/…), so its paths go too. Scheme, host and other paths are enough to diagnose.
 */
fun String.withoutUrlQueries(): String = replace(URL) { match ->
    val (scheme, host, path, query) = match.destructured
    when {
        host.endsWith("googlevideo.com", ignoreCase = true) -> "$scheme$host/***"
        query.isNotEmpty() -> "$scheme$host$path?***"
        else -> match.value
    }
}
