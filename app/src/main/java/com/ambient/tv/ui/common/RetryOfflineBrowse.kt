package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.tv.connectivity.HostConnectivity
import com.ambient.tv.connectivity.HostHealth
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RetryOfflineBrowseEntryPoint {
    fun hostConnectivity(): HostConnectivity
}

/** First re-read after 15 s, then less often (30 s, 1, 2, 5 min) while the view stays open. */
internal fun offlineRetryDelayMs(attempt: Int): Long = when (attempt) {
    0 -> 15_000L
    1 -> 30_000L
    2 -> 60_000L
    3 -> 120_000L
    else -> 300_000L
}

/**
 * Re-reads a view whose source answered with an error while its server stays reachable.
 * While the server (machine) of [systems] is offline nothing is fetched: the light host check
 * runs in the background and [RefreshOnEnterAndReconnect] reloads once it is back.
 * [retry] itself must no-op when the view is already confirmed.
 */
@Composable
fun RetryOfflineBrowse(systems: Set<String> = emptySet(), retry: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val hosts = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, RetryOfflineBrowseEntryPoint::class.java).hostConnectivity()
    }
    val current = rememberUpdatedState(retry)
    val watched = rememberUpdatedState(systems)
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var attempt = 0
            while (true) {
                delay(offlineRetryDelayMs(attempt))
                val serverOffline = watched.value.isNotEmpty() &&
                    watched.value.all { hosts.health(it) == HostHealth.OFFLINE }
                if (!serverOffline) { current.value(); attempt++ }
            }
        }
    }
}
