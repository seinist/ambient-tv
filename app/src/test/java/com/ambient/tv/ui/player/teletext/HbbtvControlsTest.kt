package com.ambient.tv.ui.player.teletext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HbbtvControlsTest {
    @Test fun manualDocumentFetchDoesNotReuseWebViewsCompressionOrCacheConditions() {
        val filtered = hbbtvDocumentRequestHeaders(mapOf(
            "Accept-Encoding" to "gzip, br", "if-none-match" to "etag", "If-Modified-Since" to "date",
            "Range" to "bytes=0-99", "IF-RANGE" to "etag", "User-Agent" to "browser",
            "Accept" to "text/html", "Referer" to "https://sender.example/",
        ))
        assertEquals(mapOf("Accept" to "text/html", "Referer" to "https://sender.example/"), filtered)
    }

    @Test fun nativeToolbarActivationDoesNotAlsoActivateTheSenderOnKeyRelease() {
        val keys = HbbtvActivationKeys()
        assertFalse(keys.down(23, repeated = true))
        assertFalse(keys.up(23))
        assertTrue(keys.down(23))
        assertTrue(keys.down(23, repeated = true))
        assertTrue(keys.up(23))
        assertFalse(keys.up(23))
        keys.down(66)
        keys.clear()
        assertFalse(keys.down(66, repeated = true))
        assertFalse(keys.up(66))
    }

    @Test fun closingAnOldViewerCannotClearTheNewViewersVideoWindow() {
        var window: String? = null
        val ownership = HbbtvWindowOwnership<String> { window = it }
        val old = Any()
        val current = Any()
        ownership.claim(old)
        ownership.update(old, "small old window")
        ownership.claim(current)
        assertNull(window)
        ownership.update(current, "small new window")
        ownership.update(old, "late old callback")
        ownership.release(old)
        assertEquals("small new window", window)
        ownership.release(current)
        assertNull(window)
    }

    @Test fun releasingTheVideoWindowAfterAChannelChangeRestoresFullscreen() {
        var window: String? = null
        val ownership = HbbtvWindowOwnership<String> { window = it }
        val old = Any()
        ownership.claim(old)
        ownership.update(old, "small window")
        ownership.release(old)
        assertNull(window)
    }

    @Test fun aShortBackReturnsToTheSenderAndTwoShortPressesDoNotCloseIt() {
        val press = HbbtvBackPress()
        press.down(1_000)
        assertEquals(HbbtvBackPress.Action.PAGE_BACK, press.up(1_100, cancelled = false))
        press.down(1_150)
        assertEquals(HbbtvBackPress.Action.PAGE_BACK, press.up(1_250, cancelled = false))
    }

    @Test fun aHeldBackOpensControlsOnceAndDoesNotAlsoGoBackOnRelease() {
        val press = HbbtvBackPress()
        press.down(1_000)
        assertEquals(HbbtvBackPress.Action.NONE, press.held(1_699))
        assertEquals(HbbtvBackPress.Action.CONTROLS, press.held(1_700))
        assertEquals(HbbtvBackPress.Action.NONE, press.held(1_800))
        assertEquals(HbbtvBackPress.Action.NONE, press.up(1_900, cancelled = false))
    }

    @Test fun lateTimerStillRecognizesAHoldOnRelease() {
        val press = HbbtvBackPress()
        press.down(10)
        assertEquals(HbbtvBackPress.Action.CONTROLS, press.up(800, cancelled = false))
    }

    @Test fun cancelledOrTransferredBackDoesNotReachThePage() {
        val press = HbbtvBackPress()
        press.down(10)
        assertEquals(HbbtvBackPress.Action.NONE, press.up(100, cancelled = true))
        press.down(200)
        press.cancel()
        assertEquals(HbbtvBackPress.Action.NONE, press.up(250, cancelled = false))
        assertEquals(HbbtvBackPress.Action.NONE, press.held(1_000))
    }

    @Test fun colorControlsFollowOnlyTheRequestedColorBits() {
        assertEquals(emptyList<Int>(), hbbtvColorKeys(HBBTV_DEFAULT_COLOR_KEYS))
        assertEquals(emptyList<Int>(), hbbtvColorKeys(16 or 32 or 256))
        assertEquals(listOf(1, 3), hbbtvColorKeys(2 or 8 or 16))
        assertEquals(listOf(0, 1, 2, 3), hbbtvColorKeys(15))
    }

    @Test fun navigationInvalidatesThePreviousDocumentEvenAtTheSameUrl() {
        val session = HbbtvDocumentSession()
        val first = checkNotNull(session.begin("https://sender.example/start"))
        assertTrue(session.accepts(first))
        assertTrue(session.accepts(first.id))
        val replacement = checkNotNull(session.begin(first.url))
        assertFalse(session.accepts(first))
        assertFalse(session.accepts(first.id))
        assertTrue(session.accepts(replacement))
    }

    @Test fun disposingTheViewerRejectsQueuedRequestsAndBridgeMessages() {
        val session = HbbtvDocumentSession()
        val page = checkNotNull(session.begin("https://sender.example/start"))
        session.close()
        assertFalse(session.accepts(page))
        assertFalse(session.accepts(page.id))
        assertNull(session.begin(page.url))
    }

    @Test fun senderShimCarriesDocumentIdentityAndPublishesKeysetChanges() {
        val shim = hbbtvShim("A \"Sender\" </script>", "deu", documentId = 42)
        assertTrue(shim.contains("documentId=42"))
        assertTrue(shim.contains("nativeBridge.keyset(v,documentId)"))
        assertTrue(shim.contains("AmbientHbbtv.keyset(this.value)"))
        assertTrue(shim.contains("nativeBridge.broadcast(v,documentId)"))
        assertTrue(shim.contains("nativeBridge.close(documentId)"))
        assertFalse(shim.contains("</script>"))
    }

    @Test fun hbbtvDocumentKeepsItsOwnViewportAndInstallsBridgeBeforeSenderScripts() {
        val document = "<html><head><meta name='viewport' content='width=960'><script>sender()</script></head></html>"
        val injected = injectHbbtvShim(document, "bridge()")
        assertTrue(injected.indexOf("bridge()") < injected.indexOf("sender()"))
        assertFalse(injected.contains("width=1280"))
        assertTrue(hbbtvDocumentType("application/vnd.hbbtv.xhtml+xml; charset=UTF-8"))
    }

    @Test fun closingHbbtvResumesOnlyPlaybackItActuallyPaused() {
        var playing = true
        val calls = mutableListOf<String>()
        val broadcast = HbbtvBroadcastControl({ playing }, { calls += "pause"; playing = false }, { calls += "play"; playing = true })
        broadcast.stop()
        broadcast.stop()
        broadcast.resume()
        broadcast.resume()
        assertEquals(listOf("pause", "play"), calls)
        playing = false
        calls.clear()
        broadcast.stop()
        broadcast.resume()
        assertTrue(calls.isEmpty())
    }
}
