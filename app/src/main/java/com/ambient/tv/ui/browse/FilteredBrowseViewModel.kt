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
import kotlinx.coroutines.ensureActive

enum class BrowseViewMode { GRID, LIST }
enum class BrowseSort { TITLE, YEAR }

sealed interface FilteredBrowseUiState {
    data object Loading : FilteredBrowseUiState
    data class Loaded(
        val title: String,
        val items: List<MediaItem>,
        val viewMode: BrowseViewMode,
        val sort: BrowseSort,
        val sortDescending: Boolean = false,
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
    val reconnectSources: Set<String> = setOf(com.ambient.tv.data.browse.mediaProvider(id))
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
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            result.fold(
                onSuccess = { sourceItems = it; _uiState.value = buildLoaded() },
                onFailure = {
                    if (_uiState.value !is FilteredBrowseUiState.Loaded) {
                        _uiState.value = FilteredBrowseUiState.Error(it.message.orEmpty())
                    }
                },
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
        val descending = next == BrowseSort.YEAR
        preferences.setSectionSort(stateKey, next.name)
        preferences.setSectionSortOrder(stateKey, if (descending) "DESCENDING" else "ASCENDING")
        _uiState.value = buildLoaded()
    }

    fun setSortDescending(descending: Boolean) {
        preferences.setSectionSortOrder(stateKey, if (descending) "DESCENDING" else "ASCENDING")
        _uiState.value = buildLoaded()
    }

    fun onItemFocused(item: MediaItem) {
        preferences.setSectionFocus(stateKey, item.ratingKey)
        val current = _uiState.value as? FilteredBrowseUiState.Loaded ?: return
        if (current.focusedRatingKey != item.ratingKey) _uiState.value = current.copy(focusedRatingKey = item.ratingKey)
    }

    private fun viewMode() = preferences.sectionViewMode(stateKey)?.let { runCatching { BrowseViewMode.valueOf(it) }.getOrNull() } ?: BrowseViewMode.GRID
    private fun sort() = preferences.sectionSort(stateKey)?.let { runCatching { BrowseSort.valueOf(it) }.getOrNull() } ?: BrowseSort.TITLE

    private fun sortDescending(sort: BrowseSort): Boolean = when (preferences.sectionSortOrder(stateKey)) {
        "DESCENDING" -> true
        "ASCENDING" -> false
        else -> sort == BrowseSort.YEAR
    }

    private fun buildLoaded(): FilteredBrowseUiState.Loaded {
        val sort = sort()
        val descending = sortDescending(sort)
        val items = when (sort) {
            BrowseSort.TITLE -> if (descending) sourceItems.sortedByDescending { it.title.lowercase() } else sourceItems.sortedBy { it.title.lowercase() }
            BrowseSort.YEAR -> sourceItems.sortedWith(
                (if (descending) compareByDescending<MediaItem> { it.year ?: 0 } else compareBy { it.year ?: Int.MAX_VALUE })
                    .thenBy { it.title.lowercase() },
            )
        }
        return FilteredBrowseUiState.Loaded(title, items, viewMode(), sort, descending, preferences.sectionFocus(stateKey))
    }
}
