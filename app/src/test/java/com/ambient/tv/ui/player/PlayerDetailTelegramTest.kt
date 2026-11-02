package com.ambient.tv.ui.player

import com.ambient.tv.R
import com.ambient.tv.data.telegram.TelegramChannel
import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.ui.common.MediaSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDetailTelegramTest {

    private val date: (Long) -> String = { "D$it" }
    private val size: (Long) -> String = { "${it / 1_000_000} MB" }

    private fun video(
        title: String = "Neues Video aus dem Studio",
        summary: String? = "Neues Video aus dem Studio\n\nAlle Infos im Beitrag.",
        thumb: String? = "thumb",
        width: Int = 1920,
        height: Int = 1080,
        fileName: String? = "studio.mp4",
        kind: TelegramMediaKind = TelegramMediaKind.VIDEO,
        durationMs: Long = 754_000L,
        fileSize: Long = 245_000_000L,
        publishedAtMs: Long = 1_700_000_000_000L,
    ) = TelegramMedia(
        chatId = 7, messageId = 42, channel = "Kanal A", title = title, kind = kind,
        summary = summary, thumbnailUrl = thumb, durationMs = durationMs, fileSize = fileSize,
        publishedAtMs = publishedAtMs, width = width, height = height, fileName = fileName,
    )

    @Test fun videoShowsChannelDateRuntimePictureSizeFileAndPostText() {
        val detail = playerDetailForTelegram(video(), TelegramChannel(7, "Kanal A", thumbnailUrl = "avatar"), date, size)
        assertEquals(OsdPlayer.MEDIA, detail.osdPlayer)
        assertEquals(MediaSystem.TELEGRAM, detail.system)
        assertEquals("Kanal A", detail.eyebrow)
        assertEquals("Neues Video aus dem Studio", detail.title)
        assertEquals(listOf("D1700000000000", "12min"), detail.meta)
        assertEquals(listOf("Full HD", "1920×1080", "MP4", "245 MB"), detail.chips)
        assertEquals("Neues Video aus dem Studio\n\nAlle Infos im Beitrag.", detail.overview)
        assertEquals(listOf(PlayerDetailLine(R.string.photos_info_file, "studio.mp4")), detail.lines)
        assertEquals("thumb", detail.artworkUrl)
        assertEquals(listOf("avatar"), detail.artworkFallbackUrls)
        assertEquals(PlayerDetailArtShape.WIDE, detail.artShape)
        // Telegram has none of these: their sections stay hidden.
        assertNull(detail.item)
        assertNull(detail.tagline)
        assertTrue(detail.genres.isEmpty())
    }

    @Test fun nothingIsInventedWhenThePostCarriesLittle() {
        val bare = video(title = "Kanal A", summary = null, thumb = null, width = 0, height = 0,
            fileName = null, durationMs = 0, fileSize = 0, publishedAtMs = 0)
        val detail = playerDetailForTelegram(bare, null, date, size)
        assertTrue(detail.meta.isEmpty())
        assertTrue(detail.chips.isEmpty())
        assertTrue(detail.lines.isEmpty())
        assertNull(detail.overview)
        assertNull(detail.artworkUrl)
    }

    @Test fun shortPostTextIsNotRepeatedBelowTheTitle() {
        val detail = playerDetailForTelegram(video(title = "Kurz", summary = "Kurz"), null, date, size)
        assertNull(detail.overview)
        // A long first line is cut in the title, so the full text stays.
        val long = "x".repeat(TELEGRAM_DETAIL_REPEAT_MAX_CHARS + 1)
        assertEquals(long, playerDetailForTelegram(video(title = long, summary = long), null, date, size).overview)
    }

    @Test fun withoutThumbnailTheChannelPictureIsShownAsLogo() {
        val detail = playerDetailForTelegram(video(thumb = null), TelegramChannel(7, "Kanal A", thumbnailUrl = "avatar"), date, size)
        assertEquals("avatar", detail.artworkUrl)
        assertEquals(PlayerDetailArtShape.LOGO, detail.artShape)
        assertTrue(detail.artworkFallbackUrls.isEmpty())
    }

    @Test fun anotherChannelsDataIsIgnored() {
        val detail = playerDetailForTelegram(video(), TelegramChannel(99, "Fremd", thumbnailUrl = "other"), date, size)
        assertEquals("Kanal A", detail.eyebrow)
        assertTrue(detail.artworkFallbackUrls.isEmpty())
    }

    @Test fun portraitVideoUsesTheUprightFrameAndAudioTheSquareCover() {
        assertEquals(PlayerDetailArtShape.POSTER,
            playerDetailForTelegram(video(width = 720, height = 1280), null, date, size).artShape)
        val audio = playerDetailForTelegram(video(kind = TelegramMediaKind.AUDIO, fileName = "song.flac"), null, date, size)
        assertEquals(PlayerDetailArtShape.SQUARE, audio.artShape)
        // Audio has no picture size.
        assertEquals(listOf("FLAC", "245 MB"), audio.chips)
    }

    @Test fun runtimeFallsBackToThePlayerOnlyWhenThePostHasNone() {
        assertEquals(listOf("D1700000000000", "5min"),
            playerDetailForTelegram(video(durationMs = 0), null, date, size, playerDurationMs = 300_000L).meta)
        assertEquals(listOf("D1700000000000", "12min"),
            playerDetailForTelegram(video(), null, date, size, playerDurationMs = 300_000L).meta)
        assertEquals(listOf("D1700000000000"),
            playerDetailForTelegram(video(kind = TelegramMediaKind.PHOTO, durationMs = 0), null, date, size,
                playerDurationMs = 300_000L).meta)
    }

    @Test fun unknownExtensionsAreNotShownAsFormat() {
        val detail = playerDetailForTelegram(video(fileName = "notes.bin"), null, date, size)
        assertEquals(listOf("Full HD", "1920×1080", "245 MB"), detail.chips)
    }
}
