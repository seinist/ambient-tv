package com.ambient.tv.ui.telegram

import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramPostDetailActionsTest {
    private val first = "abcdefghijk"
    private val second = "12345678901"

    private fun post(kind: TelegramMediaKind, protected: Boolean = false, links: List<String> = emptyList()) =
        TelegramMedia(7L, 42, "Channel", "Post", kind,
            protectedContent = protected, linkedYouTubeVideoIds = links)

    @Test fun nativeAttachmentStaysPrimaryAndKeepsEveryLinkedVideo() {
        val actions = telegramPostDetailActions(post(TelegramMediaKind.VIDEO, links = listOf(first, second)))
        assertEquals(TelegramPostNativeAction.PLAY, actions.native)
        assertNull(actions.primaryYouTubeId)
        assertEquals(listOf(first, second), actions.linkedYouTubeIds)
    }

    @Test fun originalPostWithSeveralLinksNeverSilentlySelectsOne() {
        val media = post(TelegramMediaKind.YOUTUBE, links = listOf(first, second))
            .copy(youtubeVideoId = second)
        val actions = telegramPostDetailActions(media)
        assertNull(actions.native)
        assertNull(actions.primaryYouTubeId)
        assertEquals(listOf(first, second), actions.linkedYouTubeIds)
    }

    @Test fun oneLinkIsAnUnambiguousTopActionAndIsNotRepeatedBelow() {
        val actions = telegramPostDetailActions(post(TelegramMediaKind.YOUTUBE, links = listOf(first, first)))
        assertNull(actions.native)
        assertEquals(first, actions.primaryYouTubeId)
        assertEquals(emptyList<String>(), actions.linkedYouTubeIds)
    }

    @Test fun protectedAttachmentCannotBePlayedButItsLinksRemainAvailable() {
        val actions = telegramPostDetailActions(post(TelegramMediaKind.VIDEO, protected = true, links = listOf(first)))
        assertNull(actions.native)
        assertEquals(first, actions.primaryYouTubeId)
        assertNull(telegramPostDetailActions(post(TelegramMediaKind.PHOTO, protected = true)).native)
    }

    @Test fun photoOpensThePhotoViewerEvenWhenItLinksToYouTube() {
        val actions = telegramPostDetailActions(post(TelegramMediaKind.PHOTO, links = listOf(first)))
        assertEquals(TelegramPostNativeAction.PHOTO, actions.native)
        assertNull(actions.primaryYouTubeId)
        assertEquals(listOf(first), actions.linkedYouTubeIds)
    }
}
