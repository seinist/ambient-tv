package com.ambient.tv.connectivity

import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.WakeOnLan
import com.ambient.tv.data.dlna.DlnaRepository
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.server.EmbyConnectionManager
import com.ambient.tv.server.JellyfinConnectionManager
import com.ambient.tv.server.ServerConnectionManager
import com.ambient.tv.ui.common.MediaSystem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot WoL + short re-health used when local-media playback hits a network/EOF/timeout error.
 * Rate-limiting / coalescing is inherited from [com.ambient.tv.wol.WolManager] (Files [WakeOnLan] delegates there).
 */
@Singleton
class PlaybackServerRecovery @Inject constructor(
    private val preferences: AppPreferences,
    private val plexConnection: ServerConnectionManager,
    private val jellyfinConnection: JellyfinConnectionManager,
    private val embyConnection: EmbyConnectionManager,
    private val tvheadendConnection: com.ambient.tv.server.TvheadendConnectionManager,
    private val wakeOnLan: WakeOnLan,
    private val dlnaRepository: DlnaRepository,
) {
    suspend fun tryWakeAndRecheck(ratingKey: String): Boolean {
        val system = PlaybackServerUnreachable.mediaSystemForRatingKey(ratingKey) ?: return false
        return when (system) {
            MediaSystem.PLEX -> plexConnection.recheckOnResume()
            MediaSystem.JELLYFIN -> jellyfinConnection.recheckOnResume()
            MediaSystem.EMBY -> embyConnection.recheckOnResume()
            MediaSystem.TVHEADEND -> tvheadendConnection.recheckOnResume()
            MediaSystem.FILES -> wakeFilesSource(ratingKey)
            MediaSystem.DLNA -> dlnaRepository.getMetadata(ratingKey).getOrNull() != null
            else -> false
        }
    }

    /** Whether [tryWakeAndRecheck] can send a wake packet for [ratingKey], or only look again. */
    fun wakeConfigured(ratingKey: String): Boolean = when (PlaybackServerUnreachable.mediaSystemForRatingKey(ratingKey)) {
        MediaSystem.PLEX -> preferences.autoWakeEnabled && !preferences.macAddress.isNullOrBlank()
        MediaSystem.JELLYFIN -> preferences.jellyfinAutoWakeEnabled && !preferences.jellyfinMacAddress.isNullOrBlank()
        MediaSystem.EMBY -> preferences.embyAutoWakeEnabled && !preferences.embyMacAddress.isNullOrBlank()
        MediaSystem.TVHEADEND -> preferences.tvheadendAutoWakeEnabled && !preferences.tvheadendMacAddress.isNullOrBlank()
        MediaSystem.FILES -> runCatching { FileKeys.parse(ratingKey).first }.getOrNull()
            ?.let { id -> preferences.fileSources.firstOrNull { it.id == id } }
            ?.let { it.wolAuto && it.wolMac.isNotBlank() } == true
        else -> false
    }

    private suspend fun wakeFilesSource(ratingKey: String): Boolean {
        if (!FileKeys.owns(ratingKey)) return false
        val sourceId = runCatching { FileKeys.parse(ratingKey).first }.getOrNull() ?: return false
        val source = preferences.fileSources.firstOrNull { it.id == sourceId } ?: return false
        if (source.kind == "local") return true
        return runCatching {
            wakeOnLan.ensureAwake(source)
            true
        }.getOrDefault(false)
    }
}
