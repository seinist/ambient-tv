package com.ambient.tv.data.telegram

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ambient.tv.BuildConfig
import com.ambient.tv.data.files.MediaFileServer
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.ui.search.telegramSearchGroups
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TelegramSearchTest {
    private fun media(chatId: Long, messageId: Int, kind: TelegramMediaKind, protectedContent: Boolean = false) =
        TelegramMedia(chatId, messageId, "Channel $chatId", "Post $messageId", kind, protectedContent = protectedContent)

    @Test fun searchNeedsNativeBuildEnabledToggleAndLogin() {
        assertTrue(telegramSearchAvailable(nativeBuild = true, enabled = true, authorized = true))
        assertFalse(telegramSearchAvailable(nativeBuild = false, enabled = true, authorized = true))
        assertFalse(telegramSearchAvailable(nativeBuild = true, enabled = false, authorized = true))
        assertFalse(telegramSearchAvailable(nativeBuild = true, enabled = true, authorized = false))
    }

    @Test fun resultsKeepOnlyPlayableVideoAndAudioFromSubscribedVisibleChannels() {
        val video = media(1, 1, TelegramMediaKind.VIDEO)
        val audio = media(1, 2, TelegramMediaKind.AUDIO)
        val candidates = listOf(
            video, audio, video,
            media(1, 3, TelegramMediaKind.PHOTO),
            media(1, 4, TelegramMediaKind.VIDEO, protectedContent = true),
            TelegramMedia(1, 5, "c", "yt", TelegramMediaKind.YOUTUBE, youtubeVideoId = "dQw4w9WgXcQ"),
            media(2, 6, TelegramMediaKind.VIDEO), // not subscribed
            media(3, 7, TelegramMediaKind.VIDEO), // hidden or left
        )
        assertEquals(listOf(video, audio), telegramSearchResults(candidates, setOf(1L, 3L), setOf(3L), 10))
        assertEquals(listOf(video), telegramSearchResults(candidates, setOf(1L), emptySet(), 1))
        // null: already restricted to subscribed chats by the backend.
        assertEquals(3, telegramSearchResults(candidates, null, setOf(3L), 10).size)
    }

    @Test fun floodWaitSecondsAreReadFromRpcErrors() {
        assertEquals(17, telegramFloodWaitSeconds("RPC 420: FLOOD_WAIT_17"))
        assertEquals(5, telegramFloodWaitSeconds("RPC 420: FLOOD_PREMIUM_WAIT_5"))
        assertNull(telegramFloodWaitSeconds("RPC 400: SEARCH_QUERY_EMPTY"))
        assertNull(telegramFloodWaitSeconds(null))
        assertEquals(17, TelegramFloodWaitException(17).seconds)
    }

    @Test fun searchGroupsSplitVideosAndAudio() {
        val groups = telegramSearchGroups(listOf(media(1, 1, TelegramMediaKind.AUDIO).toMediaItem(),
            media(1, 2, TelegramMediaKind.VIDEO).toMediaItem()))
        assertEquals(listOf(com.ambient.tv.R.string.search_telegram_videos, com.ambient.tv.R.string.search_telegram_audio),
            groups.map { it.first })
        assertEquals("telegram:1:2", groups.first().second.single().ratingKey)
        assertTrue(telegramSearchGroups(emptyList()).isEmpty())
    }

    @Test fun clientWithoutSearchSupportReturnsNothing() = runBlocking {
        val port = object : TelegramClientPort {
            override val accountName = "test"
            override suspend fun channels(cursor: String?, limit: Int) = TelegramChannelPage(emptyList())
            override suspend fun browse(section: String, cursor: String?, limit: Int) = TelegramMediaPage(emptyList())
            override suspend fun media(chatId: Long, messageId: Int): TelegramMedia? = null
            override suspend fun mediaPart(chatId: Long, messageId: Int, offset: Long) = ByteArray(0)
        }
        assertTrue(port.search("news").isEmpty())
    }

    @Test fun repositoryWithoutBackendOrLoginReturnsNothing() = runBlocking {
        val prefs = AppPreferences(memoryPreferences()).apply { telegramEnabled = true }
        val repository = TelegramRepository(ApplicationProvider.getApplicationContext(),
            TelegramDiskCache(ApplicationProvider.getApplicationContext()), prefs, MediaFileServer(),
            TokenStore(memoryPreferences(), prefs))
        assertFalse(repository.available)
        assertTrue(repository.search("news").isEmpty())
        // The plain build never offers Telegram search.
        if (!BuildConfig.MONOGRAM_NATIVE) assertFalse(telegramSearchAvailable(BuildConfig.MONOGRAM_NATIVE, true, true))
    }
}
