package com.ambient.tv.data.dlna

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ambient.tv.data.local.RegularPrefs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local choices only: DLNA itself has no portable account or watch-state contract. */
@Singleton
class DlnaStore @Inject constructor(@RegularPrefs private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val serverChanges = MutableStateFlow(0L)
    val serverRevision = serverChanges.asStateFlow()

    fun servers(): List<DlnaServer> = synchronized(lock) {
        decodeServers().mapNotNull { runCatching { it.validated() }.getOrNull() }.take(30)
    }

    fun server(id: String): DlnaServer? = servers().firstOrNull { it.id == id }

    fun save(server: DlnaServer) {
        val valid = server.validated()
        synchronized(lock) {
            val next = (servers().filterNot { it.id == valid.id || it.udn == valid.udn } + valid).take(30)
            prefs.edit { putString(KEY_SERVERS, json.encodeToString(next)) }
        }
        serversChanged()
    }

    fun replace(servers: List<DlnaServer>) {
        val valid = servers.mapNotNull { runCatching { it.validated() }.getOrNull() }.distinctBy { it.udn }.take(30)
        synchronized(lock) { prefs.edit { putString(KEY_SERVERS, json.encodeToString(valid)) } }
        serversChanged()
    }

    /** Refresh addresses/capabilities after discovery without reordering the user's sidebar. */
    fun refreshSaved(discovered: List<DlnaServer>) {
        val byUdn = discovered.mapNotNull { runCatching { it.validated() }.getOrNull() }.associateBy { it.udn }
        var changed = false
        synchronized(lock) {
            val current = servers()
            val next = current.map { saved ->
                byUdn[saved.udn]?.copy(id = saved.id, wolMac = saved.wolMac, wolAuto = saved.wolAuto)?.also { if (it != saved) changed = true } ?: saved
            }
            if (changed) prefs.edit { putString(KEY_SERVERS, json.encodeToString(next)) }
        }
        if (changed) serversChanged()
    }

    /** Stores the Wake-on-LAN settings of one saved server; blank MAC turns auto-wake off. */
    fun updateWake(id: String, mac: String, auto: Boolean): Boolean {
        val updated = synchronized(lock) {
            val current = servers()
            val target = current.firstOrNull { it.id == id } ?: return false
            val next = runCatching { target.copy(wolMac = mac.trim(), wolAuto = auto && mac.isNotBlank()).validated() }.getOrNull() ?: return false
            if (next == target) return true
            prefs.edit { putString(KEY_SERVERS, json.encodeToString(current.map { if (it.id == id) next else it })) }
            true
        }
        if (updated) serversChanged()
        return updated
    }

    fun remove(id: String) {
        synchronized(lock) {
            prefs.edit {
                putString(KEY_SERVERS, json.encodeToString(servers().filterNot { it.id == id }))
                putString(KEY_PROGRESS, json.encodeToString(progress().filterNot { it.mediaKey.startsWith("dlna:$id:") }))
            }
        }
        serversChanged()
    }

    fun progressFor(mediaKey: String): DlnaProgress? = synchronized(lock) {
        progress().firstOrNull { it.mediaKey == mediaKey }
    }

    fun recordProgress(mediaKey: String, positionMs: Long, completed: Boolean? = null) {
        if (!DlnaKeys.owns(mediaKey)) return
        synchronized(lock) {
            val current = progress()
            val old = current.firstOrNull { it.mediaKey == mediaKey }
            val next = DlnaProgress(mediaKey, positionMs.coerceAtLeast(0), completed ?: old?.completed ?: false, System.currentTimeMillis())
            prefs.edit { putString(KEY_PROGRESS, json.encodeToString((listOf(next) + current.filterNot { it.mediaKey == mediaKey }).take(500))) }
        }
    }

    fun removeProgress(mediaKey: String) {
        if (!DlnaKeys.owns(mediaKey)) return
        synchronized(lock) {
            prefs.edit { putString(KEY_PROGRESS, json.encodeToString(progress().filterNot { it.mediaKey == mediaKey })) }
        }
    }

    private fun decodeServers(): List<DlnaServer> = runCatching {
        json.decodeFromString<List<DlnaServer>>(prefs.getString(KEY_SERVERS, "[]") ?: "[]")
    }.getOrDefault(emptyList())

    private fun progress(): List<DlnaProgress> = runCatching {
        json.decodeFromString<List<DlnaProgress>>(prefs.getString(KEY_PROGRESS, "[]") ?: "[]")
    }.getOrDefault(emptyList())

    private fun serversChanged() = serverChanges.update { it + 1 }

    private companion object {
        const val KEY_SERVERS = "dlna_servers"
        const val KEY_PROGRESS = "dlna_progress"
    }
}
