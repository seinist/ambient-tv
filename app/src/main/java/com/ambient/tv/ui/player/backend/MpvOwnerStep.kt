package com.ambient.tv.ui.player.backend

/**
 * What the MPV owner thread is doing right now and since when. Written on the owner, read by the
 * main-thread watchdog: when "Player antwortet nicht" appears, the log names the blocked step.
 */
internal class MpvOwnerStep(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    @Volatile var name: String = "waiting_for_previous_player"
        private set
    @Volatile var sinceMs: Long = clock()
        private set

    /** Bumped by the owner right before it loads the stream again (surface change, audio fallback). */
    @Volatile var reopens: Int = 0
        private set

    /** The owner is about to reload the stream: the watchdog allows the opening limit again. */
    fun reopening(step: String) {
        reopens++
        mark(step)
    }

    /** A mailbox operation began ([step]) or ended (null: the owner is idle again). */
    fun operation(step: String?) = mark(step ?: "idle")

    /** A sub-step inside the running operation, e.g. "surface:vulkan_probe". */
    fun mark(step: String) {
        name = step
        sinceMs = clock()
    }
}

/** While MPV opens a stream (until its first picture), a slow native call is not yet a hang. */
internal const val MPV_OPENING_UNRESPONSIVE_MS = 15_000L

/** The watchdog limit: [MPV_OPENING_UNRESPONSIVE_MS] while opening, then [MPV_UNRESPONSIVE_MS]. */
internal fun mpvUnresponsiveLimitMs(opening: Boolean): Long =
    if (opening) MPV_OPENING_UNRESPONSIVE_MS else MPV_UNRESPONSIVE_MS

/** One log line for the moment the stall dialog appears. Never contains a URL. */
internal fun mpvStallReport(step: String, stepSinceMs: Long, lastProgressMs: Long, nowMs: Long, opening: Boolean): String =
    "MPV unresponsive: step=$step running ${(nowMs - stepSinceMs).coerceAtLeast(0L)} ms, " +
        "no progress for ${(nowMs - lastProgressMs).coerceAtLeast(0L)} ms, " +
        "phase=${if (opening) "opening" else "playing"} limit=${mpvUnresponsiveLimitMs(opening)} ms"

/** The first picture (or, for audio only, the loaded file) ends the opening phase. */
internal fun mpvOpeningDone(fileReady: Boolean, audioOnly: Boolean, videoWidth: Int): Boolean =
    fileReady && (audioOnly || videoWidth > 0)

/**
 * Whether the watchdog uses the opening limit: during the first open, and from a reopen
 * ([reopens] differs from the count the controller last saw) until that stream shows its picture.
 */
internal fun mpvWatchdogOpening(opening: Boolean, seenReopens: Int, reopens: Int): Boolean =
    opening || reopens != seenReopens
