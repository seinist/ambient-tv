package com.ambient.tv.wol

import com.ambient.tv.connectivity.mediaServerProfileSourceId
import com.ambient.tv.connectivity.EndpointConfigurationKey
import com.ambient.tv.data.dlna.DlnaServer
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

/** One immutable grouping, reused for every lookup until the configured endpoints change. */
internal class WakeGroupIndex(endpoints: List<WakeEndpoint>) {
    private val endpoints = endpoints.toList()
    private val keys = resolveWakeGroupKeys(this.endpoints)
    private val byHost = this.endpoints.mapIndexedNotNull { i, endpoint ->
        normalizeWakeGroupHost(endpoint.host)?.let { it to keys[i] }
    }.toMap()
    private val byMac = this.endpoints.mapIndexedNotNull { i, endpoint ->
        canonicalWolMacKey(endpoint.mac)?.let { it to keys[i] }
    }.toMap()

    private fun joinedGroups(host: String?, mac: String?): List<String> = listOfNotNull(
        normalizeWakeGroupHost(host)?.let(byHost::get),
        canonicalWolMacKey(mac)?.let(byMac::get),
    )

    fun keyFor(system: String, host: String?, mac: String?): String {
        // An arbitrary/manual target can bridge two configured groups by host and MAC.
        // Include its own key: a newly supplied MAC may become the group's smallest MAC.
        val candidates = joinedGroups(host, mac) + wakeGroupKey(mac, host, system)
        return candidates.filter { it.startsWith("mac:") }.minOrNull()
            ?: candidates.filter { it.startsWith("host:") }.minOrNull()
            ?: candidates.last()
    }

    fun groupFor(system: String, host: String?, mac: String?): Pair<String, List<String>> {
        val joined = joinedGroups(host, mac)
        // Preserve the existing order: the requested target's MAC first, then configured order.
        val macs = listOfNotNull(canonicalWolMacKey(mac)) + endpoints.mapIndexedNotNull { i, endpoint ->
            canonicalWolMacKey(endpoint.mac).takeIf { keys[i] in joined }
        }
        return keyFor(system, host, mac) to macs.distinct()
    }
}

internal class WakeMachineSnapshot(
    val configuration: EndpointConfigurationKey,
    val dlnaServers: List<DlnaServer>,
    private val groups: WakeGroupIndex,
) {
    fun keyFor(system: String, host: String?, mac: String?): String = groups.keyFor(system, host, mac)
    fun groupFor(system: String, host: String?, mac: String?): Pair<String, List<String>> = groups.groupFor(system, host, mac)
}

/** Resolves the shared machine key of any wake target against every configured system. */
@Singleton
class WakeMachines @Inject constructor(
    private val preferences: AppPreferences,
    private val dlnaStore: DlnaStore,
) {
    private val lock = Any()
    @Volatile private var cached: WakeMachineSnapshot? = null

    internal fun snapshot(): WakeMachineSnapshot {
        val configuration = preferences.connectivityConfigurationKey()
        val servers = dlnaStore.servers()
        cached?.takeIf { it.configuration == configuration && it.dlnaServers == servers }?.let { return it }
        synchronized(lock) {
            while (true) {
                val current = preferences.connectivityConfigurationKey()
                val currentServers = dlnaStore.servers()
                cached?.takeIf { it.configuration == current && it.dlnaServers == currentServers }?.let { return it }
                // Preserve the old per-target fallback if unrelated saved metadata is unreadable.
                val groups = runCatching { WakeGroupIndex(configured(currentServers)) }
                    .getOrElse { WakeGroupIndex(emptyList()) }
                // Profile migration or a concurrent settings import may have changed the inputs.
                if (current != preferences.connectivityConfigurationKey() || currentServers != dlnaStore.servers()) continue
                return WakeMachineSnapshot(current, currentServers, groups).also { cached = it }
            }
        }
    }

    fun keyFor(system: String, host: String?, mac: String?): String =
        runCatching { snapshot().keyFor(system, host, mac) }
            .getOrElse { wakeGroupKey(mac, host, system) }

    /** Machine key plus every distinct canonical MAC configured anywhere on that machine. */
    fun groupFor(system: String, host: String?, mac: String?): Pair<String, List<String>> =
        runCatching { snapshot().groupFor(system, host, mac) }
            .getOrElse { wakeGroupKey(mac, host, system) to listOfNotNull(canonicalWolMacKey(mac)) }

    private fun configured(servers: List<DlnaServer>): List<WakeEndpoint> = buildList {
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
        if (com.ambient.tv.BuildConfig.MOONLIGHT_NATIVE && preferences.hasMoonlightHosts)
            preferences.moonlightHosts.forEach { add(WakeEndpoint("moonlight:" + it.id, it.host, it.macAddress)) }
        preferences.fileSources.filter { it.kind != "local" }.forEach { add(WakeEndpoint("files", it.host, it.wolMac)) }
        servers.forEach { add(WakeEndpoint("dlna", dlnaWakeEndpoint(it)?.first, it.wolMac)) }
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
