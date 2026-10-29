package com.ambient.tv.ui.settings

import com.ambient.tv.network.NetworkEndpoint

/** Everything on the Wake page that can be woken: media servers, SMB/NFS sources and DLNA servers. */
internal enum class WakeMemberKind(val linkTarget: String) {
    PLEX("plex"), JELLYFIN("jellyfin"), EMBY("emby"), TVHEADEND("tvheadend"), FILES("files"), DLNA("dlna");

    val isMediaServer: Boolean get() = this == PLEX || this == JELLYFIN || this == EMBY || this == TVHEADEND
}

/** One wakeable service. [id] is the system key for media servers, the source/server id otherwise. */
internal data class WakeMember(
    val kind: WakeMemberKind,
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val mac: String,
    val autoWake: Boolean,
    val wireGuard: Boolean = false,
    val broadcast: String = "255.255.255.255",
    val wolPort: Int = 9,
) {
    val normalizedHost: String get() = normalizeWakeHost(host)
}

/** One physical machine: all services sharing a host address or a MAC address. */
internal data class WakeHostGroup(val members: List<WakeMember>) {
    /** Stable while the first member keeps its identity, so Compose keys and focus survive edits. */
    val key: String get() = members.first().let { "${it.kind.linkTarget}:${it.id}" }

    val hosts: List<String> get() = members.map { it.normalizedHost }.filter(String::isNotBlank).distinct()

    /** Distinct valid MACs in member order (media servers first), so the active server's MAC wins. */
    val macs: List<String> get() = members.mapNotNull { canonicalWakeMac(it.mac) }.distinct()

    val mac: String get() = macs.firstOrNull().orEmpty()

    /** Several members carry different MACs: show a hint and let the user decide before overwriting. */
    val macConflict: Boolean get() = macs.size > 1

    /** Members without the group MAC (blank, invalid or different); "apply" fills them in. */
    val membersMissingMac: List<WakeMember> get() = members.filter { canonicalWakeMac(it.mac) != mac }

    val wireGuard: Boolean get() = members.any { it.wireGuard }

    /** Address used for MAC lookup and connection tests: the first member with a host. */
    val probe: WakeMember? get() = members.firstOrNull { it.normalizedHost.isNotBlank() }

    /** A NAS source may carry a directed broadcast/port; plain media servers use the defaults. */
    val wakeTarget: WakeMember get() = members.firstOrNull { it.kind == WakeMemberKind.FILES && canonicalWakeMac(it.mac) == mac } ?: members.first()

    fun contains(linkTarget: String): Boolean = members.any { it.kind.linkTarget == linkTarget }
}

/** Host part of an address, compared case-insensitively and without a trailing dot. */
internal fun normalizeWakeHost(value: String): String =
    NetworkEndpoint.normalizeHost(value).lowercase().trimEnd('.')

/** Canonical "AA:BB:CC:DD:EE:FF" or null when the value is no usable unicast MAC. */
internal fun canonicalWakeMac(value: String): String? {
    val hex = value.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.uppercase()
    if (hex.length != 12) return null
    val bytes = hex.chunked(2).map { it.toInt(16) }
    if (bytes.all { it == 0 } || bytes.all { it == 0xFF }) return null
    // Wake-on-LAN targets a single NIC; multicast MAC addresses are not valid targets.
    if ((bytes.first() and 0x01) != 0) return null
    return bytes.joinToString(":") { "%02X".format(it) }
}

/**
 * Merges services that run on the same machine: same host address, or the same MAC
 * (which also catches "nas.local" next to "192.168.1.10"). Groups and members keep the
 * input order, so media servers stay on top as before.
 */
internal fun groupWakeTargets(members: List<WakeMember>): List<WakeHostGroup> {
    if (members.isEmpty()) return emptyList()
    val parent = IntArray(members.size) { it }
    fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
    fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) }

    val firstByHost = mutableMapOf<String, Int>()
    val firstByMac = mutableMapOf<String, Int>()
    members.forEachIndexed { index, member ->
        member.normalizedHost.takeIf(String::isNotBlank)?.let { host ->
            firstByHost[host]?.let { union(it, index) } ?: firstByHost.put(host, index)
        }
        canonicalWakeMac(member.mac)?.let { mac ->
            firstByMac[mac]?.let { union(it, index) } ?: firstByMac.put(mac, index)
        }
    }
    return members.indices.groupBy(::find).values.map { indices -> WakeHostGroup(indices.map(members::get)) }
}

/** Stable list key for a server link; headers and profile pickers do not change its destination. */
internal fun networkWakeTargetKey(groups: List<WakeHostGroup>, target: String): String? {
    val group = groups.firstOrNull { it.contains(target) }
    return when {
        group != null -> "wake-group:${group.key}"
        target == "files" || target == "dlna" -> "wake-sources"
        else -> null
    }
}
