@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import kotlinx.coroutines.flow.update
import com.ambient.tv.data.local.TitleVideoSettings
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController

/**
 * Remembers picture/filter choices per title (source + id) and restores them when the same title
 * plays again. Captured during playback and flushed before replacement/stop, including "reduce"
 * after an overload. Live channels are scoped by server and channel; trailers are not remembered.
 */
internal object TitleVideoSettingsStore {
    // Nesting depth of profile loads; atomic because the polling capture thread reads it.
    private val sessionRendererSelection = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Bumped on every stored change (capture, panel, forget), so an open panel re-reads the layers
     * instead of showing what it read when it opened.
     */
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private fun changed() { revision.update { it + 1 } }

    /** Serializes read-modify-write of title/scope storage (main thread, polling capture, stop). */
    private val lock = Any()

    /** Loading/resetting a profile must not recreate a removed per-video renderer override. */
    fun withSessionRendererSelection(action: () -> Unit) {
        sessionRendererSelection.incrementAndGet()
        try { action() } finally { sessionRendererSelection.decrementAndGet() }
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

    // "Remember changes for": chosen in the player; carried to further episodes of the same series
    // (season choice follows into the episode's season), other titles start with the title itself.
    private val rememberChoices = VideoScopeChoices()

    fun layers(context: Context, metadata: PlaybackMetadata?): List<VideoScopeLayer> =
        videoScopeLayers(key(metadata), metadata?.source.orEmpty(), metadata?.videoScope.takeIf { metadata?.isLive != true },
            videoImagePreferences(context).scopedVideoSettings)

    fun rememberLayer(context: Context, metadata: PlaybackMetadata?): VideoScopeLayer? {
        val layers = layers(context, metadata)
        return layers.getOrNull(rememberChoices.index(layers))
    }

    fun setRememberLayer(context: Context, metadata: PlaybackMetadata?, layer: VideoScopeLayer) {
        rememberChoices.choose(layers(context, metadata), layer)
        changed()
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
            if (updated != all) { preferences.titleVideoSettings = updated; changed() }
        } else {
            val all = preferences.scopedVideoSettings
            val updated = TitleVideoSettings.putScoped(all, layer.key, value)
            if (updated != all) { preferences.scopedVideoSettings = updated; changed() }
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
        if (sessionRendererSelection.get() > 0) return
        synchronized(lock) {
            val values = layerValues(context, metadata).takeIf { it.isNotEmpty() } ?: return
            val index = rememberChoices.index(values.map { it.first })
            // The target stores the choice (null = default); narrower scopes drop their own player either way.
            writeChanged(context, metadata, values, rememberRendererInScopes(values.map { it.second }, index, renderer))
        }
    }

    /** Writes every layer whose content changed; unchanged ones keep storage and eviction order. */
    private fun writeChanged(
        context: Context, metadata: PlaybackMetadata?, values: List<Pair<VideoScopeLayer, TitleVideoSettings?>>, next: List<TitleVideoSettings?>,
    ) {
        val preferences = videoImagePreferences(context)
        val now = System.currentTimeMillis()
        values.forEachIndexed { i, (layer, before) ->
            val after = next[i] ?: TitleVideoSettings()
            if (after.copy(savedAt = 0L) == (before ?: TitleVideoSettings()).copy(savedAt = 0L)) return@forEachIndexed
            write(preferences, metadata, layer, after.copy(savedAt = now))
        }
    }

    /** Panel action: drop everything remembered for this title. */
    fun forget(context: Context, metadata: PlaybackMetadata?) {
        val key = key(metadata) ?: return
        val preferences = videoImagePreferences(context)
        val all = preferences.titleVideoSettings
        val keys = setOfNotNull(key, legacyKey(metadata))
        if (keys.any { it in all }) { preferences.titleVideoSettings = all - keys; changed() }
    }

    /** Panel action: drop one season/series/collection scope. */
    fun forgetScope(context: Context, layer: VideoScopeLayer) {
        if (layer.scope == VideoSettingsScope.TITLE) return
        val preferences = videoImagePreferences(context)
        val all = preferences.scopedVideoSettings
        if (layer.key in all) { preferences.scopedVideoSettings = all - layer.key; changed() }
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
        synchronized(lock) { captureLocked(context, metadata, handle, output) }
    }

    private fun captureLocked(context: Context, metadata: PlaybackMetadata?, handle: PlaybackHandle, output: PlayerVideoSettings?) {
        val values = layerValues(context, metadata).takeIf { it.isNotEmpty() } ?: return
        val index = rememberChoices.index(values.map { it.first })
        val titleTarget = index == 0
        val preferences = videoImagePreferences(context)
        val profile = ResolutionVideoProfileStore.load(context, metadata)
        // Keep old explicit snapshots compatible; new edits are automatic overrides.
        val full = titleTarget && values[0].second?.snapshot == true
        val live = metadata?.isLive == true
        val observed = mutableListOf<ObservedVideoField>()
        fun observe(field: VideoScopeField, value: Any?, default: Any?) { observed += ObservedVideoField(field, value, default) }
        var mpvSignature: String? = null
        var vlcSignature: String? = null
        when (handle) {
            is MpvPlaybackController -> {
                observe(VideoScopeFields.MPV, handle.imageOptions, profile?.mpv ?: preferences.mpvImageOptions)
                observe(VideoScopeFields.MPV_DEINTERLACE_MODE, handle.deinterlaceModeKey, profile?.mpvDeinterlaceMode
                    ?: if (live) com.ambient.tv.ui.player.backend.tvheadendMpvDeinterlaceMode(preferences.tvheadendDeinterlaceModeKey) else "off")
                observe(VideoScopeFields.MPV_DEINTERLACE_FILTER, handle.deinterlaceFilterKey, profile?.mpvDeinterlaceFilter
                    ?: if (live) preferences.tvheadendMpvDeinterlaceFilterKey else "auto")
                mpvSignature = handle.overloadContinueSignature
            }
            is VlcPlaybackController -> {
                observe(VideoScopeFields.VLC, handle.imageOptions, profile?.vlc ?: preferences.vlcImageOptions)
                observe(VideoScopeFields.VLC_DEINTERLACE_MODE, handle.deinterlaceMode, profile?.vlcDeinterlaceMode
                    ?: if (live) preferences.tvheadendDeinterlaceModeKey else "off")
                observe(VideoScopeFields.VLC_DEINTERLACE_FILTER, handle.deinterlaceFilter, profile?.vlcDeinterlaceFilter
                    ?: if (live) preferences.tvheadendDeinterlaceFilterKey else "auto")
                vlcSignature = handle.overloadContinueSignature
            }
            is Media3PlaybackHandle -> observe(VideoScopeFields.MEDIA3, Media3ImageProcessing.get(context, handle.player).options,
                profile?.media3 ?: preferences.media3ImageOptions)
            else -> Unit
        }
        val audio = when (handle) {
            is MpvPlaybackController -> handle.audioSession
            is VlcPlaybackController -> handle.audioSession
            is Media3PlaybackHandle -> Media3AudioProcessing.session(handle.player)
            else -> null
        }
        if (audio != null) {
            observe(VideoScopeFields.AUDIO, if (full) audio.displaySettings else audio.settings, profile?.audio ?: preferences.audioSettings)
            // Delays are per title (index 0) whatever scope is chosen: they belong to this file's sync.
            if (audio.titleScoped) observe(VideoScopeFields.AUDIO_DELAY, audio.titleDelayMs, audio.settings.currentForRoute(audio.route.profile).delayMs)
        }
        if (handle is SubtitleDelayTarget) observe(VideoScopeFields.SUBTITLE_DELAY, handle.subtitleDelayMs, 0)
        // Output/decoder only when changed in this session; otherwise the stored choice stays.
        // The player's automatic HDR "direct" is no choice: the stored output stays as it was.
        output?.let { chosen ->
            val defaults = PlayerVideoSettings.from(preferences).withResolution(profile)
            if (!chosen.automaticHdrDirect) observe(VideoScopeFields.MPV_OUTPUT, chosen.mpvOutput, defaults.mpvOutput)
            observe(VideoScopeFields.MPV_DECODER, chosen.mpvDecoder, defaults.mpvDecoder)
            observe(VideoScopeFields.VLC_OUTPUT, chosen.vlcOutput, defaults.vlcOutput)
        }
        val next = captureIntoScopes(values.map { it.second }, index, observed, full).toMutableList()
        // Overload signatures are title-only.
        if (handle is MpvPlaybackController || handle is VlcPlaybackController) {
            val title = next[0] ?: TitleVideoSettings()
            val signed = if (handle is MpvPlaybackController) title.copy(mpvOverloadContinueSignature = mpvSignature)
                else title.copy(vlcOverloadContinueSignature = vlcSignature)
            next[0] = signed.takeUnless { next[0] == null && it.isEmpty }
        }
        // Polling unchanged settings must not rewrite storage or change title eviction order.
        writeChanged(context, metadata, values, next)
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
