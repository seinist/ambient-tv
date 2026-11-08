package com.ambient.tv.ui.home

import com.ambient.tv.ui.common.ContentRowData

internal enum class HomePlaybackProvider { TRAILERS, YOUTUBE, TELEGRAM, MEDIA }

/** A Home row, not a similarly named library/feed, owns the queue that its card started. */
internal data class HomePlaybackOrigin(
    val provider: HomePlaybackProvider,
    val focusStateKey: String,
    val scope: String,
    val sessionToken: Long,
    val source: ContentRowData,
)

/** Non-consuming return address, accepted only at a real navigation entry. */
internal data class HomePlaybackReturnRequest(
    val provider: HomePlaybackProvider,
    val scope: String,
    val contextRevision: Long,
    val source: ContentRowData?,
    val target: HomeFocusSnapshot,
    val liveTvSource: HomeTvRow? = null,
) {
    val key: String get() = "${provider.name}:$scope:$contextRevision"
}

/** Regular Plex/Jellyfin/Emby/file playback already has the selected card in Home's snapshot. */
internal fun homeNavigationPlaybackReturn(
    state: HomeUiState,
    returned: com.ambient.tv.ui.common.NavigationPlaybackReturn?,
    preferredRowId: String? = null,
): HomePlaybackReturnRequest? {
    val current = state as? HomeUiState.Loaded ?: return null
    val event = returned ?: return null
    if (event.itemId.startsWith("live-channel:")) {
        val preferred = preferredRowId ?: current.focusedRowId
        val source = current.tvRows.entries.firstOrNull { (key, row) -> key == preferred &&
            row.items.any { event.itemId == "live-channel:${homeLiveTvFocusKey(it)}" } }
            ?: current.tvRows.entries.firstOrNull { (_, row) ->
                row.items.any { event.itemId == "live-channel:${homeLiveTvFocusKey(it)}" } }
            // A zap can leave Home's limited preview. Use the first available channel of
            // its original row; never fetch or invent an unseen channel during navigation.
            ?: current.tvRows.entries.firstOrNull { (key, row) -> key == preferred && row.items.isNotEmpty() }
            ?: current.rowKeys.firstNotNullOfOrNull { key ->
                current.tvRows.entries.firstOrNull { it.key == key && it.value.items.isNotEmpty() }
            }
            ?: return null
        val item = source.value.items.firstOrNull { event.itemId == "live-channel:${homeLiveTvFocusKey(it)}" }
            ?: source.value.items.first()
        return HomePlaybackReturnRequest(HomePlaybackProvider.MEDIA, event.entryId, event.revision,
            source = null, target = HomeFocusSnapshot(homeLiveTvFocusKey(item), source.key), liveTvSource = source.value)
    }
    val row = current.rows.firstOrNull { row -> row.id == (preferredRowId ?: current.focusedRowId) &&
        row.items.any { event.itemId in it.allRatingKeys } }
        ?: current.rows.firstOrNull { row -> row.items.any { event.itemId in it.allRatingKeys } }
        ?: return null
    return HomePlaybackReturnRequest(HomePlaybackProvider.MEDIA, event.entryId, event.revision, row,
        HomeFocusSnapshot(event.itemId, row.id))
}

/** The same first-frame rows, focus and hero remain in place when the entry commits. */
internal fun prepareHomePlaybackReturn(
    current: HomeUiState.Loaded,
    request: HomePlaybackReturnRequest,
): HomeUiState.Loaded? {
    val revision = if (current.playbackReturnToken == request.key) current.playbackReturnRevision
        else current.playbackReturnRevision + 1
    request.liveTvSource?.let { source ->
        val rowId = request.target.rowId ?: return null
        if (rowId !in current.tvRows || rowId !in current.rowKeys) return null
        val item = source.items.firstOrNull { homeLiveTvFocusKey(it) == request.target.ratingKey } ?: return null
        return current.copy(
            tvRows = current.tvRows + (rowId to source),
            focusedRatingKey = homeLiveTvFocusKey(item),
            focusedRowId = rowId,
            hero = item.toHomeHeroContent(),
            loading = false,
            focusRecovery = null,
            initialFocusTarget = HomeInitialFocusTarget.NONE,
            playbackReturnRevision = revision,
            playbackReturnToken = request.key,
        )
    }
    val source = request.source ?: return null
    if (request.target.rowId != source.id) return null
    val item = source.items.firstOrNull { request.target.ratingKey in it.allRatingKeys } ?: return null
    val rows = if (current.rows.any { it.id == source.id }) {
        current.rows.map { if (it.id == source.id) source else it }
    } else current.rows + source
    val rowKeys = current.rowKeys.takeIf { source.id in it } ?: (current.rowKeys + source.id)
    return current.copy(
        rows = rows,
        rowKeys = rowKeys,
        focusedRatingKey = item.ratingKey,
        focusedRowId = source.id,
        hero = item.toHomeHeroContent(),
        loading = false,
        focusRecovery = null,
        initialFocusTarget = HomeInitialFocusTarget.NONE,
        playbackReturnRevision = revision,
        playbackReturnToken = request.key,
    )
}

/** Source IDs may themselves contain colons; keys are matched whole, never split. */
internal fun homeLiveTvFocusKey(item: HomeLiveTvItem): String = "${item.channel.sourceId}:${item.channel.uuid}"

internal fun HomeLiveTvItem.toHomeHeroContent(): HomeHeroContent {
    val currentEvent = event
    fun clock(epoch: Long) = HOME_LIVE_CLOCK_FORMATTER.format(java.time.Instant.ofEpochSecond(epoch))
    return HomeHeroContent(
        key = "livetv:${channel.sourceId}:${channel.uuid}:${currentEvent?.eventId ?: "channel"}",
        title = currentEvent?.title ?: channel.name,
        artworkUrl = currentEvent?.imageUrl ?: channel.iconUrl,
        thumbnailUrl = channel.iconUrl,
        titleLogoUrl = channel.iconUrl,
        artworkIsLogo = currentEvent?.imageUrl == null,
        providerLabel = sourceName,
        metadata = if (currentEvent == null) listOf(sourceName).filter { it.isNotBlank() } else emptyList(),
        imageHeaders = imageHeaders,
        subtitle = currentEvent?.let { it.subtitle?.takeIf(String::isNotBlank) ?: channel.name },
        summary = currentEvent?.let { programme ->
            listOfNotNull(programme.summary, programme.description)
                .firstOrNull { it.isNotBlank() && it.trim() != programme.subtitle?.trim() }
        },
        live = currentEvent?.let {
            HomeHeroLive("${clock(it.startEpochSeconds)}–${clock(it.stopEpochSeconds)}", it.startEpochSeconds, it.stopEpochSeconds)
        },
    )
}

private val HOME_LIVE_CLOCK_FORMATTER: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter
    .ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())

/** Resolve a focus address across Home's regular media and its separate live channel rows. */
internal fun homeFocusRowId(
    rows: List<ContentRowData>,
    tvRows: Map<String, HomeTvRow>,
    focus: HomeFocusSnapshot,
): String? {
    val key = focus.ratingKey ?: return null
    fun contains(rowId: String): Boolean = rows.any { row -> row.id == rowId && row.items.any { key in it.allRatingKeys } } ||
        tvRows[rowId]?.items?.any { homeLiveTvFocusKey(it) == key } == true
    return focus.rowId?.takeIf(::contains)
        ?: rows.firstOrNull { row -> row.items.any { key in it.allRatingKeys } }?.id
        ?: tvRows.entries.firstOrNull { (_, row) -> row.items.any { homeLiveTvFocusKey(it) == key } }?.key
}
