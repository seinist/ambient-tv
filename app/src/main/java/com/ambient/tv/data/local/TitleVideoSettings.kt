package com.ambient.tv.data.local

import kotlinx.serialization.Serializable

/**
 * Picture and filter choices made for one title, restored when that title plays again. Only what
 * differs from the inherited width/global defaults is kept automatically. Legacy snapshots remain readable;
 * a null field means "use the default".
 */
@Serializable
data class TitleVideoSettings(
    /** Explicit player choice for this title; null follows the app default. */
    val rendererKey: String? = null,
    val mpv: MpvImageOptions? = null,
    val mpvDeinterlaceMode: String? = null,
    val mpvDeinterlaceFilter: String? = null,
    val vlc: VlcImageOptions? = null,
    val vlcDeinterlaceMode: String? = null,
    val vlcDeinterlaceFilter: String? = null,
    val media3: Media3ImageOptions? = null,
    val mpvOutput: String? = null,
    val mpvDecoder: String? = null,
    val vlcOutput: String? = null,
    /** A/V delay chosen in the player for this title (all backends). */
    val audioDelayMs: Int? = null,
    /** Subtitle timing shift for this title (MPV, VLC). */
    val subtitleDelayMs: Int? = null,
    /**
     * Render-check signature the user accepted with "keep watching anyway" for MPV.
     * Restored on the next play of this title so the overload dialog does not return
     * for the same filter set. Cleared when the live signature no longer matches.
     */
    val mpvOverloadContinueSignature: String? = null,
    /** Same as [mpvOverloadContinueSignature] for LibVLC's lost-picture guard. */
    val vlcOverloadContinueSignature: String? = null,
    val audio: AudioSettings? = null,
    /** Compatibility with former manually saved title snapshots. No new snapshots are created. */
    val snapshot: Boolean = false,
    val savedAt: Long = 0L,
) {
    val isEmpty: Boolean get() = copy(savedAt = 0L) == TitleVideoSettings()
    fun rendererOrDefault(default: String): String =
        rendererKey?.takeIf { it in setOf("auto", "media3", "vlc", "mpv") } ?: default

    companion object {
        const val MAX_TITLES = 200

        /** Stores [value] under [key] (or removes it when empty) and keeps the newest [limit] titles. */
        fun put(all: Map<String, TitleVideoSettings>, key: String, value: TitleVideoSettings, limit: Int = MAX_TITLES): Map<String, TitleVideoSettings> {
            val next = all.toMutableMap()
            if (value.isEmpty) next.remove(key) else next[key] = value
            if (next.size <= limit) return next
            return next.entries.sortedByDescending { it.value.savedAt }.take(limit).associate { it.key to it.value }
        }
    }
}
