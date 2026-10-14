package com.ambient.tv.ui.player

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOsdKeysTest {
    private class Recorder(var playing: Boolean = true, private val hasNext: Boolean = true) : OsdKeyTarget {
        val calls = mutableListOf<String>()
        override fun isPlaying() = playing
        override fun togglePlayback() { calls += "toggle"; playing = !playing }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun seek(deltaMs: Long, revealOsd: Boolean) { calls += "seek:$deltaMs:${if (revealOsd) "osd" else "bar"}" }
        override fun showOsd() { calls += "osd" }
        override fun hideSeekFeedback() { calls += "hideBar" }
        override fun restart() { calls += "restart" }
        override fun next() { if (hasNext) calls += "next" }
        override fun previousTrack() { calls += "previousTrack" }
        override fun zap(delta: Int) { calls += "zap:$delta" }
    }

    private val steps = OsdSeekSteps(jumpMs = 60_000, held = { it * 2 })
    private fun state(content: OsdContent = OsdContent.VIDEO, visible: Boolean = false, panel: Boolean = false,
        bar: Boolean = false, zapOsd: Boolean = false, canSeekForward: Boolean = true) =
        OsdKeyState(content, visible, panel, bar, zapOsd, canSeekForward)

    private fun press(key: Key, state: OsdKeyState, target: Recorder = Recorder(), down: Boolean = true): Pair<Boolean, List<String>> =
        handlePlayerOsdKey(key, down, state, steps, target) to target.calls

    @Test fun keyUpAndBackAreNeverHandled() {
        assertEquals(false to emptyList<String>(), press(Key.DirectionCenter, state(), down = false))
        assertEquals(false to emptyList<String>(), press(Key.Back, state()))
        assertEquals(false to emptyList<String>(), press(Key.Back, state(visible = true)))
    }

    @Test fun backLikeAndVolumeKeysNeverOpenTheOsd() {
        listOf(Key.Escape, Key.Backspace, Key.VolumeUp, Key.VolumeDown, Key.VolumeMute).forEach { key ->
            OsdContent.entries.forEach { content ->
                assertEquals(false to emptyList<String>(), press(key, state(content)))
            }
        }
    }

    @Test fun okOpensTheOsdOrTogglesRightAfterAJump() {
        listOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter).forEach { key ->
            assertEquals(true to listOf("osd"), press(key, state()))
            assertEquals(true to listOf("toggle", "hideBar", "osd"), press(key, state(bar = true), Recorder(playing = true)))
            assertEquals(true to listOf("toggle", "hideBar"), press(key, state(bar = true), Recorder(playing = false)))
            assertEquals(false to emptyList<String>(), press(key, state(visible = true)))
        }
    }

    @Test fun leftRightJumpWithTheBarAndMoveFocusInTheOsd() {
        assertEquals(true to listOf("seek:-120000:bar"), press(Key.DirectionLeft, state()))
        assertEquals(true to listOf("seek:120000:bar"), press(Key.DirectionRight, state()))
        assertEquals(true to listOf("osd"), press(Key.DirectionRight, state(canSeekForward = false)))
        assertEquals(false to emptyList<String>(), press(Key.DirectionLeft, state(visible = true)))
        assertEquals(true to listOf("osd"), press(Key.DirectionLeft, state(OsdContent.LIVE)))
        assertEquals(true to listOf("seek:-120000:bar"), press(Key.DirectionLeft, state(OsdContent.RECORDING)))
    }

    @Test fun upDownJumpOrChangeChannels() {
        assertEquals(true to listOf("seek:60000:bar"), press(Key.DirectionUp, state()))
        assertEquals(true to listOf("seek:-60000:bar"), press(Key.DirectionDown, state(OsdContent.MUSIC)))
        assertEquals(false to emptyList<String>(), press(Key.DirectionUp, state(visible = true)))
        assertEquals(true to listOf("zap:1"), press(Key.DirectionUp, state(OsdContent.LIVE)))
        assertEquals(true to listOf("zap:-1"), press(Key.DirectionDown, state(OsdContent.LIVE, visible = true, zapOsd = true)))
        assertEquals(false to emptyList<String>(), press(Key.DirectionDown, state(OsdContent.LIVE, visible = true)))
        // The zap OSD is a live-TV thing only.
        assertEquals(false to emptyList<String>(), press(Key.DirectionUp, state(visible = true, zapOsd = true)))
    }

    @Test fun mediaKeysActAndShowTheOsdForEveryContent() {
        OsdContent.entries.forEach { content ->
            assertEquals(true to listOf("toggle", "osd"), press(Key.MediaPlayPause, state(content)))
            assertEquals(true to listOf("toggle", "osd"), press(Key.Spacebar, state(content)))
            assertEquals(true to listOf("play", "osd"), press(Key.MediaPlay, state(content)))
            assertEquals(true to listOf("pause", "osd"), press(Key.MediaPause, state(content)))
            assertEquals(true to listOf("stop"), press(Key.MediaStop, state(content)))
        }
    }

    @Test fun rewindForwardJumpAndShowTheOsdExceptLive() {
        assertEquals(true to listOf("seek:-120000:osd"), press(Key.MediaRewind, state()))
        assertEquals(true to listOf("seek:120000:osd"), press(Key.MediaFastForward, state(OsdContent.RECORDING)))
        assertEquals(false to emptyList<String>(), press(Key.MediaFastForward, state(canSeekForward = false)))
        assertEquals(false to emptyList<String>(), press(Key.MediaRewind, state(OsdContent.LIVE)))
        val music = OsdSeekSteps(jumpMs = 60_000, backMs = 10_000, forwardMs = 30_000)
        val target = Recorder()
        assertTrue(handlePlayerOsdKey(Key.MediaRewind, true, state(OsdContent.MUSIC), music, target))
        assertTrue(handlePlayerOsdKey(Key.DirectionRight, true, state(OsdContent.MUSIC), music, target))
        assertEquals(listOf("seek:-10000:osd", "seek:30000:bar"), target.calls)
    }

    @Test fun nextAndPreviousFollowTheContent() {
        assertEquals(true to listOf("next"), press(Key.MediaNext, state()))
        assertEquals(true to emptyList<String>(), press(Key.MediaNext, state(), Recorder(hasNext = false)))
        assertEquals(true to listOf("restart", "osd"), press(Key.MediaPrevious, state()))
        assertEquals(true to listOf("restart", "osd"), press(Key.MediaPrevious, state(OsdContent.RECORDING)))
        assertEquals(true to listOf("zap:1"), press(Key.MediaNext, state(OsdContent.LIVE)))
        assertEquals(true to listOf("zap:-1"), press(Key.MediaPrevious, state(OsdContent.LIVE)))
        assertEquals(true to listOf("previousTrack"), press(Key.MediaPrevious, state(OsdContent.MUSIC)))
    }

    @Test fun anOpenPanelGetsEveryKeyButMediaKeys() {
        listOf(Key.DirectionUp, Key.DirectionLeft, Key.DirectionCenter, Key.Enter, Key.Menu).forEach { key ->
            assertEquals(false to emptyList<String>(), press(key, state(panel = true)))
        }
        assertEquals(true to listOf("toggle", "osd"), press(Key.MediaPlayPause, state(panel = true)))
        assertEquals(true to listOf("zap:1"), press(Key.MediaNext, state(OsdContent.LIVE, panel = true)))
    }

    @Test fun otherKeysShowTheOsdAndPassOn() {
        val (handled, calls) = press(Key.Menu, state())
        assertFalse(handled)
        assertEquals(listOf("osd"), calls)
    }
}
