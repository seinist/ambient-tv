package com.ambient.tv.ui.player

import com.ambient.tv.data.local.ResolutionVideoProfile
import com.ambient.tv.data.local.TitleVideoSettings

/** Where a player change can be remembered, narrowest first. */
enum class VideoSettingsScope { TITLE, SEASON, SERIES, COLLECTION }

/** One storable scope of the playing title; [label] is the season/series/collection name if known. */
data class VideoScopeLayer(val scope: VideoSettingsScope, val key: String, val label: String? = null)

/** Origin of an effective setting, shown in the player: scope, width profile or the general default. */
enum class VideoSettingOrigin { TITLE, SEASON, SERIES, COLLECTION, RESOLUTION, DEFAULT }

data class VideoSettingOrigins(
    val renderer: VideoSettingOrigin,
    val picture: VideoSettingOrigin,
    val deinterlace: VideoSettingOrigin,
    val output: VideoSettingOrigin,
    val audio: VideoSettingOrigin,
    val delay: VideoSettingOrigin,
)

/** Length-delimited server identity: IDs may contain ':' (e.g. "j:…"), servers must not collide. */
internal fun scopedVideoKey(kind: String, source: String, serverId: String?, id: String): String {
    val server = serverId?.takeIf { it.isNotBlank() }.orEmpty()
    return "$kind:$source:${server.length}:$server:$id"
}

/**
 * Narrow to broad: title, season, series, then collections (most recently saved first, so with
 * several collections the newest saved one wins).
 */
internal fun videoScopeLayers(
    titleKey: String?, source: String, scope: PlaybackVideoScope?, scoped: Map<String, TitleVideoSettings>,
): List<VideoScopeLayer> = buildList {
    titleKey ?: return@buildList
    add(VideoScopeLayer(VideoSettingsScope.TITLE, titleKey))
    scope ?: return@buildList
    scope.seasonId?.takeIf { it.isNotBlank() && scope.episode }?.let {
        add(VideoScopeLayer(VideoSettingsScope.SEASON, scopedVideoKey("season", source, scope.serverId, it), scope.seasonLabel))
    }
    scope.seriesId?.takeIf { it.isNotBlank() && scope.episode }?.let {
        add(VideoScopeLayer(VideoSettingsScope.SERIES, scopedVideoKey("series", source, scope.serverId, it), scope.seriesLabel))
    }
    scope.collections.mapNotNull { tag ->
        val id = tag.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        VideoScopeLayer(VideoSettingsScope.COLLECTION, scopedVideoKey("collection", source, scope.serverId, id), tag.name)
    }.distinctBy { it.key }.sortedByDescending { scoped[it.key]?.savedAt ?: Long.MIN_VALUE }.forEach(::add)
}

/** Field-wise merge of [values] (narrow to broad); null when no scope holds anything. */
internal fun mergeVideoScopes(values: List<TitleVideoSettings?>): TitleVideoSettings? =
    values.filterNotNull().reduceOrNull { narrow, broad -> narrow.inheritFrom(broad) }

internal fun videoSettingOrigins(
    layers: List<Pair<VideoScopeLayer, TitleVideoSettings?>>, resolution: ResolutionVideoProfile?,
): VideoSettingOrigins {
    fun origin(inLayer: (TitleVideoSettings) -> Boolean, inResolution: (ResolutionVideoProfile) -> Boolean): VideoSettingOrigin {
        val layer = layers.firstOrNull { (_, value) -> value != null && inLayer(value) }?.first
        if (layer != null) return when (layer.scope) {
            VideoSettingsScope.TITLE -> VideoSettingOrigin.TITLE
            VideoSettingsScope.SEASON -> VideoSettingOrigin.SEASON
            VideoSettingsScope.SERIES -> VideoSettingOrigin.SERIES
            VideoSettingsScope.COLLECTION -> VideoSettingOrigin.COLLECTION
        }
        return if (resolution != null && inResolution(resolution)) VideoSettingOrigin.RESOLUTION else VideoSettingOrigin.DEFAULT
    }
    return VideoSettingOrigins(
        renderer = origin({ it.rendererKey != null }, { true }),
        picture = origin({ it.mpv != null || it.vlc != null || it.media3 != null }, { it.mpv != null || it.vlc != null || it.media3 != null }),
        deinterlace = origin(
            { it.mpvDeinterlaceMode != null || it.mpvDeinterlaceFilter != null || it.vlcDeinterlaceMode != null || it.vlcDeinterlaceFilter != null },
            { it.mpvDeinterlaceMode != null || it.mpvDeinterlaceFilter != null || it.vlcDeinterlaceMode != null || it.vlcDeinterlaceFilter != null }),
        output = origin({ it.mpvOutput != null || it.mpvDecoder != null || it.vlcOutput != null },
            { it.mpvOutput != null || it.mpvDecoder != null || it.vlcOutput != null }),
        audio = origin({ it.audio != null }, { it.audio != null }),
        delay = origin({ it.audioDelayMs != null || it.subtitleDelayMs != null }, { false }),
    )
}

/**
 * One rememberable field of [TitleVideoSettings]. [normalize] maps a value to its comparable form
 * (audio settings drop transient hints). [titleOnly] fields (A/V and subtitle delay) are always kept
 * on the title, whatever scope "remember for" points to.
 */
internal class VideoScopeField(
    val name: String,
    val get: (TitleVideoSettings) -> Any?,
    val set: (TitleVideoSettings, Any?) -> TitleVideoSettings,
    val titleOnly: Boolean = false,
    val normalize: (Any?) -> Any? = { it },
)

internal object VideoScopeFields {
    val MPV = VideoScopeField("mpv", { it.mpv }, { s, v -> s.copy(mpv = v as com.ambient.tv.data.local.MpvImageOptions?) })
    val MPV_DEINTERLACE_MODE = VideoScopeField("mpvDeinterlaceMode", { it.mpvDeinterlaceMode }, { s, v -> s.copy(mpvDeinterlaceMode = v as String?) })
    val MPV_DEINTERLACE_FILTER = VideoScopeField("mpvDeinterlaceFilter", { it.mpvDeinterlaceFilter }, { s, v -> s.copy(mpvDeinterlaceFilter = v as String?) })
    val VLC = VideoScopeField("vlc", { it.vlc }, { s, v -> s.copy(vlc = v as com.ambient.tv.data.local.VlcImageOptions?) })
    val VLC_DEINTERLACE_MODE = VideoScopeField("vlcDeinterlaceMode", { it.vlcDeinterlaceMode }, { s, v -> s.copy(vlcDeinterlaceMode = v as String?) })
    val VLC_DEINTERLACE_FILTER = VideoScopeField("vlcDeinterlaceFilter", { it.vlcDeinterlaceFilter }, { s, v -> s.copy(vlcDeinterlaceFilter = v as String?) })
    val MEDIA3 = VideoScopeField("media3", { it.media3 }, { s, v -> s.copy(media3 = v as com.ambient.tv.data.local.Media3ImageOptions?) })
    val MPV_OUTPUT = VideoScopeField("mpvOutput", { it.mpvOutput }, { s, v -> s.copy(mpvOutput = v as String?) })
    val MPV_DECODER = VideoScopeField("mpvDecoder", { it.mpvDecoder }, { s, v -> s.copy(mpvDecoder = v as String?) })
    val VLC_OUTPUT = VideoScopeField("vlcOutput", { it.vlcOutput }, { s, v -> s.copy(vlcOutput = v as String?) })
    val AUDIO = VideoScopeField("audio", { it.audio }, { s, v -> s.copy(audio = v as com.ambient.tv.data.local.AudioSettings?) },
        normalize = { (it as com.ambient.tv.data.local.AudioSettings?)?.normalized()?.copy(performanceHint = false) })
    val AUDIO_DELAY = VideoScopeField("audioDelayMs", { it.audioDelayMs }, { s, v -> s.copy(audioDelayMs = v as Int?) }, titleOnly = true)
    val SUBTITLE_DELAY = VideoScopeField("subtitleDelayMs", { it.subtitleDelayMs }, { s, v -> s.copy(subtitleDelayMs = v as Int?) }, titleOnly = true)
}

/** What the player shows for one field now, and what it would show without any title/scope value. */
internal data class ObservedVideoField(val field: VideoScopeField, val value: Any?, val default: Any?)

/**
 * Stores [observed] into [layers] (narrow to broad, index 0 = title) for "remember for" [target].
 *
 * - Title target: every observed value that differs from what the title inherits is kept on the title
 *   ([fullSnapshot]: old explicit snapshots keep every value).
 * - Broader target: only fields whose observed value differs from what was effective when playback started
 *   ([baseline]: the stored layers at session start, i.e. changed in this session) move to the scope; the
 *   scope drops a value equal to what it inherits, and narrower scopes stop masking exactly those fields.
 *   Changes the automatic capture already put on the title during this session move along. Title overrides
 *   from earlier sessions that were not touched never spread. A field changed back to its start value gets
 *   its start values again in the target and every narrower layer.
 * - [VideoScopeField.titleOnly] fields always use the title rule on index 0.
 *
 * Returns the new layer values (same size and order); unchanged layers are returned as given.
 */
internal fun captureIntoScopes(
    layers: List<TitleVideoSettings?>, target: Int, observed: List<ObservedVideoField>, fullSnapshot: Boolean = false,
    baseline: List<TitleVideoSettings?> = layers,
): List<TitleVideoSettings?> {
    if (layers.isEmpty()) return layers
    val index = target.coerceIn(0, layers.lastIndex)
    val start = baseline.takeIf { it.size == layers.size } ?: layers
    val result = layers.toMutableList()
    fun firstValue(field: VideoScopeField, from: Int, default: Any?, values: List<TitleVideoSettings?> = layers): Any? =
        (from..values.lastIndex).firstNotNullOfOrNull { i -> values[i]?.let(field.get) } ?: default
    for ((field, value, default) in observed) {
        val at = if (field.titleOnly) 0 else index
        val inherited = field.normalize(firstValue(field, at + 1, default))
        if (at == 0) {
            val stored = value.takeIf { (fullSnapshot && !field.titleOnly) || field.normalize(it) != inherited }
            result[0] = field.set(result[0] ?: TitleVideoSettings(), stored)
            continue
        }
        val effectiveAtStart = field.normalize(firstValue(field, 0, default, start))
        if (field.normalize(value) == effectiveAtStart) {
            // Not changed in this session: nothing spreads; layers this session touched get their start values back.
            for (i in 0..at) {
                val wanted = start[i]?.let(field.get)
                if (result[i]?.let(field.get) != wanted) result[i] = field.set(result[i] ?: TitleVideoSettings(), wanted)
            }
            continue
        }
        result[at] = field.set(result[at] ?: TitleVideoSettings(), value.takeIf { field.normalize(it) != inherited })
        for (i in 0 until at) result[i] = result[i]?.let { field.set(it, null) }
    }
    // Untouched layers keep their identity; a layer left without values becomes null (removed when written).
    return result.mapIndexed { i, value ->
        val before = layers[i]
        when {
            value == null || value.isEmpty -> null
            before != null && value.copy(savedAt = 0L) == before.copy(savedAt = 0L) -> before
            else -> value
        }
    }
}

/**
 * Player choice for "remember for" [target]: the scope stores [renderer] (null = follow the default) and
 * every narrower scope drops its own player, so the broader choice (or its reset) takes effect here.
 */
internal fun rememberRendererInScopes(layers: List<TitleVideoSettings?>, target: Int, renderer: String?): List<TitleVideoSettings?> {
    if (layers.isEmpty()) return layers
    val index = target.coerceIn(0, layers.lastIndex)
    val key = renderer?.takeIf { it in setOf("auto", "media3", "vlc", "mpv") }
    return layers.mapIndexed { i, value ->
        when {
            i == index -> (value ?: TitleVideoSettings()).copy(rendererKey = key).let { next ->
                if (value == null && next.isEmpty) null else if (value != null && next.copy(savedAt = 0L) == value.copy(savedAt = 0L)) value else next
            }
            i < index && value?.rendererKey != null -> value.copy(rendererKey = null)
            else -> value
        }
    }
}

/**
 * Switching "remember for" to the broader [target]: a player chosen in this session (the effective player
 * differs from [baseline], the layers at session start) and stored on a narrower layer moves to the target,
 * and narrower layers drop theirs. Anything else stays as it is.
 */
internal fun sessionRendererInScopes(
    layers: List<TitleVideoSettings?>, baseline: List<TitleVideoSettings?>, target: Int,
): List<TitleVideoSettings?> {
    if (layers.isEmpty() || target <= 0) return layers
    val index = target.coerceAtMost(layers.lastIndex)
    val now = layers.firstNotNullOfOrNull { it?.rendererKey }
    if (now == baseline.firstNotNullOfOrNull { it?.rendererKey }) return layers
    if ((0 until index).none { layers[it]?.rendererKey != null }) return layers
    return rememberRendererInScopes(layers, index, now)
}

/** "Remember for" choice made in the player: scope kind plus the exact layer key. */
internal data class VideoScopeChoice(val scope: VideoSettingsScope, val key: String)

/**
 * Index of the layer a [choice] points to: the exact layer when present; a season choice follows the
 * episode into its own season; a series or collection choice applies to every episode that has that layer.
 * Anything else falls back to the title.
 */
internal fun rememberScopeIndex(layers: List<VideoScopeLayer>, choice: VideoScopeChoice?): Int {
    choice ?: return 0
    layers.indexOfFirst { it.key == choice.key }.takeIf { it >= 0 }?.let { return it }
    if (choice.scope == VideoSettingsScope.SEASON) layers.indexOfFirst { it.scope == VideoSettingsScope.SEASON }.takeIf { it >= 0 }?.let { return it }
    return 0
}

/** Choices are carried per series (episodes) or per title (everything else). */
internal fun rememberScopeAnchor(layers: List<VideoScopeLayer>): String? =
    layers.firstOrNull { it.scope == VideoSettingsScope.SERIES }?.key ?: layers.firstOrNull()?.key

/** Stored form of a choice: "<SCOPE>:<layer key>" (scope names contain no ':'). */
internal fun encodeVideoScopeChoice(choice: VideoScopeChoice): String = "${choice.scope.name}:${choice.key}"

internal fun decodeVideoScopeChoice(value: String): VideoScopeChoice? {
    val split = value.indexOf(':').takeIf { it > 0 } ?: return null
    val scope = VideoSettingsScope.entries.firstOrNull { it.name == value.substring(0, split) } ?: return null
    val key = value.substring(split + 1).takeIf { it.isNotBlank() } ?: return null
    return if (scope == VideoSettingsScope.TITLE) null else VideoScopeChoice(scope, key)
}

/**
 * "Remember for" choices, thread-safe and bounded (least recently used dropped). A series/season choice
 * carries over to further episodes of the same series; other titles start with the title itself.
 * [initial] restores saved choices; [onChange] receives every change so the choice survives a restart.
 */
internal class VideoScopeChoices(
    private val limit: Int = 200,
    initial: Map<String, VideoScopeChoice> = emptyMap(),
    private val onChange: (Map<String, VideoScopeChoice>) -> Unit = {},
) {
    private val choices = object : LinkedHashMap<String, VideoScopeChoice>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoScopeChoice>?) = size > limit
    }

    init { choices.putAll(initial) }

    @Synchronized fun index(layers: List<VideoScopeLayer>): Int =
        rememberScopeIndex(layers, rememberScopeAnchor(layers)?.let { choices[it] })

    @Synchronized fun choose(layers: List<VideoScopeLayer>, layer: VideoScopeLayer) {
        val anchor = rememberScopeAnchor(layers) ?: return
        val before = choices[anchor]
        val next = VideoScopeChoice(layer.scope, layer.key).takeUnless { layer.scope == VideoSettingsScope.TITLE }
        if (next == null) choices.remove(anchor) else choices[anchor] = next
        if (before != next) onChange(LinkedHashMap(choices))
    }

    /** Layer key -> how many series/titles currently remember into it. */
    @Synchronized fun targetCounts(): Map<String, Int> = choices.values.groupingBy { it.key }.eachCount()

    /** Points every choice aimed at [key] back to the title (e.g. after that layer was deleted). */
    @Synchronized fun removeTarget(key: String) {
        if (choices.values.none { it.key == key }) return
        choices.values.removeAll { it.key == key }
        onChange(LinkedHashMap(choices))
    }

    @Synchronized fun clear() {
        if (choices.isEmpty()) return
        choices.clear()
        onChange(emptyMap())
    }
}

/**
 * One diagnostics line for the start of a title: which layers exist and hold values, where "remember
 * for" points, which width preset applies, and where each effective value comes from.
 */
internal fun videoScopeDiagnosticLine(
    titleKey: String,
    layers: List<Pair<VideoScopeLayer, TitleVideoSettings?>>,
    rememberIndex: Int,
    widthKey: String?,
    matchedWidthKey: String?,
    origins: VideoSettingOrigins,
): String {
    fun name(scope: VideoSettingsScope) = scope.name.lowercase()
    val layerText = layers.joinToString(",") { (layer, value) ->
        "${name(layer.scope)}=${if (value == null || value.isEmpty) "empty" else "set"}"
    }
    val remember = layers.getOrNull(rememberIndex)?.first?.scope?.let(::name) ?: "title"
    val width = when {
        widthKey == null -> "unknown"
        matchedWidthKey == null -> "$widthKey(no preset)"
        matchedWidthKey == widthKey -> "$widthKey(preset)"
        else -> "$widthKey->$matchedWidthKey(similar preset)"
    }
    fun o(origin: VideoSettingOrigin) = origin.name.lowercase()
    return "title=$titleKey layers=[$layerText] remember=$remember width=$width origin: player=${o(origins.renderer)} " +
        "picture=${o(origins.picture)} deinterlace=${o(origins.deinterlace)} output=${o(origins.output)} " +
        "audio=${o(origins.audio)} delay=${o(origins.delay)}"
}
