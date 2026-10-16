package com.ambient.tv.ui.files

import com.ambient.tv.notifications.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.data.files.*
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FolderLocation(val sourceId: String, val path: String, val name: String, val focusId: String? = null, val visibleLimit: Int = 300)
data class FilesUiState(
    val sources: List<FileSource> = emptyList(), val entries: List<FileEntry> = emptyList(),
    val folders: List<FolderLocation> = emptyList(), val favorites: Set<String> = emptySet(),
    val history: Map<String, FileHistory> = emptyMap(), val loading: Boolean = false,
    val more: Boolean = false, val error: OperationFeedback? = null, val focusId: String? = null,
    val revision: Int = 0, val saving: Boolean = false, val editorFeedback: OperationFeedback? = null, val editorSaved: Int = 0,
    val testing: Boolean = false,
    /** The server showed an identity the editor has to confirm (unknown) or that no longer matches (changed). */
    val keyChallenge: ServerKeyChallenge? = null,
    /** Music tags of the audio files in the open folder, by entry id; filled in the background. */
    val tags: Map<String, AudioTags> = emptyMap(),
    /** cover.jpg / folder.jpg of the open folder, served through the media bridge. */
    val folderCover: String? = null,
)

data class ServerKeyChallenge(val sourceId: String, val fingerprint: String, val certificate: Boolean, val changed: Boolean)

@HiltViewModel
class FilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context, private val saved: SavedStateHandle,
    private val store: FileStore, private val access: FileAccess, private val repository: FilesRepository,
    private val server: MediaFileServer, private val wake: WakeOnLan,
    private val preferences: com.ambient.tv.data.local.AppPreferences,
    private val wireGuardRoute: com.ambient.tv.vpn.WireGuardRouteController,
    private val photoSource: com.ambient.tv.data.photos.FilesPhotoSource,
    private val photoSession: com.ambient.tv.data.photos.PhotoViewerSession,
    private val snapshots: com.ambient.tv.data.browse.DisplaySnapshots,
    private val textFiles: com.ambient.tv.data.files.TextFileImporter,
) : ViewModel() {
    val feedback = OperationFeedbackReporter()
    private fun fileError(message: Int) = OperationFeedback("files:browse", "browse", message,
        system = "files", kind = NoticeKind.LIBRARY, delivery = FeedbackDelivery.INLINE)
    /** The per-source WireGuard switch is only offered once a tunnel has been imported. */
    fun wireGuardConfigured(): Boolean = preferences.wireGuardProfile() != null
    val tab: String = saved.get<String>("tab") ?: "browse"
    private val pinnedSourceId: String? = saved.get<String>("sourceId")?.takeIf { it.isNotBlank() }
    val pinnedSource: Boolean get() = pinnedSourceId != null
    private val json = Json { ignoreUnknownKeys = true }
    private var folders = runCatching { json.decodeFromString<List<FolderLocation>>(saved.get<String>("folders") ?: "[]") }.getOrDefault(emptyList())
    private val _state = MutableStateFlow(FilesUiState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var editorJob: Job? = null
    private var sourceMutationBusy = false
    private var limit = folders.lastOrNull()?.visibleLimit?.coerceAtLeast(300) ?: 300
    init {
        refresh()
        viewModelScope.launch { store.revision.collect {
            val sourceId = folders.lastOrNull()?.sourceId
            val previous = _state.value.sources.firstOrNull { it.id == sourceId }
            updateStored()
            val current = _state.value.sources.firstOrNull { it.id == sourceId }
            if (sourceId != null && (current == null || previous?.sameLocation(current) == false)) {
                folders = emptyList(); saved["focus"] = null; rememberFolders(); refresh()
            } else if (folders.isEmpty()) {
                if (pinnedSourceId != null) refresh() else showLanding(resetLoading = false)
            }
        } }
    }
    /** Sidebar NAS shortcuts land on that share's root instead of the source list. */
    private fun restorePinnedSourceIfNeeded(): Boolean {
        val pinned = pinnedSourceId ?: return false
        val source = store.sources().firstOrNull { it.id == pinned } ?: return false
        if (folders.isNotEmpty() && folders.all { it.sourceId == pinned }) return false
        val root = runCatching { access.root(source) }.getOrElse { return false }
        folders = listOf(FolderLocation(source.id, root, source.name, "source:${source.id}"))
        limit = 300
        rememberFolders()
        return true
    }
    private fun updateStored() { _state.update { it.copy(sources = store.sources(), favorites = store.favorites().map { e -> e.id }.toSet(), history = store.history().associateBy { h -> h.entry.id }) } }
    private fun rememberFolders() { saved["folders"] = json.encodeToString(folders) }
    private fun showLanding(resetLoading: Boolean = true) {
        val entries = when (tab) {
            "favorites" -> store.favorites()
            "recent" -> store.history().map { it.entry }
            "continue" -> store.history().filter { !it.completed && it.positionMs > 0 && !it.entry.audio }.map { it.entry }
            else -> emptyList()
        }
        _state.update { it.copy(entries = entries, folders = emptyList(), loading = if (resetLoading) false else it.loading,
            error = if (resetLoading) null else it.error, more = false) }
    }
    fun scroll(key: String): Pair<Int, Int> = (saved.get<Int>("scroll-index:$key") ?: 0) to (saved.get<Int>("scroll-offset:$key") ?: 0)
    fun saveScroll(key: String, index: Int, offset: Int) { saved["scroll-index:$key"] = index; saved["scroll-offset:$key"] = offset }
    fun focus(id: String) { saved["focus"] = id; _state.update { it.copy(focusId = id) } }
    fun refresh() {
        job?.cancel(); updateStored()
        if (folders.isEmpty() && !restorePinnedSourceIfNeeded()) {
            showLanding(); _state.update { it.copy(focusId = saved["focus"], revision = it.revision + 1) }; return
        }
        val location = folders.last()
        val source = store.sources().firstOrNull { it.id == location.sourceId }
        if (source == null) {
            folders = emptyList(); rememberFolders()
            if (restorePinnedSourceIfNeeded()) { refresh(); return }
            showLanding(); return
        }
        _state.update { it.copy(loading = true, error = null, folders = folders,
            entries = it.entries.takeIf { _ -> it.folders == folders }.orEmpty(), more = false,
            tags = it.tags.takeIf { _ -> it.folders == folders }.orEmpty(), folderCover = it.folderCover.takeIf { _ -> it.folders == folders }) }
        job = viewModelScope.launch {
            val owner = "files:${source.id}"
            val namespace = snapshots.namespace(owner, json.encodeToString(source))
            val cacheKey = "folder:${location.path}:$limit"
            snapshots.read<com.ambient.tv.data.files.FileListing>(namespace, cacheKey)?.let { cached ->
                if (store.sources().any { it.id == source.id && it.sameLocation(source) }) {
                    _state.update { it.copy(entries = cached.entries, loading = false, more = cached.more) }
                }
            }
            try {
                val result = access.list(source, location.path, limit) { it.playable || it.image }
                currentCoroutineContext().ensureActive()
                if (store.sources().none { it.id == source.id && it.sameLocation(source) }) return@launch
                snapshots.write(owner, namespace, cacheKey, result)
                val old = _state.value.entries
                val focused = saved.get<String>("focus")
                val next = com.ambient.tv.data.browse.survivingFocus(old.map { it.id }, result.entries.map { it.id }, focused)
                saved["focus"] = next
                _state.update { it.copy(entries = result.entries, more = result.more, loading = false, focusId = saved["focus"], revision = it.revision + 1) }
                loadMusicFolder(source, result.entries)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                // Class and message only; paths/hosts are fine, credentials never reach exceptions.
                timber.log.Timber.w(error, "Files listing failed")
                _state.update { it.copy(loading = false, error = fileError(errorMessage(error)), revision = it.revision + 1) }
            }
        }
    }
    private var musicJob: Job? = null
    /** Tags survive folder changes and refreshes: key = entry id + size + modification time. */
    private val tagCache = object : java.util.LinkedHashMap<String, AudioTags>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AudioTags>?) = size > 5000
    }

    /**
     * Music folders: reads the tags of the audio files (four at a time) and the folder cover, then
     * shows titles/artists and puts albums into track order — also the order playback follows.
     */
    private fun loadMusicFolder(source: FileSource, entries: List<FileEntry>) {
        musicJob?.cancel()
        val audio = entries.filter { it.audio && !it.directory }.take(MAX_TAGGED_FILES)
        if (audio.isEmpty()) { _state.update { it.copy(tags = emptyMap(), folderCover = null) }; return }
        val ids = entries.map { it.id }
        musicJob = viewModelScope.launch {
            MusicFolder.cover(entries)?.let { cover ->
                val url = withContext(Dispatchers.IO) {
                    runCatching { server.register(source.id, cover.mime.ifBlank { FilePaths.mime(cover.name) }) { access.open(source, cover.path) } }.getOrNull()
                }
                _state.update { if (it.entries.map(FileEntry::id) == ids) it.copy(folderCover = url) else it }
            }
            val found = java.util.concurrent.ConcurrentHashMap<String, AudioTags>()
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            kotlinx.coroutines.coroutineScope {
                audio.map { entry ->
                    async(Dispatchers.IO) {
                        gate.acquire()
                        try {
                            val key = "${entry.id}:${entry.size}:${entry.modified}"
                            val cached = synchronized(tagCache) { tagCache[key] }
                            val tags = cached ?: runCatching { access.open(source, entry.path).use { AudioTagReader.read(it, entry.name) } }.getOrNull()
                            if (tags != null) { found[entry.id] = tags; if (cached == null) synchronized(tagCache) { tagCache[key] = tags } }
                        } finally { gate.release() }
                    }
                }.forEach { it.await() }
            }
            _state.update { state ->
                if (state.entries.map(FileEntry::id).toSet() != ids.toSet()) state
                else state.copy(tags = found.toMap(), entries = MusicFolder.sort(state.entries, found))
            }
        }
    }

    fun openSource(source: FileSource) {
        val root = runCatching { access.root(source) }.getOrElse {
            _state.update { it.copy(error = fileError(R.string.files_invalid_source)) }; return
        }
        folders = listOf(FolderLocation(source.id, root, source.name, "source:${source.id}"))
        saved["focus"] = null; limit = 300; rememberFolders(); refresh()
    }
    /** Images of the current folder, in list order, for the photo viewer. */
    private fun folderPhotos() = _state.value.entries.filter { it.image }.map(photoSource::entry)

    /** Opens the viewer on [entry] or, for [entry] = null, a slideshow of the folder. */
    fun preparePhotos(entry: FileEntry?): Boolean {
        val photos = folderPhotos()
        val location = folders.lastOrNull() ?: return false
        if (photos.isEmpty() && entry != null) return false
        entry?.let { saved["focus"] = it.id }
        photoSession.request = com.ambient.tv.data.photos.PhotoViewerSession.Request(
            title = location.name,
            entries = photos,
            startIndex = photos.indexOfFirst { it.id == entry?.id }.coerceAtLeast(0),
            startSlideshow = entry == null,
            albumKey = FileKeys.key(location.sourceId, location.path),
        )
        return true
    }

    /** After the photo viewer: refocus the image that was shown last. */
    fun applyViewerReturn() {
        val id = photoSession.lastViewedId ?: return
        photoSession.lastViewedId = null
        if (_state.value.entries.none { it.id == id }) return
        saved["focus"] = id
        _state.update { it.copy(focusId = id, revision = it.revision + 1) }
    }

    fun open(entry: FileEntry, play: (String, Boolean) -> Unit, restart: Boolean = false, openPhotos: () -> Unit = {}) {
        if (entry.image) { if (preparePhotos(entry)) openPhotos(); return }
        if (!entry.directory) { repository.setQueue(_state.value.entries); saved["focus"] = entry.id; play(entry.id, restart); return }
        if (folders.isEmpty()) {
            val source = store.sources().firstOrNull { it.id == entry.sourceId } ?: return
            // Opened from Favorites/Recent: leaving the source root returns to that entry.
            val root = runCatching { access.root(source) }.getOrElse {
                _state.update { it.copy(error = fileError(R.string.files_invalid_source)) }; return
            }
            folders = listOf(FolderLocation(source.id, root, source.name, entry.id))
        }
        if (folders.last().path != entry.path) {
            folders = folders.dropLast(1) + folders.last().copy(visibleLimit = limit) + FolderLocation(entry.sourceId, entry.path, entry.name, entry.id)
        }
        saved["focus"] = null; limit = 300; rememberFolders(); refresh()
    }
    fun up(): Boolean {
        if (folders.isEmpty()) return false
        if (pinnedSourceId != null && folders.size == 1) return false
        saved["focus"] = folders.last().focusId
        folders = folders.dropLast(1); limit = folders.lastOrNull()?.visibleLimit?.coerceAtLeast(300) ?: 300
        rememberFolders(); refresh(); return true
    }
    fun breadcrumb(index: Int) {
        // Jumping up several levels focuses the folder of the path that was left, like repeated Back.
        val left = folders.getOrNull(if (index < 0 && pinnedSourceId != null) 1 else index + 1)
        folders = when {
            index < 0 && pinnedSourceId != null -> folders.take(1)
            index < 0 -> emptyList()
            else -> folders.take(index + 1)
        }
        saved["focus"] = left?.focusId
        limit = folders.lastOrNull()?.visibleLimit?.coerceAtLeast(300) ?: 300; rememberFolders(); refresh()
    }
    fun more() {
        limit += 300
        if (folders.isNotEmpty()) { folders = folders.dropLast(1) + folders.last().copy(visibleLimit = limit); rememberFolders() }
        refresh()
    }
    fun favorite(entry: FileEntry) { viewModelScope.launch(Dispatchers.IO) { store.favorite(entry) } }
    fun remove(source: FileSource) {
        if (sourceMutationBusy) return
        sourceMutationBusy = true
        val ticket = feedback.begin("files:remove:${source.id}")
        if (BackgroundPlaybackSession.snapshot.value.metadata?.mediaId?.let { runCatching { FileKeys.parse(it).first }.getOrNull() } == source.id) BackgroundPlaybackSession.stop()
        viewModelScope.launch {
          try {
            val result = com.ambient.tv.util.suspendResult {
            withContext(Dispatchers.IO) {
                server.revoke(source.id); store.removeSource(source.id)
                if (source.kind == "local" && store.sources().none { it.treeUri == source.treeUri }) runCatching {
                    context.contentResolver.releasePersistableUriPermission(Uri.parse(source.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            }
            val message = feedback.complete(ticket, OperationFeedback(ticket.key, "browse",
                if (result.isSuccess) R.string.feedback_source_removed_success else R.string.notice_failed,
                if (result.isSuccess) FeedbackPhase.SUCCESS else FeedbackPhase.ERROR,
                detail = result.exceptionOrNull()?.let(::errorMessage), system = "files", subject = source.name,
                delivery = FeedbackDelivery.ACTION))
            if (result.isSuccess) {
                folders = emptyList(); saved["focus"] = null; rememberFolders(); refresh()
                if (source.wireGuard) wireGuardRoute.reconcile()
            } else _state.update { it.copy(error = message) }
          } finally { sourceMutationBusy = false }
        }
    }
    fun addLocal(uri: Uri, replaceId: String? = null) {
        if (sourceMutationBusy) return
        sourceMutationBusy = true
        val ticket = feedback.begin("files:local:${replaceId ?: "new"}")
        job?.cancel()
        job = viewModelScope.launch {
            val previous = store.sources().firstOrNull { it.id == replaceId }
            var grantAlreadyHeld = true
            var committed = false
            try {
                withContext(Dispatchers.IO) {
                    grantAlreadyHeld = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val source = FileSource(id = replaceId ?: java.util.UUID.randomUUID().toString(), name = context.getString(R.string.files_local_folder), kind = "local", treeUri = uri.toString())
                    val entry = access.stat(source, access.root(source))
                    require(entry.directory)
                    server.revoke(source.id)
                    if (previous != null && previous.treeUri != source.treeUri) store.removeSource(previous.id)
                    store.saveSource(source.copy(name = entry.name.ifBlank { source.name }), null)
                    committed = true
                    if (previous?.kind == "local" && previous.treeUri != source.treeUri && store.sources().none { it.treeUri == previous.treeUri }) runCatching {
                        context.contentResolver.releasePersistableUriPermission(Uri.parse(previous.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                if (replaceId != null && BackgroundPlaybackSession.snapshot.value.metadata?.mediaId?.let { runCatching { FileKeys.parse(it).first }.getOrNull() } == replaceId) BackgroundPlaybackSession.stop()
                if (previous != null && previous.treeUri != uri.toString()) { folders = emptyList(); saved["focus"] = null; rememberFolders() }
                feedback.complete(ticket, OperationFeedback(ticket.key, "browse",
                    if (replaceId == null) R.string.feedback_folder_added else R.string.feedback_folder_updated,
                    FeedbackPhase.SUCCESS, system = "files", delivery = FeedbackDelivery.ACTION))
                refresh()
            } catch (error: Exception) {
                if (!committed && !grantAlreadyHeld) withContext(NonCancellable + Dispatchers.IO) {
                    if (store.sources().none { it.treeUri == uri.toString() }) runCatching {
                        context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                if (error is CancellationException) throw error
                val message = feedback.complete(ticket, OperationFeedback(ticket.key, "browse", errorMessage(error),
                    system = "files", delivery = FeedbackDelivery.ACTION))
                _state.update { it.copy(error = message) }
            } finally { sourceMutationBusy = false }
        }
    }
    fun pickerUnavailable() { _state.update { it.copy(error = fileError(R.string.files_picker_unavailable)) } }
    fun resetEditor() { editorJob?.cancel(); feedback.cancel("editor"); _state.update { it.copy(saving = false, testing = false, editorFeedback = null, keyChallenge = null) } }
    /** The stored password only still belongs to a source whose server and account are unchanged. */
    private fun sameAccount(a: FileSource, b: FileSource) = a.host == b.host && a.port == b.port && a.username == b.username && a.domain == b.domain
    /** Checks connection and read access with the editor's current values; nothing is stored. */
    /** [sshKey]: null keeps the stored key (same account only), "" tests without one. */
    fun testNetwork(source: FileSource, password: String, keepPassword: Boolean, sshKey: String? = null) {
        editorJob?.cancel()
        val ticket = feedback.begin("editor")
        editorJob = viewModelScope.launch {
            _state.update { it.copy(saving = false, testing = true, editorFeedback = null, keyChallenge = null) }
            try {
                val valid = source.validated()
                access.prepare(valid)
                val original = store.sources().firstOrNull { it.id == valid.id }
                val sameAccount = original != null && sameAccount(original, valid)
                val testPassword = if (keepPassword && sameAccount) store.password(original) else password
                val testKey = sshKey ?: if (sameAccount) store.sshKey(original) else ""
                when (valid.kind) {
                    FileKinds.SMB -> access.testSmb(valid, testPassword)
                    FileKinds.WEBDAV -> access.testWebDav(valid, testPassword)
                    FileKinds.SFTP -> access.testSftp(valid, testPassword, testKey)
                    else -> access.testNfs(valid)
                }
                currentCoroutineContext().ensureActive()
                val result = feedback.complete(ticket, OperationFeedback("files:test:${source.id}", "editor:${source.id}",
                    R.string.files_test_ok, FeedbackPhase.SUCCESS, system = "files", subject = source.name,
                    delivery = FeedbackDelivery.NOTIFICATION)) ?: return@launch
                _state.update { it.copy(testing = false, editorFeedback = result) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val challenge = serverKeyChallenge(source.id, error)
                val result = feedback.complete(ticket, editorFailure(source, "test", error).copy(delivery = FeedbackDelivery.NOTIFICATION)) ?: return@launch
                _state.update { it.copy(testing = false, editorFeedback = result, keyChallenge = challenge) }
            }
        }
    }
    /** Stores the source as entered without contacting it, so an offline or sleeping NAS can be saved too. */
    fun saveNetwork(source: FileSource, password: String, keepPassword: Boolean, sshKey: String? = null) {
        editorJob?.cancel()
        val ticket = feedback.begin("editor")
        editorJob = viewModelScope.launch {
            _state.update { it.copy(saving = true, testing = false, editorFeedback = null) }
            try {
                val valid = source.validated()
                val original = store.sources().firstOrNull { it.id == valid.id }
                val keepStored = keepPassword && original != null && sameAccount(original, valid)
                if (BackgroundPlaybackSession.snapshot.value.metadata?.mediaId?.let { runCatching { FileKeys.parse(it).first }.getOrNull() } == valid.id) BackgroundPlaybackSession.stop()
                // A key from another account must not follow a changed host/user.
                val storedKey = if (sshKey == null && original != null && !sameAccount(original, valid)) "" else sshKey
                withContext(Dispatchers.IO) { server.revoke(valid.id); store.saveSource(valid, if (keepStored) null else password, storedKey) }
                currentCoroutineContext().ensureActive()
                feedback.complete(ticket, OperationFeedback("files:save:${source.id}", "editor:${source.id}",
                    R.string.notice_source_saved, FeedbackPhase.SUCCESS, system = "files", subject = source.name,
                    delivery = FeedbackDelivery.ACTION)) ?: return@launch
                _state.update { it.copy(saving = false, editorFeedback = null, editorSaved = it.editorSaved + 1) }
                refresh()
                // Drop the route of a source that no longer uses the tunnel (or stop it if unused).
                if (valid.wireGuard || original?.wireGuard == true) wireGuardRoute.reconcile()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val result = feedback.complete(ticket, editorFailure(source, "save", error)) ?: return@launch
                _state.update { it.copy(saving = false, editorFeedback = result) }
            }
        }
    }
    private fun serverKeyChallenge(sourceId: String, error: Throwable): ServerKeyChallenge? {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        chain.filterIsInstance<UnverifiedServerKeyException>().firstOrNull()?.let { return ServerKeyChallenge(sourceId, it.fingerprint, it.certificate, changed = false) }
        chain.filterIsInstance<ServerKeyChangedException>().firstOrNull()?.let { return ServerKeyChallenge(sourceId, it.fingerprint, it.certificate, changed = true) }
        return null
    }

    /** SSH private keys load the same ways as WireGuard configs: device/USB, Files sources, document picker. */
    internal fun sshKeyImportSource(editingId: String, onKey: (String?) -> Unit) = com.ambient.tv.ui.settings.TextFileImportSource(
        sources = textFiles.sources(excludeId = editingId),
        deviceRoots = textFiles.deviceRoots(),
        hasDeviceAccess = textFiles::hasDeviceAccess,
        root = textFiles::root,
        listDevice = { path -> textFiles.listDevice(path, ::isSshKeyFile, includeHidden = true) },
        listRemote = { source, path -> textFiles.listRemote(source, path, ::isSshKeyFile) },
        pickDevice = { path -> loadSshKey(onKey) { textFiles.readDevice(path, SSH_KEY_LIMIT) } },
        pickRemote = { source, path -> loadSshKey(onKey) { textFiles.readRemote(source, path, SSH_KEY_LIMIT) } },
    )

    fun hasSshKey(source: FileSource): Boolean = source.kind == FileKinds.SFTP && runCatching { store.sshKey(source).isNotBlank() }.getOrDefault(false)

    fun loadSshKeyUri(uri: android.net.Uri, onKey: (String?) -> Unit) = loadSshKey(onKey) { textFiles.readUri(uri, SSH_KEY_LIMIT) }

    private fun loadSshKey(onKey: (String?) -> Unit, read: suspend () -> String) {
        viewModelScope.launch {
            val key = com.ambient.tv.util.suspendResult { read().trim() }.getOrNull()?.takeIf(::looksLikePrivateKey)
            onKey(key)
        }
    }

    private fun editorFailure(source: FileSource, operation: String, error: Throwable): OperationFeedback {
        val message = errorMessage(error)
        return OperationFeedback("files:$operation:${source.id}", "editor:${source.id}", message,
            system = "files", subject = source.name, delivery = if (message == R.string.files_invalid_source)
                FeedbackDelivery.INLINE else FeedbackDelivery.CONTEXTUAL)
    }
    /**
     * Manual wake press. While the source's machine is already waking (this press or any other
     * system), a repeat press is ignored: the running wake, its loading state and the shared
     * wake card stay as they are instead of being cancelled and restarted.
     */
    fun wake(source: FileSource) {
        if (wake.isMachineWaking(source)) return
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                access.prepare(source.copy(wolAuto = false))
                if (wake.wakeAndWait(source, manual = true)) openSource(source)
                // Another caller started waking this machine in between: its card covers it.
                else _state.update { it.copy(loading = false) }
            }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(loading = false, error = fileError(if (error is WireGuardUnavailableException) R.string.files_wireguard_error else R.string.files_wake_timeout)) }
            }
        }
    }
    fun cancelLoading() { job?.cancel(); _state.update { it.copy(loading = false, error = null, revision = it.revision + 1) } }
    companion object {
        /** Larger folders are not album folders; their files keep their names. */
        private const val MAX_TAGGED_FILES = 300
        fun errorMessage(error: Throwable): Int {
            val chain = generateSequence(error) { it.cause }.take(8).toList()
            return when {
                chain.any { it is WireGuardUnavailableException } -> R.string.files_wireguard_error
                chain.any { it is UnverifiedServerKeyException } -> R.string.files_server_key_unconfirmed
                chain.any { it is ServerKeyChangedException } -> R.string.files_server_key_changed
                chain.any { it is IllegalArgumentException } -> R.string.files_invalid_source
                chain.any { it is SecurityException || it.message.orEmpty().contains("STATUS_LOGON_FAILURE") || it.message.orEmpty().contains("STATUS_ACCESS_DENIED") } -> R.string.files_permission_error
                else -> R.string.files_connection_error
            }
        }
    }
}
