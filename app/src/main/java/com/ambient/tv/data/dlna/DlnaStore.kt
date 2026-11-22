package com.ambient.tv.data.dlna

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ambient.tv.data.local.LocalPlaybackDatabase
import com.ambient.tv.data.local.LocalPlaybackRow
import com.ambient.tv.data.local.LocalPlaybackRows
import com.ambient.tv.data.local.UnreadablePlaybackLegacy
import com.ambient.tv.data.local.RegularPrefs
import java.util.UUID
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
class DlnaStore @Inject constructor(@RegularPrefs private val prefs: SharedPreferences, private val database: LocalPlaybackDatabase) {
    constructor(prefs: SharedPreferences) : this(prefs, LocalPlaybackDatabase.memory())
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val serverChanges = MutableStateFlow(0L)
    val serverRevision = serverChanges.asStateFlow()
    private var cachedServers: Pair<String, List<DlnaServer>>? = null
    private var cachedScopes: Pair<String, Map<String, String>>? = null
    private var retainedScopes: Map<String, String> = emptyMap()
    private val progressRows: LocalPlaybackRows<DlnaProgress> = LocalPlaybackRows(database, "dlna_progress", 500,
        legacy = ::legacyProgress, cleanup = { synchronized(lock) {
            if (prefs.contains(KEY_PROGRESS) || prefs.contains(KEY_LEGACY_SCOPES)) prefs.edit { remove(KEY_PROGRESS); remove(KEY_LEGACY_SCOPES) }
        } },
        decode = { runCatching { json.decodeFromString<DlnaProgress>(it) }.getOrNull() },
        encode = { json.encodeToString(it) }, changed = {}, primed = { reconcileScopes(force = true) })

    init {
        synchronized(lock) {
            val old = scopes()
            val next = servers().associate { it.id to (old[it.id]?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()) }
            if (old != next) prefs.edit { putString(KEY_SCOPES, json.encodeToString(next)) }
        }
        progressRows.start()
    }

    suspend fun awaitReady() = progressRows.awaitReady()
    suspend fun flush(): Boolean = database.flush()

    fun servers(): List<DlnaServer> = synchronized(lock) {
        val raw = prefs.getString(KEY_SERVERS, "[]") ?: "[]"
        cachedServers?.takeIf { it.first == raw }?.second ?: runCatching { json.decodeFromString<List<DlnaServer>>(raw) }
            .getOrDefault(emptyList()).mapNotNull { runCatching { it.validated() }.getOrNull() }.distinctBy { it.id }.take(30)
            .also { cachedServers = raw to it }
    }

    fun server(id: String): DlnaServer? = servers().firstOrNull { it.id == id }
    internal fun playbackScope(server: DlnaServer): String? = synchronized(lock) {
        if (servers().none { it.id == server.id && it.udn == server.udn }) null else scopes()[server.id]
    }
    internal fun <T> withPlaybackScope(server: DlnaServer, scope: String, action: () -> T): T = synchronized(lock) {
        check(playbackScope(server) == scope) { "DLNA server configuration changed" }
        action()
    }

    fun save(server: DlnaServer) {
        val valid = server.validated()
        synchronized(lock) {
            val next = (servers().filterNot { it.id == valid.id || it.udn == valid.udn } + valid).take(30)
            writeServers(next)
        }
        serversChanged()
    }

    fun replace(servers: List<DlnaServer>) {
        val valid = servers.mapNotNull { runCatching { it.validated() }.getOrNull() }.distinctBy { it.udn }.distinctBy { it.id }.take(30)
        synchronized(lock) { writeServers(valid) }
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
            if (changed) writeServers(next)
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
            writeServers(current.map { if (it.id == id) next else it })
            true
        }
        if (updated) serversChanged()
        return updated
    }

    fun remove(id: String) {
        synchronized(lock) {
            writeServers(servers().filterNot { it.id == id })
        }
        serversChanged()
    }

    fun progressFor(mediaKey: String): DlnaProgress? = synchronized(lock) {
        reconcileScopes()
        progressRows.get(mediaKey)
    }

    fun recordProgress(mediaKey: String, positionMs: Long, completed: Boolean? = null, expectedScope: String? = null) {
        val sourceId = runCatching { DlnaKeys.parse(mediaKey).first }.getOrNull() ?: return
        synchronized(lock) {
            reconcileScopes()
            val scope = scopes()[sourceId] ?: return
            if (expectedScope != null && expectedScope != scope) return
            val now = System.currentTimeMillis()
            val fields = if (completed == null) setOf("position", "updatedAt", "order") else setOf("*")
            progressRows.update(mediaKey, sourceId, scope, now, immediate = completed != null, pendingFields = fields) { old ->
                DlnaProgress(mediaKey, positionMs.coerceAtLeast(0), completed ?: old?.completed ?: false, now)
            }
        }
    }

    fun removeProgress(mediaKey: String) {
        val sourceId = runCatching { DlnaKeys.parse(mediaKey).first }.getOrNull() ?: return
        synchronized(lock) {
            reconcileScopes()
            val scope = scopes()[sourceId] ?: return
            progressRows.update(mediaKey, sourceId, scope, 0, immediate = true) { null }
        }
    }

    private fun scopes(): Map<String, String> {
        val raw = prefs.getString(KEY_SCOPES, "{}") ?: "{}"
        return cachedScopes?.takeIf { it.first == raw }?.second ?: runCatching { json.decodeFromString<Map<String, String>>(raw) }
            .getOrDefault(emptyMap()).also { cachedScopes = raw to it }
    }

    private fun writeServers(next: List<DlnaServer>) {
        val previous = servers().associateBy { it.id }
        val oldScopes = scopes()
        val reusable = next.filter { previous[it.id]?.udn == it.udn }.map { it.id }.toSet()
        val nextScopes = next.associate { it.id to (oldScopes[it.id]?.takeIf { _ -> it.id in reusable } ?: UUID.randomUUID().toString()) }
        prefs.edit {
            putString(KEY_SERVERS, json.encodeToString(next))
            putString(KEY_SCOPES, json.encodeToString(nextScopes))
            // Before migration, fence the legacy snapshot too. Never recreate its key later.
            if (prefs.contains(KEY_PROGRESS)) {
                val retained = runCatching { json.decodeFromString<List<DlnaProgress>>(prefs.getString(KEY_PROGRESS, "[]") ?: "[]") }.getOrNull()
                    ?.filter { runCatching { DlnaKeys.parse(it.mediaKey).first in reusable }.getOrDefault(false) }
                if (retained != null) putString(KEY_PROGRESS, json.encodeToString(retained))
                else if (!prefs.contains(KEY_LEGACY_SCOPES)) putString(KEY_LEGACY_SCOPES, json.encodeToString(oldScopes))
            }
        }
        reconcileScopes()
    }

    private fun reconcileScopes(force: Boolean = false): Unit = synchronized(lock) {
        val current = scopes()
        if (force || retainedScopes != current) {
            retainedScopes = current
            progressRows.retainScopes(current)
        }
    }

    private fun legacyProgress(): List<LocalPlaybackRow> {
        val (raw, original, scopes) = synchronized(lock) {
            val current = scopes()
            val original = prefs.getString(KEY_LEGACY_SCOPES, null)?.let {
                runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap())
            } ?: current
            Triple(prefs.getString(KEY_PROGRESS, "[]") ?: "[]", original, original.filter { (id, scope) -> current[id] == scope })
        }
        val entries = try { json.decodeFromString<List<DlnaProgress>>(raw) } catch (error: Exception) {
            throw UnreadablePlaybackLegacy(raw, json.encodeToString(original), error)
        }
        return entries.take(500).mapIndexedNotNull { index, progress ->
            val id = runCatching { DlnaKeys.parse(progress.mediaKey).first }.getOrNull() ?: return@mapIndexedNotNull null
            val scope = scopes[id] ?: return@mapIndexedNotNull null
            LocalPlaybackRow(progress.mediaKey, id, scope, 500L - index, json.encodeToString(progress))
        }
    }
    private fun serversChanged() = serverChanges.update { it + 1 }

    private companion object {
        const val KEY_SERVERS = "dlna_servers"
        const val KEY_PROGRESS = "dlna_progress"
        const val KEY_SCOPES = "dlna_progress_scopes"
        const val KEY_LEGACY_SCOPES = "dlna_progress_legacy_scopes"
    }
}
