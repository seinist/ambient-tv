package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.tv.data.browse.BrowseReconnects
import com.ambient.tv.data.browse.BrowseRefreshObservation
import com.ambient.tv.data.browse.browseReconnectVersion
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BrowseReconnectEntryPoint {
    fun browseReconnects(): BrowseReconnects
}

/**
 * One owner per content destination: keep its snapshot, then fetch after targeted cache expiry.
 * RESUMED excludes an outgoing destination and a predictive-back preview. Returning to an existing
 * ViewModel starts a fresh entry as well. Auxiliary components keep [RefreshOnReconnect] instead.
 */
@Composable
fun RefreshOnEnterAndReconnect(sources: Set<String>, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val reconnects = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, BrowseReconnectEntryPoint::class.java).browseReconnects()
    }
    val owner = LocalLifecycleOwner.current
    val currentSources = rememberUpdatedState(sources.toSet())
    val refresh = rememberUpdatedState(onRefresh)
    LaunchedEffect(reconnects, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // A historical or simultaneous recovery is covered by this same entry, not a second load.
            val entrySources = currentSources.value
            val observation = BrowseRefreshObservation(entrySources, reconnects.prepareEntry(entrySources))
            refresh.value()
            combine(snapshotFlow { currentSources.value }, reconnects.revisions) { watched, revisions ->
                watched to revisions
            }.collect { (watched, revisions) ->
                if (observation.changed(watched, revisions)) refresh.value()
            }
        }
    }
}

/** A paused/back-stack view keeps its cursor and catches up once when it becomes visible again. */
@Composable
fun RefreshOnReconnect(sources: Set<String>, onRefresh: () -> Unit) {
    if (sources.isEmpty()) return
    val context = LocalContext.current
    val reconnects = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, BrowseReconnectEntryPoint::class.java).browseReconnects()
    }
    val owner = LocalLifecycleOwner.current
    val sourceKey = sources.sorted().joinToString("|")
    val watched = remember(sourceKey) { sources.toSet() }
    val refresh = rememberUpdatedState(onRefresh)
    var seen by rememberSaveable(sourceKey) { mutableStateOf("") }
    LaunchedEffect(reconnects, owner, sourceKey) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            reconnects.revisions.map { browseReconnectVersion(it, watched) }.distinctUntilChanged().collect { revision ->
                val cursor = "${reconnects.epoch}:$revision"
                if (cursor != seen) {
                    seen = cursor
                    if (revision > 0L) refresh.value()
                }
            }
        }
    }
}
