package com.ambient.tv.ui.spotify

import com.ambient.tv.util.suspendResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.spotify.SpotifyBrowseItem
import com.ambient.tv.spotify.SpotifyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpotifyHomeUiState(
    val loading: Boolean = true,
    /** Includes a refresh while cached/current items remain on screen. */
    val refreshing: Boolean = false,
    val connected: Boolean = false,
    val webAuthorized: Boolean = false,
    val authBusy: Boolean = false,
    val accountName: String? = null,
    val nativeAvailable: Boolean = true,
    val query: String = "",
    val sectionKey: String = "home",
    val items: List<SpotifyBrowseItem> = emptyList(),
    val error: String? = null,
    val errorRes: Int? = null,
)

@HiltViewModel
class SpotifyHomeViewModel @Inject constructor(
    private val repo: SpotifyRepository,
    private val preferences: AppPreferences,
    private val snapshots: com.ambient.tv.data.browse.DisplaySnapshots,
    private val scopes: com.ambient.tv.data.browse.BrowseScopes,
    val availability: com.ambient.tv.data.browse.SourceAvailability,
    private val companion: com.ambient.tv.companion.CompanionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SpotifyHomeUiState(nativeAvailable = repo.nativeAvailable, webAuthorized = repo.authState.value.authorized, accountName = repo.authState.value.accountName))
    val state: StateFlow<SpotifyHomeUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var entered = false

    fun focusedItemId(stateKey: String): String? = preferences.sectionFocus(stateKey)
    fun onItemFocused(stateKey: String, item: SpotifyBrowseItem) {
        preferences.setSectionFocus(stateKey, "${item.type}:${item.id}")
        preferences.setSectionFocusRow(stateKey, null)
    }

    init {
        repo.start()
        viewModelScope.launch {
            repo.playback.collect { p -> _state.value = _state.value.copy(connected = p.connected, nativeAvailable = p.available) }
        }
        viewModelScope.launch {
            var wasAuthorized = repo.authState.value.authorized
            repo.authState.collect { a ->
                _state.value = _state.value.copy(
                    webAuthorized = a.authorized,
                    authBusy = a.busy,
                    accountName = a.accountName,
                    loading = if (a.authorized) _state.value.loading else false,
                    refreshing = a.authorized && _state.value.refreshing,
                    items = if (a.authorized) _state.value.items else emptyList(),
                    error = a.error ?: if (a.authorized) null else _state.value.error,
                    errorRes = if (a.authorized || a.error != null) null else _state.value.errorRes,
                )
                if (!a.authorized) { ++loadGeneration; loadJob?.cancel(); snapshots.clear("spotify") }
                if (a.authorized && !wasAuthorized) {
                    repo.start()
                }
                wasAuthorized = a.authorized
            }
        }
    }

    fun enter(initialSectionKey: String?) {
        if (!entered) {
            entered = true
            if (!initialSectionKey.isNullOrBlank() && initialSectionKey != "home") {
                loadSection(initialSectionKey)
                return
            }
        }
        refresh()
    }

    fun loadHome() = load("", "home")
    fun refresh() = refresh(manual = false)
    fun reload() {
        if (_state.value.refreshing || _state.value.authBusy || loadJob?.isActive == true) return
        refresh(manual = true)
    }
    private fun refresh(manual: Boolean) {
        val current = _state.value
        when (current.sectionKey) {
            "home" -> load("", "home", manual)
            "search" -> load(current.query.trim(), "search", manual)
            else -> loadSection(current.sectionKey, manual)
        }
    }
    fun loadSection(section: String) = loadSection(section, manual = false)
    private fun loadSection(section: String, manual: Boolean) {
        val generation = ++loadGeneration
        loadJob?.cancel()
        beginLoad("", section)
        loadJob = viewModelScope.launch {
            try {
                val namespace = restoreBrowse("section:$section", generation)
                currentCoroutineContext().ensureActive()
                if (generation != loadGeneration) return@launch
                if (!repo.webAuthorized) { _state.value = _state.value.copy(loading = false, items = emptyList(), errorRes = R.string.spotify_error_not_authorized); return@launch }
                val result = suspendResult { repo.librarySection(section) }
                currentCoroutineContext().ensureActive()
                if (generation != loadGeneration) return@launch
                result
                    .onSuccess { if (scopes.forProvider("spotify") == namespace.second) {
                        snapshots.write("spotify", namespace.first, "section:$section", it)
                        currentCoroutineContext().ensureActive()
                        if (generation == loadGeneration) _state.value = _state.value.copy(loading = false, items = it)
                    } }
                    .onFailure { if (manual || _state.value.items.isEmpty()) _state.value = _state.value.copy(loading = false, error = it.message, errorRes = if (it.message.isNullOrBlank()) R.string.spotify_error_load else null) }
            } finally {
                if (generation == loadGeneration) _state.value = _state.value.copy(refreshing = false)
            }
        }
    }
    fun search(query: String) = load(query.trim(), "search")

    private fun load(query: String, sectionKey: String, manual: Boolean = false) {
        val generation = ++loadGeneration
        loadJob?.cancel()
        beginLoad(query, sectionKey)
        loadJob = viewModelScope.launch {
            try {
                val cacheKey = if (query.isBlank()) "home" else "search:$query"
                val namespace = restoreBrowse(cacheKey, generation)
                currentCoroutineContext().ensureActive()
                if (generation != loadGeneration) return@launch
                if (!repo.nativeAvailable) {
                    _state.value = _state.value.copy(loading = false, errorRes = R.string.spotify_error_native_missing)
                    return@launch
                }
                if (!repo.webAuthorized) {
                    _state.value = _state.value.copy(loading = false, items = emptyList(), errorRes = R.string.spotify_error_not_authorized_settings)
                    return@launch
                }
                if (query.isNotBlank() && !manual) delay(350)
                val result = suspendResult { if (query.isBlank()) repo.home() else repo.search(query) }
                currentCoroutineContext().ensureActive()
                if (generation != loadGeneration) return@launch
                result
                    .onSuccess { if (scopes.forProvider("spotify") == namespace.second) {
                        snapshots.write("spotify", namespace.first, cacheKey, it)
                        currentCoroutineContext().ensureActive()
                        if (generation == loadGeneration) _state.value = _state.value.copy(loading = false, items = it)
                    } }
                    .onFailure { if (manual || _state.value.items.isEmpty()) _state.value = _state.value.copy(loading = false, error = it.message, errorRes = if (it.message.isNullOrBlank()) R.string.spotify_error_load else null) }
            } finally {
                if (generation == loadGeneration) _state.value = _state.value.copy(refreshing = false)
            }
        }
    }

    private suspend fun restoreBrowse(key: String, generation: Long): Pair<String, String> {
        val identity = scopes.forProvider("spotify")
        val namespace = snapshots.namespace("spotify", identity)
        if (_state.value.items.isEmpty()) snapshots.read<List<SpotifyBrowseItem>>(namespace, key)?.let { items ->
            currentCoroutineContext().ensureActive()
            if (generation == loadGeneration && scopes.forProvider("spotify") == identity) _state.value = _state.value.copy(items = items, loading = false)
        }
        return namespace to identity
    }

    private fun beginLoad(query: String, sectionKey: String) {
        val current = _state.value
        val items = current.items.takeIf { current.sectionKey == sectionKey && current.query == query }.orEmpty()
        _state.value = current.copy(
            loading = items.isEmpty(), refreshing = true, items = items, query = query, sectionKey = sectionKey,
            error = null, errorRes = null,
        )
    }

    fun play(item: SpotifyBrowseItem, onLocalStarted: () -> Unit = {}) {
        val parts = item.uri.split(':')
        val media = if (parts.size == 3) com.ambient.tv.companion.CompanionMedia("spotify:${parts[1]}", parts[2]) else null
        companion.requestPlayback(media, item.title) {
            if (item.type.name == "TRACK") {
                val remaining = state.value.items.dropWhile { it.id != item.id }
                    .filter { it.type.name == "TRACK" }.map { it.uri }
                if (remaining.size > 1) repo.playUris(remaining) else repo.playUri(item.uri)
            } else repo.playUri(item.uri)
            onLocalStarted()
        }
    }

    fun login() {
        if (_state.value.webAuthorized || _state.value.authBusy) return
        repo.login().onFailure { error ->
            _state.value = _state.value.copy(error = error.message, errorRes = if (error.message.isNullOrBlank()) R.string.spotify_error_login_start else null)
        }
    }

    fun logout() {
        if (!_state.value.webAuthorized || _state.value.authBusy) return
        ++loadGeneration
        loadJob?.cancel()
        repo.logoutWebApi()
        _state.value = _state.value.copy(
            loading = false,
            refreshing = false,
            webAuthorized = false,
            accountName = null,
            items = emptyList(),
            error = null,
            errorRes = null,
        )
    }
}
