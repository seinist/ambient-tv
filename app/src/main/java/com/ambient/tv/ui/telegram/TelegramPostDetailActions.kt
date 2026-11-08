package com.ambient.tv.ui.telegram

import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind

internal enum class TelegramPostNativeAction { PLAY, PHOTO }

internal data class TelegramPostDetailActions(
    val native: TelegramPostNativeAction?,
    val primaryYouTubeId: String?,
    val linkedYouTubeIds: List<String>,
) {
    val hasPrimaryAction: Boolean get() = native != null || primaryYouTubeId != null
}

/**
 * The original post has no selected YouTube link. Only a single unambiguous link can be its
 * main action; several links stay separate choices. A playable attachment always stays primary.
 */
internal fun telegramPostDetailActions(media: TelegramMedia): TelegramPostDetailActions {
    val native = when {
        !media.playableOnTelegram -> null
        media.kind == TelegramMediaKind.PHOTO -> TelegramPostNativeAction.PHOTO
        else -> TelegramPostNativeAction.PLAY
    }
    val links = media.youtubeIds
    val primaryYouTube = if (native == null) links.singleOrNull() else null
    return TelegramPostDetailActions(
        native = native,
        primaryYouTubeId = primaryYouTube,
        linkedYouTubeIds = if (primaryYouTube != null) emptyList() else links,
    )
}
