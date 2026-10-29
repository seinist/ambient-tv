package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.tv.data.browse.BrowseReconnects
import com.ambient.tv.data.browse.browseReconnectVersion
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BrowseReconnectEntryPoint {
    fun browseReconnects(): BrowseReconnects
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
