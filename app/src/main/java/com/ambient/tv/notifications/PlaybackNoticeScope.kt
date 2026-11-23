package com.ambient.tv.notifications

/**
 * Systems the running playback depends on: its own source plus every service on the same
 * server (machine). Connection notices about anything else stay off the player.
 */
object PlaybackNoticeScope {
    @Volatile var playingSystems: () -> Set<String> = { emptySet() }
}

/** Connection/wake/VPN notices only interrupt playback when they concern the playing server. */
internal fun AppNotice.concernsPlayback(playing: Set<String>): Boolean {
    if (kind !in CONNECTIVITY_NOTICE_KINDS) return interruptPlayback || phase == NoticePhase.ERROR
    if (systems.isEmpty()) return phase == NoticePhase.ERROR
    if (systems.none { system -> system in playing || system.substringBefore(':') in playing }) return false
    return phase == NoticePhase.ERROR || interruptPlayback
}

internal val CONNECTIVITY_NOTICE_KINDS = setOf(NoticeKind.CONNECTION, NoticeKind.WAKE, NoticeKind.VPN)
