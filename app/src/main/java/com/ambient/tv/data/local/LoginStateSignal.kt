package com.ambient.tv.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bumped whenever a system's login is confirmed or rejected, so menus and Home show or hide that
 * system without waiting for the next navigation.
 */
object LoginStateSignal {
    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()

    fun changed() = _generation.update { it + 1 }
}
