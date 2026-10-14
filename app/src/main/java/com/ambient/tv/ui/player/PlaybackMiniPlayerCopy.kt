package com.ambient.tv.ui.player

internal data class MiniPlayerCopy(
    val title: String,
    val subtitle: String,
)

/** Headline is the file or series name; the second line is the episode title, otherwise blank. */
internal fun miniPlayerCopy(
    mediaType: String,
    title: String,
    grandparentTitle: String?,
): MiniPlayerCopy {
    val series = grandparentTitle?.takeIf { it.isNotBlank() }
    if (!mediaType.equals("episode", ignoreCase = true) || series == null) {
        return MiniPlayerCopy(title = title, subtitle = "")
    }
    val episode = title.takeIf { it.isNotBlank() && !it.equals(series, ignoreCase = true) }.orEmpty()
    return MiniPlayerCopy(title = series, subtitle = episode)
}
