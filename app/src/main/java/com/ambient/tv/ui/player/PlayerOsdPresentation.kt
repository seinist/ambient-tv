package com.ambient.tv.ui.player

internal enum class PlayerOsdMode { HIDDEN, COMPACT, EXPANDED }

/** One presentation for startup, interactive controls and feedback after a seek. */
internal data class PlayerOsdPresentation(
    val mode: PlayerOsdMode = PlayerOsdMode.COMPACT,
    val closing: Boolean = false,
    val closingFromExpanded: Boolean = false,
    val seekFeedback: Boolean = false,
) {
    // Back still belongs to the OSD until its closing animation has finished.
    val visible: Boolean get() = mode != PlayerOsdMode.HIDDEN
    val show: Boolean get() = visible && !closing
    val expanded: Boolean get() = mode == PlayerOsdMode.EXPANDED && !closing

    fun reveal(): PlayerOsdPresentation = PlayerOsdPresentation(
        mode = if (expanded) PlayerOsdMode.EXPANDED else PlayerOsdMode.COMPACT,
    )

    fun compact(feedback: Boolean = false): PlayerOsdPresentation =
        PlayerOsdPresentation(seekFeedback = feedback)

    fun expand(): PlayerOsdPresentation = PlayerOsdPresentation(mode = PlayerOsdMode.EXPANDED)

    fun close(): PlayerOsdPresentation = when {
        !visible -> this
        closing -> PlayerOsdPresentation(mode = PlayerOsdMode.HIDDEN)
        else -> PlayerOsdPresentation(closing = true, closingFromExpanded = expanded)
    }

    fun finishClose(): PlayerOsdPresentation =
        if (closing) PlayerOsdPresentation(mode = PlayerOsdMode.HIDDEN) else this
}
