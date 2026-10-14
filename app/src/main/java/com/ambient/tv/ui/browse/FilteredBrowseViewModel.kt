package com.ambient.tv.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

enum class BrowseViewMode { GRID, LIST }
enum class BrowseSort { TITLE, YEAR }

sealed interface FilteredBrowseUiState {
    data object Loading : FilteredBrowseUiState
    data class Loaded(
        val title: String,
        val items: List<MediaItem>,
        val viewMode: BrowseViewMode,
        val sort: BrowseSort,
        val focusedRatingKey: String? = null,
    ) : FilteredBrowseUiState
    data class Error(val message: String) : FilteredBrowseUiState
}

@HiltViewModel
class FilteredBrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MediaRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val kind: String = checkNotNull(savedStateHandle.get<String>("kind"))
    private val id: String = checkNotNull(savedStateHandle.get<String>("filterId"))
    private val title: String = checkNotNull(savedStateHandle.get<String>("title"))
    private val stateKey = "browse:$kind:$id"
    private var sourceItems: List<MediaItem> = emptyList()

    private val _uiState = MutableStateFlow<FilteredBrowseUiState>(FilteredBrowseUiState.Loading)
    val uiState: StateFlow<FilteredBrowseUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init { load() }

    fun load() {
        loadJob?.cancel()
        if (_uiState.value !is FilteredBrowseUiState.Loaded) {
            _uiState.value = FilteredBrowseUiState.Loading
        }
        loadJob = viewModelScope.launch {
            val result = when (kind) {
                "actor" -> repository.getItemsByActor(id)
                "collection" -> repository.getItemsByCollection(id)
                else -> Result.failure(IllegalArgumentException("Unknown browse filter"))
            }
            result.fold(
                onSuccess = { sourceItems = it; _uiState.value = buildLoaded() },
                onFailure = { _uiState.value = FilteredBrowseUiState.Error(it.message.orEmpty()) },
            )
        }
    }

    fun toggleViewMode() {
        val next = if (viewMode() == BrowseViewMode.GRID) BrowseViewMode.LIST else BrowseViewMode.GRID
        preferences.setSectionViewMode(stateKey, next.name)
        _uiState.value = buildLoaded()
    }

    fun cycleSort() {
        val next = if (sort() == BrowseSort.TITLE) BrowseSort.YEAR else BrowseSort.TITLE
        preferences.setSectionSort(stateKey, next.name)
        _uiState.value = buildLoaded()
    }

    fun onItemFocused(item: MediaItem) {
        preferences.setSectionFocus(stateKey, item.ratingKey)
        val current = _uiState.value as? FilteredBrowseUiState.Loaded ?: return
        if (current.focusedRatingKey != item.ratingKey) _uiState.value = current.copy(focusedRatingKey = item.ratingKey)
    }

    private fun viewMode() = preferences.sectionViewMode(stateKey)?.let { runCatching { BrowseViewMode.valueOf(it) }.getOrNull() } ?: BrowseViewMode.GRID
    private fun sort() = preferences.sectionSort(stateKey)?.let { runCatching { BrowseSort.valueOf(it) }.getOrNull() } ?: BrowseSort.TITLE

    private fun buildLoaded(): FilteredBrowseUiState.Loaded {
        val sort = sort()
        val items = when (sort) {
            BrowseSort.TITLE -> sourceItems.sortedBy { it.title.lowercase() }
            BrowseSort.YEAR -> sourceItems.sortedWith(compareByDescending<MediaItem> { it.year ?: 0 }.thenBy { it.title.lowercase() })
        }
        return FilteredBrowseUiState.Loaded(title, items, viewMode(), sort, preferences.sectionFocus(stateKey))
    }
}
