package com.ambient.tv.data.browse

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor

enum class SourceHealth { UNKNOWN, ONLINE, OFFLINE, AUTH_REQUIRED }

/** Network presence is not internet validation: an isolated LAN can still serve media. */
@Singleton
class SourceAvailability @Inject constructor(@ApplicationContext context: Context, private val scopes: BrowseScopes, private val preferences: com.ambient.tv.data.local.AppPreferences) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow(0L)
    val changes = mutable.asStateFlow()
    private val health = mutableMapOf<String, SourceHealth>()

    init {
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = changed()
                override fun onLost(network: Network) = changed()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
            })
        }
    }

    @Synchronized private fun changed() {
        if (!hasNetwork()) listOf("plex", "jellyfin", "emby", "youtube", "spotify").forEach { health[scopes.forProvider(it)] = SourceHealth.OFFLINE }
        else health.replaceAll { _, value -> if (value == SourceHealth.ONLINE) SourceHealth.UNKNOWN else value }
        mutable.value++
    }
    fun online(provider: String) = report(scopes.forProvider(provider), SourceHealth.ONLINE)
    fun unreachable(provider: String) = report(scopes.forProvider(provider), SourceHealth.OFFLINE)
    fun mediaState(id: String): SourceHealth {
        if (mediaProvider(id) == "files") {
            val source = runCatching { com.ambient.tv.data.files.FileKeys.parse(id).first }.getOrNull()
            if (preferences.fileSources.any { it.id == source && it.kind == "local" }) return SourceHealth.ONLINE
        }
        return state(mediaProvider(id))
    }
    fun hasNetwork(): Boolean = connectivity.activeNetwork != null
    @Synchronized fun state(provider: String): SourceHealth =
        if (!hasNetwork()) SourceHealth.OFFLINE else health[scopes.forProvider(provider)] ?: SourceHealth.UNKNOWN

    @Synchronized private fun report(scope: String, state: SourceHealth) {
        if (health.put(scope, state) != state) mutable.value++
    }

    fun interceptor(provider: String) = Interceptor { chain ->
        val scope = scopes.forProvider(provider)
        try {
            chain.proceed(chain.request()).also { response ->
                if (scope == scopes.forProvider(provider)) report(scope, when (response.code) {
                    401, 403 -> SourceHealth.AUTH_REQUIRED
                    in 200..499 -> SourceHealth.ONLINE
                    else -> SourceHealth.UNKNOWN
                })
            }
        } catch (error: IOException) {
            if (!chain.call().isCanceled() && scope == scopes.forProvider(provider)) report(scope, SourceHealth.OFFLINE)
            throw error
        }
    }

    fun notifyBlocked() = com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
        key = "playback:unavailable", message = com.ambient.tv.R.string.browse_playback_unavailable,
        phase = com.ambient.tv.notifications.NoticePhase.ERROR, kind = com.ambient.tv.notifications.NoticeKind.PLAYBACK,
    ))
}

fun mediaProvider(id: String): String = when {
    com.ambient.tv.data.jellyfin.JellyfinBackend.owns(id) -> "jellyfin"
    com.ambient.tv.data.emby.EmbyBackend.owns(id) -> "emby"
    com.ambient.tv.data.files.FileKeys.owns(id) -> "files"
    com.ambient.tv.data.dlna.DlnaKeys.owns(id) -> "dlna"
    com.ambient.tv.data.shared.SharedMediaRepository.owns(id) -> "shared"
    else -> "plex"
}
