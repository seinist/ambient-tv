package com.ambient.tv.data.dlna

import java.io.IOException
import java.net.SocketTimeoutException

internal class DlnaHttpException(val status: Int, message: String) : IOException(message)

/** Retry the same offset with less metadata, never skip a failing item or mark it empty. */
internal suspend fun browseDlnaPageWithFallback(
    count: Int,
    fetch: suspend (Int) -> DlnaBrowsePage,
): DlnaBrowsePage {
    var requested = count
    while (true) {
        try {
            return fetch(requested).copy(requestedCount = requested)
        } catch (error: IOException) {
            val retryable = error is SocketTimeoutException ||
                (error is DlnaHttpException && error.status in setOf(500, 502, 503, 504))
            if (!retryable || requested == 1) throw error
            requested = if (requested > 10) 10 else 1
        }
    }
}
