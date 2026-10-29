package com.ambient.tv.ui.player

/** Keep a failed player's listeners, monitors and decoder from surviving its initial setup. */
internal fun <T> initializePlaybackOrRelease(
    vararg releaseOnFailure: () -> Unit,
    initialize: () -> T,
): T {
    try {
        return initialize()
    } catch (failure: Throwable) {
        // One failing release must not skip the remaining resources or hide the start failure.
        for (release in releaseOnFailure) {
            try {
                release()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
        }
        throw failure
    }
}
