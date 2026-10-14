package com.ambient.tv.companion

import com.ambient.tv.data.shared.SharedMediaLink
import com.ambient.tv.data.shared.SharedMediaRepository
import com.ambient.tv.connectivity.PlaybackServerUnreachable
import com.ambient.tv.ui.player.audioOnlyPlaybackHint
import com.ambient.tv.ui.player.usesProviderStreamHeaders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SharedMediaTest {
    @Test fun audioAndVideoFormatsAreRecognizedWithoutFetchingWebsites() {
        for (extension in listOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "weba")) {
            val url = "https://media.example.test/file.$extension?signature=abc%2F123"
            val link = requireNotNull(SharedMediaLink.parse(url))
            assertTrue(link.audioOnly)
            assertEquals(CompanionMedia("url", url), sharedMedia("Listen here: $url"))
        }
        for (extension in listOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mpg", "mpeg", "ogv", "m3u8", "mpd")) {
            assertFalse(requireNotNull(SharedMediaLink.parse("https://media.example.test/file.$extension")).audioOnly)
        }
        assertNotNull(sharedMedia("http://192.168.1.42:8080/Music/song.MP3"))
        assertNotNull(sharedMedia("https://[2001:db8::1]/video.mp4"))
    }

    @Test fun rejectsWebsitesLocalFilesCredentialsAndAmbiguousShares() {
        listOf("https://example.test/watch?id=1", "https://example.test/?file=video.mp4", "https://example.test/page.html",
            "file:///sdcard/music.mp3", "content://media/song.mp3", "ftp://host/song.mp3", "javascript:video.mp4",
            "https://user:password@example.test/song.mp3", "https://example.test:0/song.mp3", "https://example.test:99999/song.mp3",
            "https://example.test/song.mp3#hidden", "https://example.test/song%0d%0a.mp3", "https://example.test/song%5c.mp3",
            "http://localhost/video.mp4", "http://x.localhost/video.mp4", "http://127.0.0.1/video.mp4",
            "http://127.1/video.mp4", "http://2130706433/video.mp4", "http://0177.0.0.1/video.mp4", "http://0x7f000001/video.mp4",
            "http://169.254.169.254/video.mp4", "http://[::1]/song.mp3", "http://[::]/song.mp3", "http://[fe80::1]/song.mp3"
        ).forEach { assertNull(it, SharedMediaLink.parse(it)) }
        assertNull(sharedMedia("https://example.test/a.mp3 https://example.test/b.mp4"))
        assertNull(sharedMedia("https://youtu.be/dQw4w9WgXcQ https://example.test/a.mp3"))
        assertNull(sharedMedia("x".repeat(8193)))
        assertEquals(CompanionMedia("youtube", "dQw4w9WgXcQ"), sharedMedia("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test fun sharedItemsAreEphemeralAndNeverUseProviderMetadataOrCredentials() = runBlocking {
        val repository = SharedMediaRepository()
        val url = "https://example.test/song.mp3?signature=secret"
        val key = repository.register(url)
        assertTrue(SharedMediaRepository.owns(key))
        assertFalse(key.contains("secret"))
        val item = requireNotNull(repository.getMetadata(key).getOrThrow())
        assertEquals(url, item.streamUrl)
        assertEquals("track", item.type)
        assertFalse(item.title.contains("secret"))
        assertTrue(item.mediaVersions.single().tracks.isEmpty())
        assertNull(PlaybackServerUnreachable.mediaSystemForRatingKey(key))
        assertTrue(repository.getNextTrack(key).getOrThrow() == null)
        assertTrue(repository.getAudioCompatibleStreamUrl(key, 0, 0).isFailure)
        assertTrue(repository.getVideoCompatibleStreamUrl(key, 0, 0).isFailure)
        assertTrue(SharedMediaRepository().getMetadata(key).isFailure)
        repeat(32) { repository.register("https://example.test/$it.mp4") }
        assertNull(repository.link(key))
    }

    @Test fun missingVideoCodecDoesNotTurnSharedVideoIntoAudioOnly() {
        assertFalse(audioOnlyPlaybackHint("shared:test", "video", null))
        assertTrue(audioOnlyPlaybackHint("shared:test", "track", null))
    }

    @Test fun sharedLinksCannotReceiveConfiguredProviderTokens() {
        assertFalse(usesProviderStreamHeaders("shared:opaque-id"))
        assertFalse(usesProviderStreamHeaders("dlna:server:item"))
        assertTrue(usesProviderStreamHeaders("123"))
        assertTrue(usesProviderStreamHeaders("j:item"))
        assertTrue(usesProviderStreamHeaders("e:item"))
    }

    @Test fun shortcutIdsOnlyResolveCurrentlyPairedIdentities() {
        val peer = CompanionPeer("ab".repeat(32), "Living room")
        assertEquals(peer, CompanionShareTargets.peer(CompanionShareTargets.id(peer), listOf(peer)))
        assertNull(CompanionShareTargets.peer(CompanionShareTargets.id(peer), emptyList()))
        assertNull(CompanionShareTargets.peer("Living room", listOf(peer)))
        assertEquals(CompanionShareTargets.id(peer), CompanionShareTargets.id(peer.copy(name = "Renamed")))
    }
}
