package com.ambient.tv.data.dlna

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.wol.wakeWithRetries
import com.ambient.tv.R
import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.AppNotifications
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.NoticePhase
import com.ambient.tv.notifications.notifyResult
import com.ambient.tv.wol.WolManager
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * DLNA counterpart of the Files/NAS wake helper: before browsing a server with auto-wake, probe
 * its ContentDirectory endpoint and, if it does not answer, send a magic-packet burst through
 * [WolManager] (shared coalescing with the other systems) and wait until it is reachable.
 */
@Singleton
class DlnaWake @Inject constructor(
    private val wolManager: WolManager,
    private val preferences: AppPreferences,
) {
    private val lastOnline = ConcurrentHashMap<String, Long>()

    suspend fun ensureAwake(server: DlnaServer) {
        if (!server.wolAuto || server.wolMac.isBlank()) return
        val target = dlnaWakeEndpoint(server) ?: return
        val key = "${target.first}:${target.second}"
        val now = System.nanoTime()
        if (lastOnline[key]?.let { now - it in 0 until RECENT_NS } == true) return
        if (online(target)) { remember(key); return }
        wakeAndWait(server, target, key)
    }

    suspend fun wake(server: DlnaServer): Result<Unit> =
        wolManager.sendWakeBurst(server.wolMac)

    private suspend fun wakeAndWait(server: DlnaServer, target: Pair<String, Int>, key: String) {
        val notice = "wake:dlna:$key"
        AppNotifications.post(AppNotice(notice, R.string.notice_waking, listOf("dlna"), server.name, NoticePhase.WORKING, NoticeKind.WAKE))
        try {
            val ready = wakeWithRetries(
                retries = preferences.wolRetryCount,
                windowMs = WAIT_MS,
                pollMs = POLL_MS,
                send = { attempt -> wolManager.sendWakeBurst(server.wolMac, retry = attempt > 0).getOrThrow() },
                reachable = { online(target) },
            )
            if (!ready) throw java.net.SocketTimeoutException("Wake timeout")
            remember(key)
            notifyResult(notice, "dlna", server.name, R.string.notice_awake, kind = NoticeKind.WAKE)
        } catch (cancelled: CancellationException) {
            AppNotifications.dismiss(notice)
            throw cancelled
        } catch (error: Exception) {
            notifyResult(notice, "dlna", server.name, success = false, kind = NoticeKind.WAKE, failureMessage = R.string.notice_wake_failed)
            throw error
        }
    }

    private fun remember(key: String) {
        val now = System.nanoTime()
        lastOnline.entries.removeAll { now - it.value >= RECENT_NS }
        if (lastOnline.size >= 64) lastOnline.clear()
        lastOnline[key] = now
    }

    private suspend fun online(target: Pair<String, Int>): Boolean = runInterruptible(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(target.first, target.second), 900) }
            true
        } catch (_: java.io.IOException) {
            false
        }
    }

    private companion object {
        const val RECENT_NS = 30_000_000_000L
        const val WAIT_MS = 60_000L
        const val POLL_MS = 1_500L
    }
}

/** Host and TCP port of the ContentDirectory control URL (the endpoint browsing talks to). */
internal fun dlnaWakeEndpoint(server: DlnaServer): Pair<String, Int>? = runCatching {
    val uri = URI(server.contentDirectoryUrl)
    val host = uri.host?.takeIf(String::isNotBlank) ?: return null
    val port = if (uri.port > 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    host to port
}.getOrNull()
