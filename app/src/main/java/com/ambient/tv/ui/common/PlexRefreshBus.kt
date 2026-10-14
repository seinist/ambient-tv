package com.ambient.tv.ui.common

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App-local generation counter used to ask Plex screens to re-query the server.
 * StateFlow makes a refresh request survive navigation transitions (for example player -> Home).
 */
@Singleton
class PlexRefreshBus @Inject constructor() {
    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()

    fun requestRefresh() {
        _generation.update { it + 1L }
    }
}
