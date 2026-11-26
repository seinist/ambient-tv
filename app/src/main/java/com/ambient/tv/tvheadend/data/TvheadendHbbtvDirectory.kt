package com.ambient.tv.tvheadend.data

import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvHbbtvApp
import kotlinx.serialization.Serializable

/** Only server-provided AIT metadata; never tunes a channel. Independently successful transports
 * retain their own results so an unavailable HTTP or HTSP endpoint cannot erase the other. */
@Serializable
internal data class TvheadendHbbtvDirectory(
    val htspPort: Int,
    val servicesByChannel: Map<String, List<String>> = emptyMap(),
    val httpByService: Map<String, List<LiveTvHbbtvApp>> = emptyMap(),
    val htspByChannel: Map<String, List<LiveTvHbbtvApp>> = emptyMap(),
    val legacyByChannel: Map<String, List<LiveTvHbbtvApp>> = emptyMap(),
    val httpLoadedAt: Long = 0,
    val htspLoadedAt: Long = 0,
) {
    fun withTopology(topology: Map<String, List<String>>): TvheadendHbbtvDirectory {
        if (topology == servicesByChannel) return this
        val changed = servicesByChannel.filter { (id, services) ->
            services.isNotEmpty() && topology[id] != services
        }.keys
        val usedServices = topology.values.flatten().toHashSet()
        return copy(
            servicesByChannel = topology,
            httpByService = httpByService.filterKeys { it in usedServices },
            htspByChannel = htspByChannel.filterKeys { it in topology && it !in changed },
            legacyByChannel = legacyByChannel.filterKeys { it in topology && it !in changed },
            httpLoadedAt = 0, htspLoadedAt = 0,
        )
    }

    fun withHtspPort(port: Int): TvheadendHbbtvDirectory =
        if (port == htspPort) this else copy(htspPort = port, htspByChannel = emptyMap(),
            htspLoadedAt = 0, legacyByChannel = emptyMap())

    fun withHttp(result: Map<String, List<LiveTvHbbtvApp>>, now: Long): TvheadendHbbtvDirectory {
        val services = servicesByChannel.values.flatten().toHashSet()
        // Missing rows can mean permission-filtered service metadata. Only a returned
        // service (including an explicit empty application list) replaces its previous value.
        val updated = (httpByService + result).filterKeys { it in services }
        return copy(
            httpByService = updated,
            httpLoadedAt = now,
            legacyByChannel = legacyByChannel.filterKeys { id ->
                val mapped = servicesByChannel[id].orEmpty()
                val covered = mapped.isNotEmpty() && mapped.all { it in result }
                val positive = mapped.any { result[it].orEmpty().isNotEmpty() }
                !positive && !(covered && id in htspByChannel)
            },
        )
    }

    fun withHtsp(result: Map<String, List<LiveTvHbbtvApp>>, now: Long): TvheadendHbbtvDirectory =
        copy(htspByChannel = (htspByChannel + result).filterKeys { it in servicesByChannel },
            htspLoadedAt = now, legacyByChannel = legacyByChannel.filterKeys { id ->
                val mapped = servicesByChannel[id].orEmpty()
                val httpCovered = mapped.isNotEmpty() && mapped.all { it in httpByService }
                result[id].orEmpty().isEmpty() && !(id in result && httpCovered)
            })

    @kotlinx.serialization.Transient
    private val resolvedApps = servicesByChannel.keys.associateWith(::resolveApps)

    fun apps(channelId: String): List<LiveTvHbbtvApp> = resolvedApps[channelId].orEmpty()

    private fun resolveApps(channelId: String): List<LiveTvHbbtvApp> {
        return (servicesByChannel[channelId].orEmpty().flatMap { httpByService[it].orEmpty() } +
            htspByChannel[channelId].orEmpty() + legacyByChannel[channelId].orEmpty())
            .filter { it.visibility != "none" }
            .distinctBy { it.url }
            .sortedBy { if (it.visibility == "all") 0 else if (it.visibility == "apps") 1 else 2 }
    }

    fun enrich(channel: LiveTvChannel): LiveTvChannel {
        val apps = apps(channel.uuid)
        val first = apps.firstOrNull()
        if (channel.hbbtvApps == apps && channel.hbbtvUrl == first?.url && channel.hbbtvName == first?.name) return channel
        return channel.copy(hbbtvApps = apps, hbbtvUrl = first?.url, hbbtvName = first?.name)
    }

    fun enrich(channels: List<LiveTvChannel>): List<LiveTvChannel> {
        var changed = false
        val result = channels.map { channel -> enrich(channel).also { if (it !== channel) changed = true } }
        return if (changed) result else channels
    }

    @kotlinx.serialization.Transient
    private val menuApps = resolvedApps.values.any { apps -> apps.any { it.visibility == "all" || it.visibility == "apps" } }

    fun hasMenuApps(): Boolean = menuApps

    companion object {
        /** Upgrade the old combined channel cache without a network round trip. Its origin is
         * unknown, so keep it until either transport provides evidence for that channel. */
        fun fromLegacy(port: Int, channels: List<LiveTvChannel>) = TvheadendHbbtvDirectory(
            htspPort = port,
            servicesByChannel = channels.associate { it.uuid to emptyList() },
            legacyByChannel = channels.associate { channel ->
                channel.uuid to channel.hbbtvApps.ifEmpty {
                    listOfNotNull(channel.hbbtvUrl?.let { LiveTvHbbtvApp(it, channel.hbbtvName) })
                }
            }.filterValues { it.isNotEmpty() },
        )
    }
}

internal fun hbbtvRefreshDue(loadedAt: Long, lastAttemptElapsed: Long?, now: Long, elapsed: Long, force: Boolean): Boolean {
    val fresh = loadedAt > 0 && now >= loadedAt && now - loadedAt < 6 * 3600_000L
    if (!force && fresh) return false
    val cooldown = if (force) 30_000L else 15 * 60_000L
    return lastAttemptElapsed == null || elapsed < lastAttemptElapsed || elapsed - lastAttemptElapsed >= cooldown
}
