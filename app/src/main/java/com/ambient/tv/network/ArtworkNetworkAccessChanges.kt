package com.ambient.tv.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * A cancelled startup wake can change several services at once. Resolve image access away
 * from the UI thread, and only send an image's actual offline/recovery transitions to Compose.
 * Keep at most one pending result without dropping recovery transitions.
 */
internal fun artworkNetworkAccessChanges(changes: Flow<*>, blocked: () -> Boolean): Flow<Boolean> =
    changes.map { blocked() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .buffer(0)
