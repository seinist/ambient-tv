package com.ambient.tv.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.radio.RadioBrowserRepository
import com.ambient.tv.data.radio.RadioStation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RadioHomeUiState(
    val query: String = "",
    val items: List<RadioStation> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class RadioHomeViewModel @Inject constructor(
    private val repository: RadioBrowserRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(RadioHomeUiState())
    val state: StateFlow<RadioHomeUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var entered = false

    fun enter() {
        if (entered) return
        entered = true
        loadPopular()
    }

    fun refresh() {
        if (_state.value.hasSearched) submitSearch() else loadPopular()
    }

    fun onQueryChanged(value: String) {
        _state.update { it.copy(query = value.take(120)) }
    }

    fun submitSearch() {
        val query = _state.value.query.trim()
        if (query.length < 2) {
            if (query.isBlank()) loadPopular()
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    loadingMore = false,
                    items = emptyList(),
                    hasMore = false,
                    hasSearched = true,
                    error = null,
                )
            }
            runCatching { repository.search(query = query) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            loading = false,
                            items = page.items,
                            hasMore = page.hasMore,
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            items = emptyList(),
                            hasMore = false,
                            error = error.message,
                        )
                    }
                }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, error = null) }
            val offset = current.items.size
            val result = if (current.hasSearched) {
                repository.search(query = current.query.trim(), offset = offset)
            } else {
                // Popular is intentionally capped to one page until the browse categories land.
                null
            }
            if (result == null) {
                _state.update { it.copy(loadingMore = false, hasMore = false) }
                return@launch
            }
            _state.update {
                it.copy(
                    loadingMore = false,
                    items = (it.items + result.items).distinctBy(RadioStation::id),
                    hasMore = result.hasMore,
                    error = null,
                )
            }
        }
    }

    private fun loadPopular() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    loadingMore = false,
                    hasSearched = false,
                    items = emptyList(),
                    hasMore = false,
                    error = null,
                )
            }
            runCatching { repository.popular(50) }
                .onSuccess { stations ->
                    _state.update { it.copy(loading = false, items = stations, hasMore = false, error = null) }
                }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, items = emptyList(), error = error.message) }
                }
        }
    }
}
