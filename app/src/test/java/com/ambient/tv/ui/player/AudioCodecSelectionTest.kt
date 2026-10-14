package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class AudioCodecSelectionTest {
    @Test fun enablingHdEnablesCoreAndDisablingCoreDisablesHd() {
        val enabled = toggledAudioCodec(listOf("ac3"), "dts-hd")
        assertEquals(listOf("ac3", "dts", "dts-hd"), enabled)
        assertEquals(listOf("ac3"), toggledAudioCodec(enabled, "dts"))
    }
    @Test fun disablingHdPreservesCoreAndOtherFormats() {
        assertEquals(listOf("ac3", "dts"), toggledAudioCodec(listOf("ac3", "dts", "dts-hd"), "dts-hd"))
    }
}
