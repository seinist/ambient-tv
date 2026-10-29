package com.ambient.tv.ui.tvheadend

/** Unknown HTTP stream formats are checked by the buffer when enabled; HLS is already known not to qualify. */
internal fun liveTimeshiftConfigurable(
    isLive: Boolean,
    hasChannel: Boolean,
    playingRecording: Boolean,
    changingChannel: Boolean,
    url: String,
): Boolean {
    if (!isLive || !hasChannel || playingRecording || changingChannel) return false
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return false
    return !url.substringBefore('#').substringBefore('?').endsWith(".m3u8", ignoreCase = true)
}
