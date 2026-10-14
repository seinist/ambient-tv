package com.ambient.tv.data.browse

import android.net.Uri
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.repository.PlexRepository
import com.ambient.tv.data.jellyfin.JellyfinRepository
import com.ambient.tv.data.emby.EmbyRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

/** Used before navigation and again by playback owners (deep links/Companion can bypass navigation). */
@Singleton
class PlaybackAvailability @Inject constructor(
    private val availability: SourceAvailability,
    private val preferences: AppPreferences,
    private val plex: PlexRepository,
    private val jellyfin: JellyfinRepository,
    private val emby: EmbyRepository,
) {
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
        if (provider !in setOf("plex", "jellyfin", "emby")) return true
        if (availability.state(provider) == SourceHealth.ONLINE) return true
        val reachable = withTimeoutOrNull(2_500) {
            when (provider) {
                "jellyfin" -> jellyfin.getIdentity()
                "emby" -> emby.getIdentity()
                else -> plex.getIdentity()
            }.isSuccess
        } == true
        if (!reachable) availability.notifyBlocked()
        return reachable
    }

    suspend fun route(route: String): Boolean = when {
        route.startsWith("player/") -> media(Uri.decode(route.substringAfter("player/").substringBefore('?')))
        route.startsWith("youtube_player/") || route.startsWith("tvh_player/") || route.startsWith("spotify_player") -> network()
        else -> true
    }
}
