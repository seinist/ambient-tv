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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpotifyHomeUiState(
    val loading: Boolean = true,
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
                    items = if (a.authorized) _state.value.items else emptyList(),
                    error = a.error ?: if (a.authorized) null else _state.value.error,
                    errorRes = if (a.authorized || a.error != null) null else _state.value.errorRes,
                )
                if (!a.authorized) { loadJob?.cancel(); snapshots.clear("spotify") }
                if (a.authorized && !wasAuthorized) {
                    repo.start()
                }
                wasAuthorized = a.authorized
            }
        }
        loadHome()
    }

    fun loadHome() = load("", "home")
    fun refresh() {
        val current = _state.value
        when (current.sectionKey) {
            "home" -> loadHome()
            "search" -> search(current.query)
            else -> loadSection(current.sectionKey)
        }
    }
    fun loadSection(section: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            beginLoad("", section)
            val namespace = restoreBrowse("section:$section")
            if (!repo.webAuthorized) { _state.value = _state.value.copy(loading = false, items = emptyList(), errorRes = R.string.spotify_error_not_authorized); return@launch }
            suspendResult { repo.librarySection(section) }
                .onSuccess { if (scopes.forProvider("spotify") == namespace.second) {
                    snapshots.write("spotify", namespace.first, "section:$section", it)
                    _state.value = _state.value.copy(loading = false, items = it)
                } }
                .onFailure { if (_state.value.items.isEmpty()) _state.value = _state.value.copy(loading = false, error = it.message, errorRes = if (it.message.isNullOrBlank()) R.string.spotify_error_load else null) }
        }
    }
    fun search(query: String) = load(query.trim(), "search")

    private fun load(query: String, sectionKey: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            beginLoad(query, sectionKey)
            val cacheKey = if (query.isBlank()) "home" else "search:$query"
            val namespace = restoreBrowse(cacheKey)
            if (!repo.nativeAvailable) {
                _state.value = _state.value.copy(loading = false, errorRes = R.string.spotify_error_native_missing)
                return@launch
            }
            if (!repo.webAuthorized) {
                _state.value = _state.value.copy(loading = false, items = emptyList(), errorRes = R.string.spotify_error_not_authorized_settings)
                return@launch
            }
            if (query.isNotBlank()) delay(350)
            suspendResult { if (query.isBlank()) repo.home() else repo.search(query) }
                .onSuccess { if (scopes.forProvider("spotify") == namespace.second) {
                    snapshots.write("spotify", namespace.first, cacheKey, it)
                    _state.value = _state.value.copy(loading = false, items = it)
                } }
                .onFailure { if (_state.value.items.isEmpty()) _state.value = _state.value.copy(loading = false, error = it.message, errorRes = if (it.message.isNullOrBlank()) R.string.spotify_error_load else null) }
        }
    }

    private suspend fun restoreBrowse(key: String): Pair<String, String> {
        val identity = scopes.forProvider("spotify")
        val namespace = snapshots.namespace("spotify", identity)
        if (_state.value.items.isEmpty()) snapshots.read<List<SpotifyBrowseItem>>(namespace, key)?.let { items ->
            if (scopes.forProvider("spotify") == identity) _state.value = _state.value.copy(items = items, loading = false)
        }
        return namespace to identity
    }

    private fun beginLoad(query: String, sectionKey: String) {
        val current = _state.value
        val items = current.items.takeIf { current.sectionKey == sectionKey && current.query == query }.orEmpty()
        _state.value = current.copy(
            loading = items.isEmpty(), items = items, query = query, sectionKey = sectionKey,
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
        loadJob?.cancel()
        repo.logoutWebApi()
        _state.value = _state.value.copy(
            loading = false,
            webAuthorized = false,
            accountName = null,
            items = emptyList(),
            error = null,
            errorRes = null,
        )
    }
}
