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

    @Test fun shimGoesFirstIntoHead() {
        assertEquals("<html><head lang=\"de\"><script type=\"text/javascript\">S</script><script>app()</script></head>",
            injectHbbtvShim("<html><head lang=\"de\"><script>app()</script></head>", "S"))
        assertEquals("<script type=\"text/javascript\">S</script><header/>", injectHbbtvShim("<header/>", "S"))
    }

    @Test fun keysAndStringsForThePage() {
        assertEquals(403, hbbtvKeyCode(android.view.KeyEvent.KEYCODE_PROG_RED))
        assertEquals(461, hbbtvKeyCode(android.view.KeyEvent.KEYCODE_BACK))
        assertNull(hbbtvKeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("\"Das \\\"Erste\\\" \\u003c/script>\"", jsString("Das \"Erste\" </script>"))
        assertTrue(hbbtvShim("ARD", "deu").contains("name:\"ARD\""))
    }
}
