package com.ambient.tv.launcher

import android.content.Intent
import com.ambient.tv.connectivity.PlaybackServerUnreachable
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.server.EmbyConnectionManager
import com.ambient.tv.server.JellyfinConnectionManager
import com.ambient.tv.server.ServerConnectionManager
import com.ambient.tv.ui.common.MediaSystem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Opens a title selected in a launcher row. A launcher entry skips the Home screen, which is
 * where the app normally connects, so the same connect runs first here: VPN route, reachability,
 * Wake-on-LAN with the full wait, and the usual waking/unreachable notices. Only when it succeeds
 * does the app navigate; otherwise it stays on Home with the connection notice. Used only for
 * launcher entries and only while the launcher integration is enabled.
 */
@Singleton
class LauncherOpener @Inject constructor(
    private val preferences: AppPreferences,
    private val plex: ServerConnectionManager,
    private val jellyfin: JellyfinConnectionManager,
    private val emby: EmbyConnectionManager,
    private val tvheadendConnection: com.ambient.tv.server.TvheadendConnectionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** True when [intent] was a launcher entry (handled or deliberately ignored). */
    fun open(intent: Intent?): Boolean {
        val request = LauncherIntents.parse(intent) ?: return false
        // Switched off: rows are gone; a stale entry just opens the app.
        if (!preferences.launcherIntegrationEnabled) return true
        job?.cancel()
        job = scope.launch {
            // Waking a server can take minutes; whatever the user started meanwhile wins.
            val playingBefore = com.ambient.tv.ui.player.BackgroundPlaybackSession.snapshot.value.metadata?.route
            val connected = runCatching {
                when (request) {
                    is LauncherIntents.Request.Title -> when (PlaybackServerUnreachable.mediaSystemForRatingKey(request.key)) {
                        MediaSystem.PLEX -> plex.ensureConnected()
                        MediaSystem.JELLYFIN -> jellyfin.ensureConnected()
                        MediaSystem.EMBY -> emby.ensureConnected()
                        else -> false
                    }
                    // Live TV source ids start with their system; TVHeadend's own may be blank.
                    is LauncherIntents.Request.Channel -> when (request.sourceId.substringBefore(':')) {
                        "plex" -> plex.ensureConnected()
                        "jellyfin" -> jellyfin.ensureConnected()
                        "emby" -> emby.ensureConnected()
                        else -> tvheadendConnection.ensureConnected()
                    }
                }
            }.onFailure { Timber.w(it, "Launcher open: connect failed") }.getOrDefault(false)
            val playingNow = com.ambient.tv.ui.player.BackgroundPlaybackSession.snapshot.value.metadata?.route
            if (connected && playingNow == playingBefore) LauncherIntents.post(request.route)
        }
        return true
    }
}
