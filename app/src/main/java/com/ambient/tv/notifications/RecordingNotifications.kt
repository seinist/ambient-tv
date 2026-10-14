package com.ambient.tv.notifications

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.ambient.tv.R
import com.ambient.tv.livetv.LiveTvRegistry
import com.ambient.tv.livetv.RecordingEventKind
import com.ambient.tv.livetv.RecordingSnapshot
import com.ambient.tv.livetv.recordingEvents
import com.ambient.tv.util.suspendResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Announces recordings that start, finish or fail on a Live TV server (TVHeadend, Plex, Jellyfin,
 * Emby). Servers do not push these, so the recording lists are polled while the app is in the
 * foreground; in the background nothing is requested.
 */
@Singleton
class RecordingNotifications @Inject constructor(
    private val registry: LiveTvRegistry,
) {
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshots = mutableMapOf<String, RecordingSnapshot>()
    private val announcedStarts = mutableMapOf<String, MutableSet<String>>()

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) poll()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun poll() {
        val sources = suspendResult { registry.discover() }.getOrDefault(emptyList())
            .filter { it.capabilities.recordings || it.capabilities.timers }
        val active = sources.map { it.source.id }.toSet()
        snapshots.keys.retainAll(active)
        announcedStarts.keys.retainAll(active)
        for (available in sources) {
            val repository = registry.repository(available.source.id)
            // A failed poll keeps the previous snapshot, so nothing is announced twice or lost.
            val current = suspendResult {
                RecordingSnapshot(
                    upcoming = repository.upcomingRecordings().associateBy { it.uuid },
                    finished = repository.finishedRecordings().mapTo(mutableSetOf()) { it.uuid },
                    failed = repository.failedRecordings().mapTo(mutableSetOf()) { it.uuid },
                )
            }.getOrNull() ?: continue
            val id = available.source.id
            val events = recordingEvents(snapshots[id], current, System.currentTimeMillis() / 1000,
                announcedStarts.getOrPut(id) { mutableSetOf() })
            snapshots[id] = current
            events.forEach { event ->
                AppNotifications.post(AppNotice(
                    key = "recording:$id:${event.recording.uuid}:${event.kind.name.lowercase()}",
                    message = when (event.kind) {
                        RecordingEventKind.STARTED -> R.string.notice_recording_started
                        RecordingEventKind.FINISHED -> R.string.notice_recording_finished
                        RecordingEventKind.FAILED -> R.string.notice_recording_failed
                    },
                    systems = listOf(available.source.system),
                    subject = event.recording.title,
                    phase = if (event.kind == RecordingEventKind.FAILED) NoticePhase.ERROR else NoticePhase.INFO,
                    kind = NoticeKind.RECORDING,
                    interruptPlayback = false,
                ))
            }
        }
    }

    private companion object {
        const val POLL_MS = 120_000L
    }
}
