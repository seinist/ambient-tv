package com.ambient.tv.ui.player.backend

import java.util.concurrent.CompletableFuture

/**
 * [ready] includes the controller's initial composition boundary. Once it completes, dispatch
 * directly to the owner: SurfaceHolder callbacks may be waiting on the UI thread for a detach.
 */
internal class MpvOwnerDispatcher(
    private val ready: CompletableFuture<*>,
    private val postToOwner: (() -> Unit) -> Unit,
) {
    fun dispatch(task: () -> Unit) {
        ready.whenComplete { _, _ -> postToOwner(task) }
    }
}
