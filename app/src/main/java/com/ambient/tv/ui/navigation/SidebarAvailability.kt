package com.ambient.tv.ui.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/** Reconcile even when a login completed before the sidebar began observing changes. */
internal fun observeSidebarAvailability(
    vararg changes: Flow<*>,
    snapshot: () -> List<Boolean>,
): Flow<List<Boolean>> = merge(*changes)
    .map { snapshot() }
    .onStart { emit(snapshot()) }
    .distinctUntilChanged()
