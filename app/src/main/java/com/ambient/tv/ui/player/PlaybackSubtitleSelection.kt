package com.ambient.tv.ui.player

/** One subtitle selector for native tracks and the app's teletext subtitles. */
internal data class PlaybackSubtitleSelection(
    val options: List<Pair<String, String>>,
    val selectedKey: String,
)

internal fun playbackSubtitleSelection(
    nativeOptions: List<Pair<String, String>>,
    selectedNative: String,
    teletextOptions: List<Pair<String, String>>,
    teletextChoice: String,
    offLabel: String,
): PlaybackSubtitleSelection {
    // VLC uses -1, while Media3 and MPV use off. Keep the key their callback expects.
    val off = nativeOptions.firstOrNull { it.first == "off" || it.first == "-1" }
        ?: ("off" to offLabel)
    val native = listOf(off) + nativeOptions.filterNot { it.first == "off" || it.first == "-1" }
    val teletext = teletextOptions.mapNotNull { (choice, label) ->
        val key = "teletext:$choice"
        if (teletextSubtitleChoice(key) != null) key to label else null
    }.distinctBy { it.first }
    val selected = teletext.firstOrNull { it.first == "teletext:$teletextChoice" }?.first
        ?: native.firstOrNull { it.first == selectedNative }?.first
        ?: off.first
    return PlaybackSubtitleSelection(native + teletext, selected)
}

/** Only namespaced, valid teletext choices may bypass the native track callback. */
internal fun teletextSubtitleChoice(key: String): String? {
    if (!key.startsWith("teletext:")) return null
    val choice = key.removePrefix("teletext:")
    return choice.takeIf { it == "auto" || it.toIntOrNull() in 100..899 }
}
