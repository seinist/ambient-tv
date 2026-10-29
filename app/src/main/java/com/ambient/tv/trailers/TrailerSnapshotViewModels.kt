package com.ambient.tv.trailers

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.ambient.tv.domain.model.MediaItem
import kotlinx.coroutines.Job

internal class TrailerCatalogLoad(val replace: Boolean, val job: Job?)

/** Display snapshots survive a destination's composition; repository cache expiry leaves them intact. */
internal class TrailerCatalogSnapshotViewModel : ViewModel() {
    val feeds = mutableStateMapOf<TrailerFeed, TrailerFeedUi>()
    // Shared with the snapshot so a cancelled old composition cannot publish over a newer load.
    val loads = mutableStateMapOf<TrailerFeed, TrailerCatalogLoad>()

    fun beginLoad(feed: TrailerFeed, replace: Boolean, job: Job?): TrailerCatalogLoad {
        val token = TrailerCatalogLoad(replace, job)
        val previous = loads.put(feed, token)
        if (previous?.job !== job) previous?.job?.cancel()
        return token
    }

    fun finishLoad(feed: TrailerFeed, token: TrailerCatalogLoad): Boolean {
        if (loads[feed] !== token) return false
        loads.remove(feed)
        return true
    }

    fun cancelLoads() {
        val previous = loads.toMap()
        loads.clear()
        previous.forEach { (feed, token) ->
            token.job?.cancel()
            feeds[feed]?.let { state ->
                if (state.loadingInitial) feeds.remove(feed)
                else feeds[feed] = state.copy(loadingMore = false, refreshing = false)
            }
        }
    }
}

internal class TrailerTitleSnapshotViewModel : ViewModel() {
    val title = mutableStateOf("")
    val videos = mutableStateOf<List<TrailerVideo>>(emptyList())
    val serverTrailers = mutableStateOf<List<MediaItem>>(emptyList())
    val loadedKey = mutableStateOf<String?>(null)
}
