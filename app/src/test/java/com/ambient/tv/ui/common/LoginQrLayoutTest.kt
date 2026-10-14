package com.ambient.tv.ui.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginQrLayoutTest {
    @Test fun tallTvKeepsFivePercentOverscanMargin() {
        assertEquals(0.90f, qrDialogMaxHeightFraction(1080.dp), 0.0001f)
        assertEquals(0.90f, qrDialogMaxHeightFraction(540.dp), 0.0001f)
    }

    @Test fun shortTvKeepsAtLeast27dpInset() {
        val screen = 400.dp
        val fraction = qrDialogMaxHeightFraction(screen)
        val inset = screen * ((1f - fraction) / 2f)
        assertEquals(27f, inset.value, 0.05f)
        assertEquals(0.865f, fraction, 0.0001f)
    }

    @Test fun qrSideNeverExceedsTheMeasuredSlot() {
        assertEquals(180f, qrSide(220.dp, 180.dp).value, 0.01f)
        assertEquals(96f, qrSide(96.dp, 400.dp).value, 0.01f)
        assertEquals(0f, qrSide(0.dp, 200.dp).value, 0.01f)
        assertEquals(0f, qrSide(Dp.Infinity, 200.dp).value, 0.01f)
    }

    @Test fun phoneInsetStaysSmallAndTvKeepsOverscan() {
        assertEquals(12f, qrScreenInset(800.dp, television = false).value, 0.01f)
        assertEquals(54f, qrScreenInset(1080.dp, television = true).value, 0.01f)
        assertEquals(27f, qrScreenInset(400.dp, television = true).value, 0.01f)
    }

    @Test fun onlyWebLinksGetTheBrowserButton() {
        assertTrue(loginLinkIsWebUrl("https://youtube.com/qr/activate/ABCD-EFGH"))
        assertTrue(loginLinkIsWebUrl("HTTP://plex.tv/link"))
        // Companion pairing only means something on the other device.
        assertFalse(loginLinkIsWebUrl("ambienttv://pair?host=192.168.1.5"))
        assertFalse(loginLinkIsWebUrl("plex.tv/link"))
    }

    @Test fun linkLabelDropsSchemeAndTrailingSlash() {
        assertEquals("youtube.com/qr/activate/ABCD-EFGH", loginLinkLabel("https://youtube.com/qr/activate/ABCD-EFGH"))
        assertEquals("plex.tv/link", loginLinkLabel(" https://plex.tv/link/ "))
        assertEquals("ambienttv://pair", loginLinkLabel("ambienttv://pair"))
    }
}
