package com.ambient.tv.ui.telegram

/** Read without consuming: the returning page starts from its own visit and player-loaded pages. */
internal fun prepareTelegramBrowseReturn(
    section: String,
    sourceRevision: Long,
    pending: TelegramPlaybackContext.ReturnSnapshot?,
    includeYouTubeLinks: Boolean = true,
): TelegramBrowseState? {
    val snapshot = pending?.takeIf {
        it.scope == "telegram:$section" && it.sourceRevision == sourceRevision && it.rows.isNotEmpty()
    } ?: return null
    val rows = if (includeYouTubeLinks) snapshot.rows else snapshot.rows.map { row ->
        row.copy(items = row.items.filterNot {
            it.ratingKey.startsWith(com.ambient.tv.data.telegram.TELEGRAM_YOUTUBE_PREFIX)
        })
    }.filter { it.items.isNotEmpty() }
    return TelegramBrowseState(
        section = section,
        available = true,
        rows = rows,
        media = snapshot.media,
        nextCursor = snapshot.target.nextCursor,
        playbackReturnRevision = snapshot.revision,
        playbackReturnKey = snapshot.target.mediaKey,
    )
}
