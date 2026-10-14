package com.ambient.tv.livetv

/** One poll of a source's recording lists, reduced to what transition detection needs. */
data class RecordingSnapshot(
    val upcoming: Map<String, LiveTvRecording>,
    val finished: Set<String>,
    val failed: Set<String>,
)

enum class RecordingEventKind { STARTED, FINISHED, FAILED }

data class RecordingEvent(val kind: RecordingEventKind, val recording: LiveTvRecording)

/**
 * What changed between two polls. Only entries this app saw scheduled earlier are announced as
 * finished or failed, so old recordings that appear through list paging stay quiet. A recording
 * that was already running at the first poll is added to [announcedStarts] without an event.
 */
fun recordingEvents(
    previous: RecordingSnapshot?,
    current: RecordingSnapshot,
    nowEpochSeconds: Long,
    announcedStarts: MutableSet<String>,
): List<RecordingEvent> = buildList {
    current.upcoming.values
        .filter { it.startEpochSeconds <= nowEpochSeconds && nowEpochSeconds < it.stopEpochSeconds }
        .forEach { running -> if (announcedStarts.add(running.uuid) && previous != null) add(RecordingEvent(RecordingEventKind.STARTED, running)) }
    if (previous == null) return@buildList
    (current.finished - previous.finished).forEach { uuid ->
        previous.upcoming[uuid]?.let { add(RecordingEvent(RecordingEventKind.FINISHED, it)) }
    }
    (current.failed - previous.failed).forEach { uuid ->
        previous.upcoming[uuid]?.let { add(RecordingEvent(RecordingEventKind.FAILED, it)) }
    }
    // Forget starts once the entry left the upcoming list, so the set stays small.
    announcedStarts.retainAll(current.upcoming.keys)
}
