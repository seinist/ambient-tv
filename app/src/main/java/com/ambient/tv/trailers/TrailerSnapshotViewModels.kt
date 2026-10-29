package com.ambient.tv.trailers

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.ambient.tv.domain.model.MediaItem

/** Display snapshots survive a destination's composition; repository cache expiry leaves them intact. */
internal class TrailerCatalogSnapshotViewModel : ViewModel() {
    val feeds = mutableStateMapOf<TrailerFeed, TrailerFeedUi>()
    // Shared with the snapshot so a cancelled old composition cannot publish over a newer load.
    val loads = mutableMapOf<TrailerFeed, Any>()
}

internal class TrailerTitleSnapshotViewModel : ViewModel() {
    val title = mutableStateOf("")
    val videos = mutableStateOf<List<TrailerVideo>>(emptyList())
    val serverTrailers = mutableStateOf<List<MediaItem>>(emptyList())
    val loadedKey = mutableStateOf<String?>(null)
}
