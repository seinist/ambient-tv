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
    /** Same for the Media3 effect chain's dropped-buffer guard. */
    val media3OverloadContinueSignature: String? = null,
    val audio: AudioSettings? = null,
    /** Compatibility with former manually saved title snapshots. No new snapshots are created. */
    val snapshot: Boolean = false,
    val savedAt: Long = 0L,
    /** Display name for the saved-profile manager (title, channel, season…); not a setting. */
    val label: String? = null,
) {
    val isEmpty: Boolean get() = copy(savedAt = 0L, label = null) == TitleVideoSettings()
    fun rendererOrDefault(default: String): String =
        rendererKey?.takeIf { it in setOf("auto", "media3", "vlc", "mpv") } ?: default

    /**
     * Field by field: this (narrower) scope wins, [broader] fills the gaps. Overload signatures and
     * old snapshots stay title-only; they never come from a season, series or collection.
     */
    fun inheritFrom(broader: TitleVideoSettings?): TitleVideoSettings = if (broader == null) this else copy(
        rendererKey = rendererKey ?: broader.rendererKey,
        mpv = mpv ?: broader.mpv, vlc = vlc ?: broader.vlc, media3 = media3 ?: broader.media3,
        mpvDeinterlaceMode = mpvDeinterlaceMode ?: broader.mpvDeinterlaceMode,
        mpvDeinterlaceFilter = mpvDeinterlaceFilter ?: broader.mpvDeinterlaceFilter,
        vlcDeinterlaceMode = vlcDeinterlaceMode ?: broader.vlcDeinterlaceMode,
        vlcDeinterlaceFilter = vlcDeinterlaceFilter ?: broader.vlcDeinterlaceFilter,
        mpvOutput = mpvOutput ?: broader.mpvOutput, mpvDecoder = mpvDecoder ?: broader.mpvDecoder,
        vlcOutput = vlcOutput ?: broader.vlcOutput,
        audioDelayMs = audioDelayMs ?: broader.audioDelayMs,
        subtitleDelayMs = subtitleDelayMs ?: broader.subtitleDelayMs,
        audio = audio ?: broader.audio,
        savedAt = maxOf(savedAt, broader.savedAt),
    )

    /** Drops every field [broader] now carries, so the broader scope takes effect for this item. */
    fun withoutFieldsOf(broader: TitleVideoSettings): TitleVideoSettings = copy(
        rendererKey = rendererKey.takeIf { broader.rendererKey == null },
        mpv = mpv.takeIf { broader.mpv == null }, vlc = vlc.takeIf { broader.vlc == null },
        media3 = media3.takeIf { broader.media3 == null },
        mpvDeinterlaceMode = mpvDeinterlaceMode.takeIf { broader.mpvDeinterlaceMode == null },
        mpvDeinterlaceFilter = mpvDeinterlaceFilter.takeIf { broader.mpvDeinterlaceFilter == null },
        vlcDeinterlaceMode = vlcDeinterlaceMode.takeIf { broader.vlcDeinterlaceMode == null },
        vlcDeinterlaceFilter = vlcDeinterlaceFilter.takeIf { broader.vlcDeinterlaceFilter == null },
        mpvOutput = mpvOutput.takeIf { broader.mpvOutput == null },
        mpvDecoder = mpvDecoder.takeIf { broader.mpvDecoder == null },
        vlcOutput = vlcOutput.takeIf { broader.vlcOutput == null },
        audioDelayMs = audioDelayMs.takeIf { broader.audioDelayMs == null },
        subtitleDelayMs = subtitleDelayMs.takeIf { broader.subtitleDelayMs == null },
        audio = audio.takeIf { broader.audio == null },
    )

    companion object {
        const val MAX_TITLES = 200

        /** Season/series/collection layers kept (newest by savedAt), so the store and backups stay bounded. */
        const val MAX_SCOPES = 500

        /** Stores [value] under [key] (or removes it when empty) and keeps the newest [limit] titles. */
        fun put(all: Map<String, TitleVideoSettings>, key: String, value: TitleVideoSettings, limit: Int = MAX_TITLES): Map<String, TitleVideoSettings> {
            val next = all.toMutableMap()
            if (value.isEmpty) next.remove(key) else next[key] = value
            if (next.size <= limit) return next
            return next.entries.sortedByDescending { it.value.savedAt }.take(limit).associate { it.key to it.value }
        }

        /** Season, series and collection settings: same shape, the newest [limit] ([MAX_SCOPES]) scopes. */
        fun putScoped(all: Map<String, TitleVideoSettings>, key: String, value: TitleVideoSettings, limit: Int = MAX_SCOPES): Map<String, TitleVideoSettings> =
            put(all, key, value.copy(snapshot = false, mpvOverloadContinueSignature = null, vlcOverloadContinueSignature = null, media3OverloadContinueSignature = null), limit)

        val SCOPED_PREFIXES = listOf("season:", "series:", "collection:")
        fun isScopedKey(key: String): Boolean = SCOPED_PREFIXES.any { key.startsWith(it) && key.length > it.length }
    }
}
