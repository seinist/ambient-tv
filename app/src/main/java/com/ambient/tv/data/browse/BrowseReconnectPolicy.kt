package com.ambient.tv.data.browse

/** A family watches its members; a member also watches a refresh of its whole family. */
internal fun browseSourceMatches(changed: String, watched: Set<String>): Boolean =
    watched.isNotEmpty() && (changed == "*" || "*" in watched || watched.any {
        it == changed || changed.startsWith("$it:") || it.startsWith("$changed:")
    })

/** Globally ordered revisions let a view keep one durable cursor for several sources. */
internal fun browseReconnectVersion(revisions: Map<String, Long>, sources: Set<String>): Long =
    revisions.entries.asSequence().filter { browseSourceMatches(it.key, sources) }.maxOfOrNull { it.value } ?: 0L

internal class BrowseReconnectRevisions {
    private var sequence = 0L
    private var revisions = emptyMap<String, Long>()

    @Synchronized fun publish(sources: Set<String>): Map<String, Long> {
        if (sources.isEmpty()) return revisions
        val next = ++sequence
        revisions = revisions + sources.associateWith { next }
        return revisions
    }
}

/** Internet recovery also covers IPTV and media servers reached through a remote route. */
internal class BrowseNetworkRecovery {
    private var initialized = false
    private var previousNetwork: String? = null
    private var previousInternet = false

    @Synchronized fun update(network: String?, internetReady: Boolean): Set<String> {
        val recovered = when {
            !initialized || network == null -> emptySet()
            network != previousNetwork -> setOf("*")
            internetReady && !previousInternet -> setOf("*")
            else -> emptySet()
        }
        initialized = true
        previousNetwork = network
        previousInternet = network != null && internetReady
        return recovered
    }
}
