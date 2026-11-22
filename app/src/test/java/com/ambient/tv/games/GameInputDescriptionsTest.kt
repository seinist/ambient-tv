package com.ambient.tv.games

import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameInputDescriptionsTest {
    @Test fun labelsKeepColonsAndPlayerIdentityWithoutConfusingStickAxes() {
        val labels = GameInputDescriptions.fromNative(arrayOf(
            "0:1:0:8:Fire: primary", "1:1:0:8:Jump", "1:5:2:12:Brake",
            "0:5:0:0:Steering", "0:2:0:0:Mouse X", "4:1:0:8:Outside port", "0:1:0:16:Outside button",
        ))
        assertEquals(mapOf("0:8" to "Fire: primary", "1:8" to "Jump", "1:12" to "Brake"), labels)
        assertEquals(mapOf(RetroPadButton.A to "Jump", RetroPadButton.L2 to "Brake"),
            GameInputDescriptions.forPort(labels, 1))
        assertTrue(GameInputDescriptions.forPort(labels, -1).isEmpty())
    }

    @Test fun labelsAreBoundedAndControlCharactersCannotBreakThePanel() {
        val labels = GameInputDescriptions.fromNative(arrayOf(
            "0:1:0:0:  Jump\n\t (hold)  ", "0:1:0:1:" + "a".repeat(256),
            "0:1:0:2:   ", "garbage", "999999999999:1:0:0:no",
        ))
        assertEquals("Jump (hold)", labels["0:0"])
        assertEquals(GameInputDescriptions.MAX_LABEL_LENGTH, labels["0:1"]?.length)
        assertEquals(2, labels.size)
        assertEquals(mapOf("0:8" to "Jump"), GameInputDescriptions.normalized(mapOf(
            "0:8" to "Jump", "-1:8" to "Bad", "0:55" to "Bad", "abc" to "Bad")))
    }
}
