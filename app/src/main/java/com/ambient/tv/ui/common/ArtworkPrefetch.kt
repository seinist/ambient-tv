package com.ambient.tv.ui.common

import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Structured requests stop with their owner; warming never floods all artwork decoders at once. */
internal suspend fun prefetchArtworkRequests(loader: ImageLoader, requests: List<ImageRequest>) {
    val slots = Semaphore(2)
    coroutineScope {
        requests.map { request -> async { slots.withPermit { loader.execute(request) } } }.awaitAll()
    }
}
