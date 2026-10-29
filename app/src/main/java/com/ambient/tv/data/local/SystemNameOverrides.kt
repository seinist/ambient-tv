package com.ambient.tv.data.local

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Display names the user gave a system in Settings, by system key ("plex", "tvheadend", …).
 * A system without an entry keeps its provider name. Snapshot-backed, so every Composable that
 * names a system follows a rename without a flow being threaded through each screen.
 */
object SystemNameOverrides {
    const val MAX_LENGTH = 30
    val KEYS = listOf("plex", "jellyfin", "emby", "youtube", "spotify", "radio", "tvheadend", "dlna", "files", "trailers")

    var names: Map<String, String> by mutableStateOf(emptyMap())
        internal set

    fun normalized(names: Map<String, String>): Map<String, String> = names
        .filterKeys { it in KEYS }
        .mapValues { it.value.trim().take(MAX_LENGTH).trim() }
        .filterValues(String::isNotBlank)
}
