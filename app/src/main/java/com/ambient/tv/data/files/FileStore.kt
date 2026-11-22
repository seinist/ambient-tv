package com.ambient.tv.data.files

import android.content.SharedPreferences
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.LocalPlaybackDatabase
import com.ambient.tv.data.local.LocalPlaybackRow
import com.ambient.tv.data.local.LocalPlaybackRows
import com.ambient.tv.data.local.UnreadablePlaybackLegacy
import com.ambient.tv.data.local.RegularPrefs
import com.ambient.tv.data.local.TokenStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class FileStore @Inject constructor(
    private val preferences: AppPreferences, private val tokens: TokenStore,
    @RegularPrefs private val prefs: SharedPreferences,
    private val database: LocalPlaybackDatabase,
) {
    /** Domain tests retain an immediate memory backend; production always injects the IO writer. */
    constructor(preferences: AppPreferences, tokens: TokenStore, prefs: SharedPreferences) :
        this(preferences, tokens, prefs, LocalPlaybackDatabase.memory())

    private val json = Json { ignoreUnknownKeys = true }
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private var retainedScopes: Map<String, String> = emptyMap()
    private val favoriteRows = LocalPlaybackRows(database, "file_favorites", 300, ascending = true,
        legacy = { legacyFavorites() }, cleanup = { removeLegacy("file_favorites") },
        decode = { runCatching { json.decodeFromString<FileEntry>(it) }.getOrNull() },
        encode = { json.encodeToString(it) }, changed = ::changed, primed = ::rowsPrimed)
    private val historyRows = LocalPlaybackRows(database, "file_history", 500,
        legacy = { legacyHistory() }, cleanup = { removeLegacy("file_history") },
        decode = { runCatching { json.decodeFromString<FileHistory>(it) }.getOrNull() },
        encode = { json.encodeToString(it) }, changed = ::changed, primed = ::rowsPrimed)
    // Avoid decoding the full ROM-folder JSON for every Files/Auto/photo listing entry.
    @Volatile private var visibilityFolders = preferences.gameLibraryFolders
    // Imports also write AppPreferences directly. Keep every retained browser up to date.
    private val sourceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "game_library_folders") visibilityFolders = preferences.gameLibraryFolders
        if (key == "file_sources" || key == "file_playback_scopes") reconcileSources()
        if (key == "file_sources" || key == "game_library_folders") changed()
    }
    init {
        preferences.ensureFilePlaybackScopes()
        prefs.registerOnSharedPreferenceChangeListener(sourceListener)
        favoriteRows.start()
        historyRows.start()
    }

    /** Playback waits for local resume data without blocking its caller or waking a source. */
    suspend fun awaitReady() { favoriteRows.awaitReady(); historyRows.awaitReady() }
    suspend fun flush(): Boolean = database.flush()
    /** All configured sources, including Games-only sources (required for playback and WOL). */
    fun sources() = preferences.fileSources

    /** Only sources that actually belong in the normal Files browser and sidebar. */
    fun fileBrowserSources(): List<FileSource> = sources().filter { source ->
        filePathVisibleInBrowser(source.id, sourceRoot(source), source.showInFiles, visibilityFolders)
    }

    fun visibleInFiles(entry: FileEntry): Boolean = visibleInFiles(entry.sourceId, entry.path)

    fun visibleInFiles(sourceId: String, path: String): Boolean {
        val source = sources().firstOrNull { it.id == sourceId } ?: return false
        return filePathVisibleInBrowser(sourceId, path, source.showInFiles, visibilityFolders)
    }

    private fun sourceRoot(source: FileSource): String = if (source.kind == "local") {
        runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(source.treeUri))
        }.getOrDefault("")
    } else ""

    /** Credentials and source identity must be read atomically with edits and imports. */
    internal fun <T> withCurrentSource(source: FileSource, action: () -> T): T = synchronized(preferences.fileSourceLock) {
        if (sources().none { it == source }) throw java.io.IOException("Source configuration changed")
        action()
    }
    internal fun playbackScope(source: FileSource): String? = synchronized(preferences.fileSourceLock) {
        if (sources().none { it.id == source.id && it.sameLocation(source) }) null else preferences.filePlaybackScopes[source.id]
    }
    fun password(source: FileSource): String = withCurrentSource(source) { tokens.smbPasswordForSource(source.id).orEmpty() }
    fun sshKey(source: FileSource): String = withCurrentSource(source) { tokens.sshKeyForSource(source.id).orEmpty() }
    /** [password]/[sshKey] null keep the stored secret; an SSH key "" removes it. */
    fun saveSource(source: FileSource, password: String?, sshKey: String? = null) = synchronized(preferences.fileSourceLock) {
        val valid = source.validated()
        val all = sources()
        val existing = all.filterNot { it.id == valid.id }
        val added = existing.size == all.size
        require(existing.size < 50) { "Too many file sources" }
        if (!valid.usesPassword) tokens.setSmbPassword(valid.id, null)
        else if (password != null) tokens.setSmbPassword(valid.id, password)
        if (valid.kind != FileKinds.SFTP) tokens.setSshKey(valid.id, null)
        else if (sshKey != null) tokens.setSshKey(valid.id, sshKey)
        preferences.fileSources = existing + valid
        // Adding a source turns Files on; editing one (e.g. its wake settings) leaves the switch alone.
        if (valid.showInFiles && (added || all.firstOrNull { it.id == valid.id }?.showInFiles == false))
            preferences.filesEnabled = true
        changed()
    }
    /**
     * Wake-on-LAN fields only, for the grouped Wake page: unlike [saveSource] this keeps secrets and
     * does not switch the Files system on. An invalid MAC is rejected (returns false).
     */
    fun updateWake(id: String, mac: String, auto: Boolean): Boolean = synchronized(preferences.fileSourceLock) {
        val current = sources()
        val target = current.firstOrNull { it.id == id } ?: return@synchronized false
        val next = runCatching { target.copy(wolMac = mac.trim(), wolAuto = auto && mac.isNotBlank()).validated() }.getOrNull()
            ?: return@synchronized false
        if (next != target) { preferences.fileSources = current.map { if (it.id == id) next else it }; changed() }
        true
    }
    fun removeSource(id: String) = synchronized(preferences.fileSourceLock) {
        preferences.fileSources = sources().filterNot { it.id == id }
        preferences.gameLibraryFolders = preferences.gameLibraryFolders.filterNot { it.sourceId == id }
        tokens.setSmbPassword(id, null)
        tokens.setSshKey(id, null)
        reconcileSources()
        changed()
    }
    fun favorites(): List<FileEntry> {
        reconcileSources()
        return favoriteRows.all()
    }
    fun history(): List<FileHistory> {
        reconcileSources()
        return historyRows.all()
    }
    fun historyFor(id: String): FileHistory? { reconcileSources(); return historyRows.get(id) }
    fun favorite(entry: FileEntry) = synchronized(preferences.fileSourceLock) {
        if (sources().none { it.id == entry.sourceId }) return@synchronized
        reconcileSources()
        val scope = preferences.filePlaybackScopes[entry.sourceId] ?: return@synchronized
        favoriteRows.update(entry.id, entry.sourceId, scope, System.currentTimeMillis(), immediate = true, pendingFields = emptySet()) {
            if (it == null) entry else null
        }
    }
    fun record(entry: FileEntry, position: Long? = null, duration: Long? = null, completed: Boolean? = null,
               expectedSource: FileSource? = null, expectedScope: String? = null) = synchronized(preferences.fileSourceLock) {
        val source = sources().firstOrNull { it.id == entry.sourceId } ?: return@synchronized
        if (expectedSource != null && !source.sameLocation(expectedSource)) return@synchronized
        reconcileSources()
        val scope = preferences.filePlaybackScopes[entry.sourceId] ?: return@synchronized
        if (expectedScope != null && expectedScope != scope) return@synchronized
        val now = System.currentTimeMillis()
        val fields = buildSet {
            addAll(listOf("entry", "playedAt", "order"))
            if (position != null) add("position")
            if (duration != null && duration > 0) add("duration")
            if (completed != null) add("completed")
        }
        historyRows.update(entry.id, entry.sourceId, scope, now, immediate = completed == true, pendingFields = fields) { previous ->
            FileHistory(entry, position?.coerceAtLeast(0) ?: previous?.positionMs ?: 0,
                duration?.takeIf { it > 0 } ?: previous?.durationMs ?: 0, now, completed ?: previous?.completed ?: false)
        }
    }
    fun clearProgress(id: String) = synchronized(preferences.fileSourceLock) {
        reconcileSources()
        val sourceId = runCatching { FileKeys.parse(id).first }.getOrNull() ?: return@synchronized
        val scope = preferences.filePlaybackScopes[sourceId] ?: return@synchronized
        historyRows.update(id, sourceId, scope, 0, immediate = true, keepOrder = true,
            pendingFields = setOf("position", "completed")) { it?.copy(positionMs = 0, completed = true) }
    }

    private fun rowsPrimed() { reconcileSources(force = true); changed() }
    private fun reconcileSources(force: Boolean = false): Unit = synchronized(preferences.fileSourceLock) {
        val scopes = preferences.filePlaybackScopes
        if (force || scopes != retainedScopes) {
            retainedScopes = scopes
            favoriteRows.retainScopes(scopes)
            historyRows.retainScopes(scopes)
        }
    }
    private fun removeLegacy(key: String) = synchronized(preferences.fileSourceLock) {
        if (prefs.contains(key) || prefs.contains("${key}_legacy_scopes"))
            prefs.edit().remove(key).remove("${key}_legacy_scopes").apply()
    }
    private data class LegacySnapshot(val raw: String, val originalScopes: Map<String, String>, val eligibleScopes: Map<String, String>)
    private fun legacySnapshot(key: String): LegacySnapshot = synchronized(preferences.fileSourceLock) {
        val current = preferences.filePlaybackScopes
        val original = prefs.getString("${key}_legacy_scopes", null)?.let { raw ->
            runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
        } ?: current
        LegacySnapshot(prefs.getString(key, "[]") ?: "[]", original, original.filter { (id, scope) -> current[id] == scope })
    }
    private fun legacyFavorites(): List<LocalPlaybackRow> {
        val snapshot = legacySnapshot("file_favorites")
        val scopes = snapshot.eligibleScopes
        val entries = try { json.decodeFromString<List<FileEntry>>(snapshot.raw) } catch (error: Exception) {
            throw UnreadablePlaybackLegacy(snapshot.raw, json.encodeToString(snapshot.originalScopes), error)
        }
        return entries
            .filter { it.sourceId in scopes }.takeLast(300).mapIndexed { index, entry ->
                LocalPlaybackRow(entry.id, entry.sourceId, scopes.getValue(entry.sourceId), index.toLong(), json.encodeToString(entry))
            }
    }
    private fun legacyHistory(): List<LocalPlaybackRow> {
        val snapshot = legacySnapshot("file_history")
        val scopes = snapshot.eligibleScopes
        val entries = try { json.decodeFromString<List<FileHistory>>(snapshot.raw) } catch (error: Exception) {
            throw UnreadablePlaybackLegacy(snapshot.raw, json.encodeToString(snapshot.originalScopes), error)
        }
        return entries
            .filter { it.entry.sourceId in scopes }.take(500).mapIndexed { index, record ->
                LocalPlaybackRow(record.entry.id, record.entry.sourceId, scopes.getValue(record.entry.sourceId),
                    500L - index, json.encodeToString(record))
            }
    }
    private fun changed() { changes.update { it + 1 } }
}
