package com.ambient.tv.data.browse

import android.net.Uri
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.repository.PlexRepository
import com.ambient.tv.data.jellyfin.JellyfinRepository
import com.ambient.tv.data.emby.EmbyRepository
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Used before navigation and again by playback owners (deep links/Companion can bypass navigation).
 * A remote system that does not answer never reaches the player: the user gets a notice naming
 * it instead, and a server with Wake-on-LAN starts waking in the background (with its progress card).
 */
@Singleton
class PlaybackAvailability @Inject constructor(
    private val availability: SourceAvailability,
    private val preferences: AppPreferences,
    private val plex: PlexRepository,
    private val jellyfin: JellyfinRepository,
    private val emby: EmbyRepository,
    private val tvheadend: com.ambient.tv.tvheadend.data.TvheadendRepository,
    private val hosts: com.ambient.tv.connectivity.HostConnectivity,
    private val plexConnection: Provider<com.ambient.tv.server.ServerConnectionManager>,
    private val jellyfinConnection: Provider<com.ambient.tv.server.JellyfinConnectionManager>,
    private val embyConnection: Provider<com.ambient.tv.server.EmbyConnectionManager>,
    private val tvheadendConnection: Provider<com.ambient.tv.server.TvheadendConnectionManager>,
) {
    private val wakeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun network(): Boolean {
        BrowseWorkBudget.interact()
        return availability.hasNetwork().also { if (!it) availability.notifyBlocked() }
    }

    suspend fun media(id: String): Boolean {
        BrowseWorkBudget.interact()
        val provider = mediaProvider(id)
        if (provider == "shared") return true // The receiving device resolves its source and permissions.
        if (provider == "files") {
            val sourceId = runCatching { com.ambient.tv.data.files.FileKeys.parse(id).first }.getOrNull()
            if (preferences.fileSources.any { it.id == sourceId && it.kind == "local" }) return true
        }
        if (!network()) return false
        val reachable = when (provider) {
            "plex", "jellyfin", "emby" -> serverReachable(provider)
            // Network shares and DLNA servers: their host must answer.
            "files", "dlna" -> hosts.mediaSystem(provider, id)?.let { hostReachable(it) } ?: true
            else -> true
        }
        if (!reachable) blocked(provider, hosts.mediaSystem(provider, id))
        return reachable
    }

    suspend fun route(route: String): Boolean = when {
        route.startsWith("player/") -> media(Uri.decode(route.substringAfter("player/").substringBefore('?')))
        route.startsWith("tvh_player/") -> network() && liveSource(route)
        route.startsWith("youtube_player/") || route.startsWith("spotify_player") -> network()
        else -> true
    }

    /** Live TV: the system behind the channel (TVHeadend, or a media server's live TV) must answer. */
    private suspend fun liveSource(route: String): Boolean {
        val sourceId = route.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("sourceId=") }?.substringAfter('=')?.let(Uri::decode).orEmpty()
        // Live TV source ids start with their system; TVHeadend's own may be blank.
        val system = sourceId.substringBefore(':').takeIf { it in setOf("plex", "jellyfin", "emby", "iptv") } ?: "tvheadend"
        val reachable = when (system) {
            "iptv" -> true
            "tvheadend" -> !preferences.isTvheadendConfigured || withTimeoutOrNull(2_500) { tvheadend.portReachable() } == true
            else -> serverReachable(system)
        }
        if (!reachable) blocked(system, null)
        return reachable
    }

    private suspend fun serverReachable(provider: String): Boolean {
        if (availability.state(provider) == SourceHealth.ONLINE) return true
        return withTimeoutOrNull(2_500) {
            when (provider) {
                "jellyfin" -> jellyfin.getIdentity()
                "emby" -> emby.getIdentity()
                else -> plex.getIdentity()
            }.isSuccess
        } == true
    }

    private suspend fun hostReachable(system: String): Boolean =
        hosts.machine(system) == null || withTimeoutOrNull(2_500) { hosts.verifySystem(system) } == true

    /** Says which system is down and, when it can be woken, starts that wake right away. */
    private fun blocked(provider: String, hostSystem: String?) {
        val name = displayName(provider, hostSystem)
        val wake: (suspend () -> Boolean)? = when (provider) {
            "plex" -> plexConnection.get()::reconnect.takeIf { preferences.autoWakeEnabled && !preferences.macAddress.isNullOrBlank() }
            "jellyfin" -> jellyfinConnection.get()::reconnect.takeIf { preferences.jellyfinAutoWakeEnabled && !preferences.jellyfinMacAddress.isNullOrBlank() }
            "emby" -> embyConnection.get()::reconnect.takeIf { preferences.embyAutoWakeEnabled && !preferences.embyMacAddress.isNullOrBlank() }
            "tvheadend" -> tvheadendConnection.get()::reconnect.takeIf { preferences.tvheadendAutoWakeEnabled && !preferences.tvheadendMacAddress.isNullOrBlank() }
            else -> null
        }
        if (wake != null) wakeScope.launch { runCatching { wake() } }
        com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
            key = "playback:unavailable",
            message = if (wake != null) com.ambient.tv.R.string.browse_playback_waking else com.ambient.tv.R.string.browse_playback_offline,
            systems = listOfNotNull(provider.takeIf { com.ambient.tv.ui.common.MediaSystem.fromKey(it) != null }),
            args = listOf(name),
            phase = com.ambient.tv.notifications.NoticePhase.ERROR, kind = com.ambient.tv.notifications.NoticeKind.PLAYBACK,
        ))
    }

    private fun displayName(provider: String, hostSystem: String?): String {
        if (provider == "files") {
            val id = hostSystem?.substringAfter("files:")
            preferences.fileSources.firstOrNull { it.id == id }?.name?.takeIf(String::isNotBlank)?.let { return it }
        }
        val system = com.ambient.tv.ui.common.MediaSystem.fromKey(provider)
        return system?.let { preferences.systemNames[it.key]?.takeIf(String::isNotBlank) ?: it.brand } ?: provider
    }
}
