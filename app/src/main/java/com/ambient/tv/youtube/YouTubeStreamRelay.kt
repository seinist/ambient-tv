package com.ambient.tv.youtube

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** The process-wide [ChunkedStreamRelay] VLC and MPV fetch googlevideo through. */
object YouTubeStreamRelay {
    private val relay by lazy {
        ChunkedStreamRelay(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build(),
            onUpstreamStatus = { code ->
                // Status only: stream URLs carry signatures and are never logged.
                com.ambient.tv.diagnostics.DiagnosticLog.warn("youtube-relay", "googlevideo answered HTTP $code")
            },
            onTransferTiming = { timing ->
                if (com.ambient.tv.BuildConfig.DEBUG || com.ambient.tv.diagnostics.DiagnosticLog.enabled) {
                    com.ambient.tv.ui.player.logPlaybackStartup("youtube-relay", timing.stage, timing.openedAtNs,
                        "offset=${timing.rangeStart} head=${timing.headOnly} requests=${timing.upstreamRequests} " +
                            "sent=${timing.sentBytes} headers_ms=${timing.headersMs} first_body_ms=${timing.firstBodyMs}")
                }
            },
        )
    }

    /**
     * Resolves a fresh URL for [videoId]'s stream that the expired URL belonged to; set by the
     * engine. Called on a relay thread and may block for a network round trip.
     */
    @Volatile var renewer: ((videoId: String, expiredUrl: String) -> String?)? = null

    /** Relay URL for a googlevideo [upstream]; with [videoId] an expired link is renewed in place. */
    fun url(upstream: String, headers: Map<String, String>, videoId: String? = null): String =
        relay.register(upstream, headers, videoId?.let { id -> { failedUrl -> renewer?.invoke(id, failedUrl) } })
}
