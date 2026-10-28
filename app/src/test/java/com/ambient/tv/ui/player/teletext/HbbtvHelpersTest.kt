package com.ambient.tv.ui.player.teletext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HbbtvHelpersTest {
    @Test fun hbbtvDocumentsAreShownAsHtml() {
        assertTrue(hbbtvDocumentType("application/vnd.hbbtv.xhtml+xml; charset=UTF-8"))
        assertTrue(hbbtvDocumentType("text/html"))
        assertFalse(hbbtvDocumentType("image/png"))
    }

    @Test fun shimGoesFirstIntoHeadWithA720pViewport() {
        val viewport = "<meta name=\"viewport\" content=\"width=1280, user-scalable=no\">"
        assertEquals("<html><head lang=\"de\">$viewport<script type=\"text/javascript\">S</script><script>app()</script></head>",
            injectHbbtvShim("<html><head lang=\"de\"><script>app()</script></head>", "S"))
        assertEquals("$viewport<script type=\"text/javascript\">S</script><header/>", injectHbbtvShim("<header/>", "S"))
        // A page with its own viewport keeps it.
        assertEquals("<head><script type=\"text/javascript\">S</script><meta name='viewport' content='width=960'></head>",
            injectHbbtvShim("<head><meta name='viewport' content='width=960'></head>", "S"))
    }

    @Test fun keysAndStringsForThePage() {
        assertEquals(403, hbbtvKeyCode(android.view.KeyEvent.KEYCODE_PROG_RED))
        assertEquals(461, hbbtvKeyCode(android.view.KeyEvent.KEYCODE_BACK))
        assertNull(hbbtvKeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("\"Das \\\"Erste\\\" \\u003c/script>\"", jsString("Das \"Erste\" </script>"))
        assertTrue(hbbtvShim("ARD", "deu").contains("name:\"ARD\""))
    }

    @Test fun broadcastPausesOnlyARunningProgrammeAndResumesOnlyWhatItPaused() {
        var playing = true
        val calls = mutableListOf<String>()
        val control = HbbtvBroadcastControl({ playing }, { calls += "pause"; playing = false }, { calls += "play"; playing = true })
        control.stop()
        control.stop()
        control.resume()
        control.resume()
        assertEquals(listOf("pause", "play"), calls)
        // Paused by the viewer: the application neither pauses it again nor starts it.
        playing = false
        calls.clear()
        control.stop()
        control.resume()
        assertEquals(emptyList<String>(), calls)
        assertTrue(hbbtvShim("ARD", "deu").contains("AmbientHbbtv.broadcast(false)"))
    }
}
