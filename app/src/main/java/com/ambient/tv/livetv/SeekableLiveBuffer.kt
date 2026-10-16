package com.ambient.tv.livetv

/** A seekable live window, backed either by local Timeshift or a server recording. */
interface SeekableLiveBuffer {
    val liveWallMs: Long
    val oldestWallMs: Long
    val recordingId: String? get() = null
    val finished: Boolean get() = false
    /** May read the server index; call on an IO dispatcher. */
    fun urlAt(wallMs: Long): Pair<String, Long>
    fun liveUrl(): Pair<String, Long> = urlAt(liveWallMs - 1_500L)
    fun reopenedUrl(url: String): Pair<String, Long>?
    fun stop()
}
