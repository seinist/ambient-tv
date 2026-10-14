package com.ambient.tv.data.browse

import android.net.Uri
import android.os.Process
import com.ambient.tv.domain.repository.MediaRepository
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** At most one cancellable metadata request; never creates a ViewModel or requests artwork. */
@Singleton
class MetadataPrefetcher @Inject constructor(private val repository: MediaRepository, private val availability: SourceAvailability, private val scopes: BrowseScopes) {
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { work ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); work.run() }, "browse-prefetch")
    }.asCoroutineDispatcher())
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val pending = linkedMapOf<String, suspend () -> Unit>()
    private val completed = linkedMapOf<String, Long>()

    init {
        scope.launch {
            for (ignored in signal) {
                while (true) {
                    while (!BrowseWorkBudget.idle()) delay(500)
                    val next = synchronized(pending) {
                        pending.entries.firstOrNull()?.let { it.key to pending.remove(it.key)!! }
                    } ?: break
                    val job = launch(SpeculativeRequest, start = CoroutineStart.LAZY) {
                        try { withTimeoutOrNull(3_000) { next.second() } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Speculation must never fail foreground work. */ }
                    }
                    BrowseWorkBudget.speculativeJob = job
                    if (BrowseWorkBudget.idle()) job.start() else job.cancel()
                    job.join()
                    BrowseWorkBudget.speculativeJob = null
                    if (!job.isCancelled) synchronized(pending) {
                        completed[next.first] = System.currentTimeMillis()
                        while (completed.size > 64) completed.remove(completed.keys.first())
                    }
                }
            }
        }
    }

    private fun offer(key: String, id: String, fetch: suspend () -> Unit) {
        if (mediaProvider(id) !in setOf("plex", "jellyfin", "emby")) return
        val namespace = scopes.forProvider(mediaProvider(id))
        val scopedKey = "$namespace:$key"
        synchronized(pending) {
            if (System.currentTimeMillis() - (completed[scopedKey] ?: 0) < 60_000) return
            pending[scopedKey] = {
                if (namespace == scopes.forProvider(mediaProvider(id)) && availability.state(mediaProvider(id)) in setOf(SourceHealth.ONLINE, SourceHealth.UNKNOWN)) fetch()
            }
            while (pending.size > 4) pending.remove(pending.keys.first())
        }
        signal.trySend(Unit)
    }

    fun detail(id: String) = offer("detail:$id", id) { repository.getMetadataFast(id) }
    fun section(id: String) = offer("section:$id", id) { repository.getSectionPreview(id, "titleSort:asc", 100) }
    fun route(route: String) {
        if (route.startsWith("section/")) section(Uri.decode(route.substringAfter("section/").substringBefore('?')))
    }
}
