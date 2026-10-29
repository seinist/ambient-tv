package com.ambient.tv.ui.player

internal enum class PlayerTool { AB_REPEAT, FRAME_STEP, BOOKMARKS, LYRICS, GAPLESS, SCREENSHOT }

/** Categories in the same order as the tools panel, including settings that need no player handle. */
internal fun availablePlayerTools(
    hasVideo: Boolean,
    isLive: Boolean,
    hasHandle: Boolean,
    hasBookmarkKey: Boolean,
): List<PlayerTool> = buildList {
    if (hasHandle && !isLive) {
        add(PlayerTool.AB_REPEAT)
        if (hasVideo) add(PlayerTool.FRAME_STEP)
        if (hasBookmarkKey) add(PlayerTool.BOOKMARKS)
    }
    if (!hasVideo && !isLive) {
        add(PlayerTool.LYRICS)
        add(PlayerTool.GAPLESS)
    }
    if (hasVideo) add(PlayerTool.SCREENSHOT)
}
