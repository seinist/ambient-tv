package com.ambient.tv.ui.player.backend

import java.util.concurrent.CompletableFuture

/** A new native output must not open while an earlier owner is still closing. */
internal class AudioOutputReleaseGate {
    private val pending = mutableListOf<CompletableFuture<Unit>>()

    @Synchronized fun beginRelease(): CompletableFuture<Unit> = CompletableFuture<Unit>().also {
        pending.removeAll { it.isDone && !it.isCompletedExceptionally }
        pending.add(it)
    }

    @Synchronized fun snapshot(): CompletableFuture<Void> =
        CompletableFuture.allOf(*pending.toTypedArray())
}

internal val nativeAudioReleases = AudioOutputReleaseGate()
