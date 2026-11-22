package com.ambient.tv.ui.youtube

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.ambient.tv.youtube.lateYouTubeResumeTarget

/** A late watch-next resume belongs to the video, not to an MPV/VLC output instance. */
internal class YouTubePlaybackResume(
    private val initialStartMs: Long,
    private val initialExternalSeekVersion: Int,
) {
    private var applied = initialStartMs > 0L
    private var userSeeked = false

    fun userSeek() {
        userSeeked = true
    }

    fun resolve(isLive: Boolean, resumeMs: Long, resolved: Boolean, externalSeekVersion: Int): Long? {
        if (!resolved) return null
        val target = lateYouTubeResumeTarget(
            isLive, initialStartMs, resumeMs,
            userSeeked = userSeeked || externalSeekVersion != initialExternalSeekVersion,
            resumeAlreadyApplied = applied,
        )
        // A resolved zero is final too. Enriched metadata must not restore older history later.
        applied = true
        return target
    }
}

@Composable
internal fun rememberYouTubePlaybackResume(
    videoId: String,
    initialStartMs: Long,
    externalSeekVersion: Int,
): YouTubePlaybackResume = remember(videoId) {
    YouTubePlaybackResume(initialStartMs, externalSeekVersion)
}
