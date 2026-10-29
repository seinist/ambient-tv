package com.ambient.tv.wol

import com.ambient.tv.connectivity.mediaServerProfileSourceId
import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.data.dlna.dlnaWakeEndpoint
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.network.NetworkEndpoint
import javax.inject.Inject
import javax.inject.Singleton

/** One wakeable endpoint of any system (media server, NAS source, DLNA server). */
internal data class WakeEndpoint(val system: String, val host: String?, val mac: String?)

/** Host part compared case-insensitively, without scheme, port or trailing dot; null when blank. */
internal fun normalizeWakeGroupHost(host: String?): String? =
    host?.let { NetworkEndpoint.normalizeHost(it).lowercase().trimEnd('.') }?.takeIf(String::isNotBlank)

/** Key of a single endpoint: canonical MAC first, normalized host as fallback. */
internal fun wakeGroupKey(mac: String?, host: String?, system: String = ""): String =
    canonicalWolMacKey(mac)?.let { "mac:$it" }
        ?: normalizeWakeGroupHost(host)?.let { "host:$it" }
        ?: "system:$system"

/**
 * One machine per group: endpoints sharing a MAC or a host are merged transitively
 * (Plex with MAC on 192.168.1.10 + TVHeadend without MAC on the same host + a NAS source
 * carrying that MAC under another hostname all end up together). The group key prefers the
 * smallest MAC key, then the smallest host key, so it is independent of input order.
 */
internal fun resolveWakeGroupKeys(endpoints: List<WakeEndpoint>): List<String> {
    if (endpoints.isEmpty()) return emptyList()
    val parent = IntArray(endpoints.size) { it }
    fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
    fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) }
    val byHost = mutableMapOf<String, Int>()
    val byMac = mutableMapOf<String, Int>()
    endpoints.forEachIndexed { index, endpoint ->
        normalizeWakeGroupHost(endpoint.host)?.let { host -> byHost[host]?.let { union(it, index) } ?: byHost.put(host, index) }
        canonicalWolMacKey(endpoint.mac)?.let { mac -> byMac[mac]?.let { union(it, index) } ?: byMac.put(mac, index) }
    }
    val keys = endpoints.indices.groupBy(::find).mapValues { (_, members) ->
        val own = members.map { wakeGroupKey(endpoints[it].mac, endpoints[it].host, endpoints[it].system) }
        own.filter { it.startsWith("mac:") }.minOrNull()
            ?: own.filter { it.startsWith("host:") }.minOrNull()
            ?: own.first()
    }
    return endpoints.indices.map { keys.getValue(find(it)) }
}

/** Resolves the shared machine key of any wake target against every configured system. */
@Singleton
class WakeMachines @Inject constructor(
    private val preferences: AppPreferences,
    private val dlnaStore: DlnaStore,
) {
    fun keyFor(system: String, host: String?, mac: String?): String =
        runCatching { resolveWakeGroupKeys(configured() + WakeEndpoint(system, host, mac)).last() }
            .getOrElse { wakeGroupKey(mac, host, system) }

    /** Machine key plus every distinct canonical MAC configured anywhere on that machine. */
    fun groupFor(system: String, host: String?, mac: String?): Pair<String, List<String>> =
        runCatching { macsOfGroup(configured() + WakeEndpoint(system, host, mac)) }
            .getOrElse { wakeGroupKey(mac, host, system) to listOfNotNull(canonicalWolMacKey(mac)) }

    private fun configured(): List<WakeEndpoint> = buildList {
        if (preferences.host.isNotBlank()) add(WakeEndpoint("plex", preferences.host, preferences.macAddress))
        if (preferences.jellyfinHost.isNotBlank()) add(WakeEndpoint("jellyfin", preferences.jellyfinHost, preferences.jellyfinMacAddress))
        if (preferences.embyHost.isNotBlank()) add(WakeEndpoint("emby", preferences.embyHost, preferences.embyMacAddress))
        if (preferences.tvheadendHost.isNotBlank()) add(WakeEndpoint("tvheadend", preferences.tvheadendHost, preferences.tvheadendMacAddress))
        // Saved Live TV profiles must share the same machine even while another profile is active.
        if (preferences.plexEnabled) preferences.serverProfiles.forEach { profile ->
            mediaServerProfileSourceId("plex", profile.id)?.let {
                add(WakeEndpoint(it, profile.host, profile.macAddress))
            }
        }
        if (preferences.jellyfinEnabled) preferences.jellyfinServerProfiles.filter { it.authenticated }.forEach { profile ->
            mediaServerProfileSourceId("jellyfin", profile.id, profile.userId)?.let {
                add(WakeEndpoint(it, profile.host, profile.macAddress))
            }
        }
        if (preferences.embyEnabled) preferences.embyServerProfiles.filter { it.authenticated }.forEach { profile ->
            mediaServerProfileSourceId("emby", profile.id, profile.userId)?.let {
                add(WakeEndpoint(it, profile.host, profile.macAddress))
            }
        }
        preferences.fileSources.filter { it.kind != "local" }.forEach { add(WakeEndpoint("files", it.host, it.wolMac)) }
        dlnaStore.servers().forEach { add(WakeEndpoint("dlna", dlnaWakeEndpoint(it)?.first, it.wolMac)) }
    }
}

/** Key of the last endpoint's machine and all MACs in it, the last endpoint's own MAC first. */
internal fun macsOfGroup(endpoints: List<WakeEndpoint>): Pair<String, List<String>> {
    val keys = resolveWakeGroupKeys(endpoints)
    val key = keys.last()
    val macs = (listOf(endpoints.last()) + endpoints.filterIndexed { i, _ -> keys[i] == key })
        .mapNotNull { canonicalWolMacKey(it.mac) }.distinct()
    return key to macs
}
