package dev.rusty.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for the AudioTrack buffer arithmetic behind [PcmOutput]. */
class PcmBuffersTest {

    // ---- bufferBytes ----

    @Test
    fun oneSecondOfFloatStereoAt44100Wins_whenTheHalMinimumIsSmaller() {
        // 44_100 frames * 2 ch * 4 B = 352_800 B
        assertEquals(352_800, PcmBuffers.bufferBytes(minBytes = 15_376, sampleRate = 44_100, channels = 2, bytesPerSample = 4, targetMs = 1_000))
    }

    @Test
    fun halMinimumWins_whenItIsLargerThanTheTarget() {
        assertEquals(400_000, PcmBuffers.bufferBytes(minBytes = 400_000, sampleRate = 44_100, channels = 2, bytesPerSample = 4, targetMs = 1_000))
    }

    @Test
    fun resultIsRoundedUpToWholeFrames() {
        // frame = 8 B; 7 B minimum with a 0 ms target must become one full frame.
        assertEquals(8, PcmBuffers.bufferBytes(minBytes = 7, sampleRate = 44_100, channels = 2, bytesPerSample = 4, targetMs = 0))
        // 44_100 * 250 / 1000 = 11_025 frames = 88_200 B exactly
        assertEquals(88_200, PcmBuffers.bufferBytes(minBytes = 0, sampleRate = 44_100, channels = 2, bytesPerSample = 4, targetMs = 250))
    }

    // ---- unplayedFrames ----

    @Test
    fun unplayedIsWrittenMinusPlayed() {
        assertEquals(30_000, PcmBuffers.unplayedFrames(framesWritten = 44_100, head = 14_100, headBase = 0, bufferFrames = 44_100))
    }

    @Test
    fun headBaseIsSubtracted_whenFlushDidNotResetTheHead() {
        // Head kept counting across a flush: base 100_000, now 114_100 -> 14_100 played.
        assertEquals(30_000, PcmBuffers.unplayedFrames(framesWritten = 44_100, head = 114_100, headBase = 100_000, bufferFrames = 44_100))
    }

    @Test
    fun survivesTheUnsigned32BitWrapOfTheHead() {
        // Head is an unsigned 32-bit counter exposed as a signed int: 0xFFFF_FFF0 -> 0x0000_0005 is 21 frames.
        assertEquals(44_100 - 21, PcmBuffers.unplayedFrames(framesWritten = 44_100, head = 5, headBase = 0xFFFF_FFF0.toInt(), bufferFrames = 44_100))
    }

    @Test
    fun clampsToZeroAndToTheBufferCapacity() {
        // Head reports more than was written (latency jitter) -> nothing to replay.
        assertEquals(0, PcmBuffers.unplayedFrames(framesWritten = 1_000, head = 1_200, headBase = 0, bufferFrames = 44_100))
        // Can never have more queued than the buffer holds.
        assertEquals(44_100, PcmBuffers.unplayedFrames(framesWritten = 90_000, head = 0, headBase = 0, bufferFrames = 44_100))
    }

    // ---- audibleDelayNanos (Ambient TV visualizer tap) ----

    @Test
    fun visualizerBatchWaitsForTheQueuedAudio() {
        // A full 1 s buffer: the last written frame is heard in one second.
        assertEquals(1_000_000_000L, PcmBuffers.audibleDelayNanos(unplayedFrames = 44_100, framesBeforeWriteEnd = 0, sampleRate = 44_100))
        // A batch that ended 441 frames before the end of the write plays 10 ms earlier.
        assertEquals(990_000_000L, PcmBuffers.audibleDelayNanos(unplayedFrames = 44_100, framesBeforeWriteEnd = 441, sampleRate = 44_100))
        // Never negative, and a broken rate means "now".
        assertEquals(0L, PcmBuffers.audibleDelayNanos(unplayedFrames = 100, framesBeforeWriteEnd = 500, sampleRate = 44_100))
        assertEquals(0L, PcmBuffers.audibleDelayNanos(unplayedFrames = 100, framesBeforeWriteEnd = 0, sampleRate = 0))
    }
}
