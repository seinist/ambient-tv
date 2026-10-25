@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import com.ambient.tv.data.local.TitleVideoSettings
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController

/**
 * Remembers picture/filter choices per title (source + id) and restores them when the same title
 * plays again. Captured during playback and flushed before replacement/stop, including "reduce"
 * after an overload. Live channels are scoped by server and channel; trailers are not remembered.
 */
internal object TitleVideoSettingsStore {
    private var sessionRendererSelection = false

    /** Loading/resetting a profile must not recreate a removed per-video renderer override. */
    fun withSessionRendererSelection(action: () -> Unit) {
        val previous = sessionRendererSelection
        sessionRendererSelection = true
        try { action() } finally { sessionRendererSelection = previous }
    }

    fun key(metadata: PlaybackMetadata?): String? {
        metadata ?: return null
        if (metadata.isTrailer || metadata.source == "shared" || metadata.mediaId.isBlank()) return null
        if (metadata.isLive) return channelKey(metadata.liveTvSourceId, metadata.mediaId)
        return titleKey(metadata.source, metadata.mediaId, metadata.videoScope?.serverId)
    }

    /** Plex rating keys are only unique per server; older keys without server stay readable. */
    fun titleKey(source: String, mediaId: String, serverId: String?): String =
        serverId?.takeIf { source == "plex" && it.isNotBlank() }?.let { "plex:${it.length}:$it:$mediaId" } ?: "$source:$mediaId"

    private fun legacyKey(metadata: PlaybackMetadata?): String? =
        metadata?.takeIf { !it.isLive }?.let { "${it.source}:${it.mediaId}" }?.takeIf { it != key(metadata) }

    /** Length-delimited source identity avoids collisions between server/channel ID pairs. */
    fun channelKey(sourceId: String?, channelId: String): String? = sourceId?.takeIf { it.isNotBlank() }
        ?.takeIf { channelId.isNotBlank() }?.let { "live-channel:${it.length}:$it:$channelId" }

    // "Remember changes for": chosen per title in the player; a new title starts with the title itself.
    private var rememberTarget: Pair<String, String>? = null

    fun layers(context: Context, metadata: PlaybackMetadata?): List<VideoScopeLayer> =
        videoScopeLayers(key(metadata), metadata?.source.orEmpty(), metadata?.videoScope.takeIf { metadata?.isLive != true },
            videoImagePreferences(context).scopedVideoSettings)

    fun rememberLayer(context: Context, metadata: PlaybackMetadata?): VideoScopeLayer? {
        val layers = layers(context, metadata)
        val title = key(metadata) ?: return null
        return rememberTarget?.takeIf { it.first == title }?.let { (_, key) -> layers.firstOrNull { it.key == key } } ?: layers.firstOrNull()
    }

    fun setRememberLayer(metadata: PlaybackMetadata?, layer: VideoScopeLayer) {
        val title = key(metadata) ?: return
        rememberTarget = title to layer.key
    }

    private fun read(preferences: com.ambient.tv.data.local.AppPreferences, metadata: PlaybackMetadata?, layer: VideoScopeLayer): TitleVideoSettings? =
        if (layer.scope == VideoSettingsScope.TITLE) {
            val all = preferences.titleVideoSettings
            all[layer.key] ?: legacyKey(metadata)?.let { all[it] }
        } else preferences.scopedVideoSettings[layer.key]

    private fun write(preferences: com.ambient.tv.data.local.AppPreferences, metadata: PlaybackMetadata?, layer: VideoScopeLayer, value: TitleVideoSettings) {
        if (layer.scope == VideoSettingsScope.TITLE) {
            val all = preferences.titleVideoSettings
            val legacy = legacyKey(metadata)
            val base = if (legacy != null) all - legacy else all
            val updated = TitleVideoSettings.put(base, layer.key, value)
            if (updated != all) preferences.titleVideoSettings = updated
        } else {
            val all = preferences.scopedVideoSettings
            val updated = TitleVideoSettings.putScoped(all, layer.key, value)
            if (updated != all) preferences.scopedVideoSettings = updated
        }
    }

    /** Stored values per scope, narrow to broad. */
    fun layerValues(context: Context, metadata: PlaybackMetadata?): List<Pair<VideoScopeLayer, TitleVideoSettings?>> {
        val preferences = videoImagePreferences(context)
        return layers(context, metadata).map { it to read(preferences, metadata, it) }
    }

    /** Effective title-level settings: title over season over series over collection. */
    fun load(context: Context, metadata: PlaybackMetadata?): TitleVideoSettings? =
        mergeVideoScopes(layerValues(context, metadata).map { it.second })

    fun rendererFor(context: Context, metadata: PlaybackMetadata, default: String): String =
        load(context, metadata)?.rendererOrDefault(default) ?: default

    fun rememberRenderer(context: Context, metadata: PlaybackMetadata?, renderer: String?) {
        if (sessionRendererSelection) return
        val values = layerValues(context, metadata).takeIf { it.isNotEmpty() } ?: return
        val target = rememberLayer(context, metadata) ?: return
        val preferences = videoImagePreferences(context)
        val index = values.indexOfFirst { it.first.key == target.key }.coerceAtLeast(0)
        val current = values[index].second ?: TitleVideoSettings()
        val next = current.copy(rendererKey = renderer?.takeIf { it in setOf("auto", "media3", "vlc", "mpv") },
            savedAt = System.currentTimeMillis())
        write(preferences, metadata, values[index].first, next)
        // A broader choice takes effect here: narrower scopes of this title drop their own player.
        if (next.rendererKey != null) values.take(index).forEach { (layer, value) ->
            value?.let { write(preferences, metadata, layer, it.copy(rendererKey = null)) }
        }
    }

    /** Panel action: drop everything remembered for this title. */
    fun forget(context: Context, metadata: PlaybackMetadata?) {
        val key = key(metadata) ?: return
        val preferences = videoImagePreferences(context)
        val all = preferences.titleVideoSettings
        val keys = setOfNotNull(key, legacyKey(metadata))
        if (keys.any { it in all }) preferences.titleVideoSettings = all - keys
    }

    /** Panel action: drop one season/series/collection scope. */
    fun forgetScope(context: Context, layer: VideoScopeLayer) {
        if (layer.scope == VideoSettingsScope.TITLE) return
        val preferences = videoImagePreferences(context)
        val all = preferences.scopedVideoSettings
        if (layer.key in all) preferences.scopedVideoSettings = all - layer.key
    }

    fun clearProfileOverrides(context: Context, metadata: PlaybackMetadata?) {
        val layer = layers(context, metadata).firstOrNull() ?: return
        val preferences = videoImagePreferences(context)
        val title = read(preferences, metadata, layer) ?: return
        val updated = title.copy(snapshot = false, audio = null, audioDelayMs = null, rendererKey = null, mpv = null, vlc = null, media3 = null,
            mpvOutput = null, mpvDecoder = null, vlcOutput = null,
            mpvDeinterlaceMode = null, mpvDeinterlaceFilter = null,
            vlcDeinterlaceMode = null, vlcDeinterlaceFilter = null,
            mpvOverloadContinueSignature = null, vlcOverloadContinueSignature = null)
        write(preferences, metadata, layer, updated)
    }

    /**
     * Persists "keep watching anyway" for [metadata] immediately so a later play of the
     * same title skips the overload dialog for that filter signature. Always title-only.
     */
    fun rememberOverloadContinue(context: Context, metadata: PlaybackMetadata?, backend: String, signature: String?) {
        val layer = layers(context, metadata).firstOrNull() ?: return
        val preferences = videoImagePreferences(context)
        val current = read(preferences, metadata, layer) ?: TitleVideoSettings()
        val next = when (backend) {
            "mpv" -> current.copy(mpvOverloadContinueSignature = signature?.takeIf { it.isNotBlank() })
            "vlc" -> current.copy(vlcOverloadContinueSignature = signature?.takeIf { it.isNotBlank() })
            else -> return
        }
        if (next == current) return
        write(preferences, metadata, layer, next.copy(savedAt = System.currentTimeMillis()))
    }

    /** Marks [session] as belonging to a rememberable title and applies [delayMs] (null = profile delay). */
    fun attachAudio(session: AudioSession?, metadata: PlaybackMetadata?, delayMs: Int?) {
        session ?: return
        session.titleScoped = key(metadata) != null
        if (session.titleScoped && delayMs != null) session.restoreTitleDelay(delayMs)
    }

    fun capture(context: Context, metadata: PlaybackMetadata?, handle: PlaybackHandle?, output: PlayerVideoSettings?) {
        if (handle == null) return
        val values = layerValues(context, metadata).takeIf { it.isNotEmpty() } ?: return
        val target = rememberLayer(context, metadata) ?: return
        val index = values.indexOfFirst { it.first.key == target.key }.coerceAtLeast(0)
        val layer = values[index].first
        val stored = values[index].second
        val titleTarget = layer.scope == VideoSettingsScope.TITLE
        val preferences = videoImagePreferences(context)
        var next = stored ?: TitleVideoSettings()
        // Only what differs from everything this scope inherits (broader scopes, width profile, defaults) is kept.
        val inherited = mergeVideoScopes(values.drop(index + 1).map { it.second })
        val profile = ResolutionVideoProfileStore.load(context, metadata)
        val full = titleTarget && next.snapshot // Keep old explicit snapshots compatible; new edits are automatic overrides.
        val live = metadata?.isLive == true
        when (handle) {
            is MpvPlaybackController -> {
                next = next.copy(
                    mpv = handle.imageOptions.takeIf { full || it != (inherited?.mpv ?: profile?.mpv ?: preferences.mpvImageOptions) },
                    mpvDeinterlaceMode = handle.deinterlaceModeKey.takeIf { full || it != (inherited?.mpvDeinterlaceMode ?: profile?.mpvDeinterlaceMode ?: if (live) com.ambient.tv.ui.player.backend.tvheadendMpvDeinterlaceMode(preferences.tvheadendDeinterlaceModeKey) else "off") },
                    mpvDeinterlaceFilter = handle.deinterlaceFilterKey.takeIf { full || it != (inherited?.mpvDeinterlaceFilter ?: profile?.mpvDeinterlaceFilter ?: if (live) preferences.tvheadendMpvDeinterlaceFilterKey else "auto") },
                    mpvOverloadContinueSignature = if (titleTarget) handle.overloadContinueSignature else null,
                )
            }
            is VlcPlaybackController -> {
                next = next.copy(
                    vlc = handle.imageOptions.takeIf { full || it != (inherited?.vlc ?: profile?.vlc ?: preferences.vlcImageOptions) },
                    vlcDeinterlaceMode = handle.deinterlaceMode.takeIf { full || it != (inherited?.vlcDeinterlaceMode ?: profile?.vlcDeinterlaceMode ?: if (live) preferences.tvheadendDeinterlaceModeKey else "off") },
                    vlcDeinterlaceFilter = handle.deinterlaceFilter.takeIf { full || it != (inherited?.vlcDeinterlaceFilter ?: profile?.vlcDeinterlaceFilter ?: if (live) preferences.tvheadendDeinterlaceFilterKey else "auto") },
                    vlcOverloadContinueSignature = if (titleTarget) handle.overloadContinueSignature else null,
                )
            }
            is Media3PlaybackHandle -> next = next.copy(
                media3 = Media3ImageProcessing.get(context, handle.player).options.takeIf { full || it != (inherited?.media3 ?: profile?.media3 ?: preferences.media3ImageOptions) },
            )
            else -> Unit
        }
        val audio = when (handle) {
            is MpvPlaybackController -> handle.audioSession
            is VlcPlaybackController -> handle.audioSession
            is Media3PlaybackHandle -> Media3AudioProcessing.session(handle.player)
            else -> null
        }
        if (full) next = next.copy(audio = audio?.displaySettings)
        else if (audio != null) next = next.copy(audio = audio.settings.takeIf {
            it != (inherited?.audio ?: profile?.audio ?: preferences.audioSettings).normalized().copy(performanceHint = false)
        })
        if (audio != null && audio.titleScoped) {
            next = next.copy(audioDelayMs = audio.titleDelayMs?.takeIf { it != (inherited?.audioDelayMs ?: audio.settings.currentForRoute(audio.route.profile).delayMs) })
        }
        if (handle is SubtitleDelayTarget) next = next.copy(subtitleDelayMs = handle.subtitleDelayMs.takeIf { it != (inherited?.subtitleDelayMs ?: 0) })
        // Output/decoder only when changed in this session; otherwise the stored choice stays.
        // The player's automatic HDR "direct" is no choice: the stored output stays as it was.
        output?.let { chosen ->
            val defaults = PlayerVideoSettings.from(preferences).withResolution(profile).withTitle(inherited)
            next = next.copy(
                mpvOutput = titleMpvOutputToStore(next.mpvOutput, chosen, defaults.mpvOutput, full),
                mpvDecoder = chosen.mpvDecoder.takeIf { full || it != defaults.mpvDecoder },
                vlcOutput = chosen.vlcOutput.takeIf { full || it != defaults.vlcOutput },
            )
        }
        // Polling unchanged settings must not rewrite storage or change title eviction order.
        if (next.copy(savedAt = 0L) == (stored ?: TitleVideoSettings()).copy(savedAt = 0L)) return
        write(preferences, metadata, layer, next.copy(savedAt = System.currentTimeMillis()))
        // The broader scope now carries these choices: narrower scopes of this title stop masking them.
        values.take(index).forEach { (narrower, value) ->
            value?.let { write(preferences, metadata, narrower, it.withoutFieldsOf(next)) }
        }
    }
}

/** Output/decoder chosen for this title over the global defaults. */
internal fun PlayerVideoSettings.withTitle(title: TitleVideoSettings?): PlayerVideoSettings = if (title == null) this else copy(
    mpvOutput = title.mpvOutput ?: mpvOutput,
    mpvDecoder = title.mpvDecoder ?: mpvDecoder,
    vlcOutput = title.vlcOutput ?: vlcOutput,
)

/**
 * Output stored for a title after a session: the session's choice when it deviates from
 * [defaultOutput] (or always for an old full [snapshot]). The player's automatic HDR "direct"
 * ([PlayerVideoSettings.automaticHdrDirect]) is no choice: [stored] stays as it was.
 */
internal fun titleMpvOutputToStore(stored: String?, chosen: PlayerVideoSettings, defaultOutput: String, snapshot: Boolean): String? =
    if (chosen.automaticHdrDirect) stored else chosen.mpvOutput.takeIf { snapshot || it != defaultOutput }
