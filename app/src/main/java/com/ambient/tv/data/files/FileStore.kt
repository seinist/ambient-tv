package com.ambient.tv.data.files

import android.content.SharedPreferences
import com.ambient.tv.data.local.AppPreferences
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
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    // Imports also write AppPreferences directly. Keep every retained browser up to date.
    private val sourceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "file_sources") changed()
    }
    init { prefs.registerOnSharedPreferenceChangeListener(sourceListener) }
    fun sources() = preferences.fileSources
    /** Credentials and source identity must be read atomically with edits and imports. */
    internal fun <T> withCurrentSource(source: FileSource, action: () -> T): T = synchronized(preferences.fileSourceLock) {
        if (sources().none { it == source }) throw java.io.IOException("Source configuration changed")
        action()
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
        if (added) preferences.filesEnabled = true
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
        tokens.setSmbPassword(id, null)
        tokens.setSshKey(id, null)
        saveFavorites(favorites().filterNot { it.sourceId == id })
        saveHistory(history().filterNot { it.entry.sourceId == id })
        changed()
    }
    fun favorites(): List<FileEntry> {
        val sourceIds = sources().map { it.id }.toSet()
        return decode(prefs.getString("file_favorites", null)).filter { it.sourceId in sourceIds }.takeLast(300)
    }
    private fun decode(value: String?): List<FileEntry> = runCatching { json.decodeFromString<List<FileEntry>>(value ?: "[]") }.getOrDefault(emptyList())
    fun history(): List<FileHistory> {
        val sourceIds = sources().map { it.id }.toSet()
        return runCatching { json.decodeFromString<List<FileHistory>>(prefs.getString("file_history", "[]")!!) }
            .getOrDefault(emptyList()).filter { it.entry.sourceId in sourceIds }.take(500)
    }
    fun favorite(entry: FileEntry) = synchronized(preferences.fileSourceLock) {
        if (sources().none { it.id == entry.sourceId }) return@synchronized
        val old = favorites()
        saveFavorites(if (old.any { it.id == entry.id }) old.filterNot { it.id == entry.id } else (old + entry).takeLast(300))
        changed()
    }
    fun record(entry: FileEntry, position: Long? = null, duration: Long? = null, completed: Boolean? = null, expectedSource: FileSource? = null) = synchronized(preferences.fileSourceLock) {
        val source = sources().firstOrNull { it.id == entry.sourceId } ?: return@synchronized
        if (expectedSource != null && !source.sameLocation(expectedSource)) return@synchronized
        val old = history(); val previous = old.firstOrNull { it.entry.id == entry.id }
        val record = FileHistory(entry, position?.coerceAtLeast(0) ?: previous?.positionMs ?: 0,
            duration?.takeIf { it > 0 } ?: previous?.durationMs ?: 0, System.currentTimeMillis(), completed ?: previous?.completed ?: false)
        saveHistory((listOf(record) + old.filterNot { it.entry.id == entry.id }).take(500))
        changed()
    }
    fun clearProgress(id: String) = synchronized(preferences.fileSourceLock) { saveHistory(history().map { if (it.entry.id == id) it.copy(positionMs = 0, completed = true) else it }); changed() }
    private fun saveFavorites(items: List<FileEntry>) { prefs.edit().putString("file_favorites", json.encodeToString(items)).apply() }
    private fun saveHistory(items: List<FileHistory>) { prefs.edit().putString("file_history", json.encodeToString(items)).apply() }
    private fun changed() { changes.update { it + 1 } }
}
