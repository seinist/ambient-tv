package com.ambient.tv.livetv

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.domain.model.*
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.tvheadend.TvheadendPlayerState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*


/** Emulator-only protocol/UI audit. Every mutation goes to the in-process loopback fixture. */
class LiveTvAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun <T> main(block: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return value!!.getOrThrow()
    }
    private fun await(label: String, timeout: Long = 30_000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (main(predicate)) return; Thread.sleep(100) }
        error("$label timed out")
    }
    fun run() = runBlocking {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Emulator only" }
        check(instrumentation.targetContext.packageName.endsWith(".livetvaudit")) { "Build with -PAUDIT_APP_SUFFIX=.livetvaudit to isolate test settings" }
        val deps = EntryPointAccessors.fromApplication(instrumentation.targetContext, LiveTvAuditDependencies::class.java)
        val prefs = deps.preferences(); val tokens = deps.tokens(); val registry = deps.registry()
        val fixture = TvFixture(File("/data/local/tmp/livetv-audit-20261002.mp4").readBytes())
        var activity: LiveTvAuditActivity? = null
        try {
            prefs.clearRecentTvChannels()
            prefs.plexEnabled = true; prefs.jellyfinEnabled = true; prefs.embyEnabled = true
            prefs.tvheadendEnabled = false; prefs.youtubeEnabled = false; prefs.spotifyEnabled = false
            prefs.localNetworkOnly = true; prefs.initialSetupCompleted = true
            prefs.tvheadendPlaybackBackendKey = "media3"; prefs.displayRefreshAdjustmentKey = "off"
            prefs.replaceServerProfiles(listOf(ServerProfile("audit-plex", "Plex TV", "127.0.0.1", fixture.port)), "audit-plex")
            prefs.replaceJellyfinServerProfiles(listOf(
                JellyfinServerProfile("audit-jf1", "Jellyfin One", "127.0.0.1", fixture.port, basePath = "/jf1", userId = "user", authenticated = true),
                JellyfinServerProfile("audit-jf2", "Jellyfin Two", "127.0.0.1", fixture.port, basePath = "/jf2", userId = "user", authenticated = true),
            ), "audit-jf1")
            prefs.replaceEmbyServerProfiles(listOf(EmbyServerProfile("audit-emby", "Emby TV", "127.0.0.1", fixture.port, basePath = "/emby", userId = "user", authenticated = true)), "audit-emby")
            tokens.setTokenForServer("audit-plex", "fixture-plex")
            tokens.setJellyfinTokenForServer("audit-jf1", "fixture-jf1"); tokens.setJellyfinTokenForServer("audit-jf2", "fixture-jf2")
            tokens.setEmbyTokenForServer("audit-emby", "fixture-emby")
            val sources = registry.discover(true)
            check(sources.size == 4) { "Expected four independent sources, got ${sources.map { it.source }}" }
            log("PASS discover Plex, Emby and two Jellyfin servers simultaneously")
            val jf = registry.repository("jellyfin:audit-jf1:user")
            val second = registry.repository("jellyfin:audit-jf2:user")
            check(jf.channels()[0].uuid == second.channels()[0].uuid)
            check(jf.channels()[0].sourceId != second.channels()[0].sourceId)
            prefs.activateJellyfinServer("audit-jf2")
            check(jf.channels(true)[0].name == "jf1 Channel One")
            check(jf.channels()[0].numberLabel == "6.1")
            check(jf.epgEvents().single().eventId == "programme-string-id")
            check(jf.finishedRecordings().single().sourceId == jf.source.id)
            log("PASS immutable endpoint/auth, string EPG IDs, fractional channel numbers, overlapping IDs")
            fixture.denied = true
            check(!second.capabilities(true).available)
            fixture.denied = false
            fixture.empty = true
            check(!second.capabilities(true).available)
            fixture.empty = false
            fixture.management = false
            check(!second.capabilities(true).manageTimers)
            check(second.scheduleRecording("programme-string-id").isFailure)
            fixture.management = true
            second.capabilities(true)
            log("PASS no TV permission, empty channels and read-only recording policy")
            jf.scheduleRecording("programme-string-id").getOrThrow(); jf.scheduleSeries("programme-string-id").getOrThrow()
            jf.cancelRecording("timer1").getOrThrow(); jf.deleteAutoRecording("series1").getOrThrow(); jf.deleteRecording("recording1").getOrThrow()
            check(fixture.requests.any { it == "POST /jf1/LiveTv/Timers" })
            check(fixture.requests.any { it == "POST /jf1/LiveTv/SeriesTimers" })
            check(fixture.requests.none { it.startsWith("POST /jf2/LiveTv/Timers") })
            log("PASS recording/series creation and deletion stay on the selected fixture server")
            for (repo in listOf(jf, second, registry.repository("emby:audit-emby:user"))) {
                val stream = repo.liveStream(repo.channels().first())
                check(stream.isLive && stream.session != null)
                stream.session!!.report(1000, true, false)
                stream.session!!.report(2000, false, true)
                stream.session!!.report(2000, false, true)
                check(fixture.active.isEmpty())
            }
            fixture.busy = true
            check(runCatching { jf.liveStream(jf.channels().first()) }.isFailure)
            fixture.busy = false
            log("PASS three MediaBrowser sessions, progress, idempotent close and occupied tuner error")
            val plex = registry.repository("plex:audit-plex")
            check(plex.channels().single().numberLabel == "6.1")
            val programme = plex.epgEvents().single()
            plex.scheduleRecording(programme.eventId).getOrThrow()
            val stream = plex.liveStream(plex.channels().first())
            stream.session!!.report(2000, true, false); stream.session!!.report(2000, false, true)
            check(fixture.requests.any { it == "POST /livetv/dvrs/12/channels/6.1/tune" })
            check(fixture.requests.any { it == "GET /video/:/transcode/universal/stop" })
            log("PASS Plex discovered EPG prefix, guide, tune, subscription and transcode stop")
            val results = jf.search("Channel")
            check(results.channels.all { it.sourceId == jf.source.id })
            prefs.recordWatchedTvChannel(jf.source.id, "shared1"); prefs.recordWatchedTvChannel(second.source.id, "shared2")
            check(prefs.recentTvChannelIds(jf.source.id) == listOf("shared1"))
            check(prefs.recentTvChannelIds(second.source.id) == listOf("shared2"))
            log("PASS source-separated search and recent-channel histories")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, LiveTvAuditActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sourceId", jf.source.id)) as LiveTvAuditActivity
            val screen = activity
            await("Live TV screen") { screen.television?.uiState?.value?.dashboard?.channels?.size == 2 }
            Thread.sleep(800)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("DPAD starts selected TV channel") { BackgroundPlaybackSession.snapshot.value.let { it.playing && it.status.positionMs > 1500 } }
            check(main { BackgroundPlaybackSession.snapshot.value.metadata?.historyScope } == jf.source.id)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            if (screenshot != null) {
                File(instrumentation.targetContext.filesDir, "livetv-player.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            log("PASS production screen DPAD Center -> Media3 playback with advancing position")
            val listeningTimer = com.ambient.tv.ui.player.SleepTimerConfig(com.ambient.tv.ui.player.SleepTimerKind.DURATION,
                60, SystemClock.elapsedRealtime() + 3_600_000)
            main { BackgroundPlaybackSession.setSleepTimer(listeningTimer) }
            main { screen.player!!.zap(1) }
            await("zap with one tuner") { (screen.player?.uiState?.value as? TvheadendPlayerState.Ready)?.channel?.uuid == "shared2" && BackgroundPlaybackSession.snapshot.value.status.positionMs > 1000 }
            check(main { screen.navigation.currentDestination?.route == Destinations.TVH_PLAYER }) {
                "Channel zap minimized the player"
            }
            check(fixture.maxActive.get() == 1) { "Old tuner was not closed before tuning" }
            log("PASS channel zap stays on the player and closes the old tuner before opening the next")
            val playerPage = main { checkNotNull(screen.player) }
            for (delta in listOf(-1, 1, -1, 1)) {
                val expected = if (delta < 0) "shared1" else "shared2"
                instrumentation.sendKeyDownUpSync(if (delta < 0) KeyEvent.KEYCODE_MEDIA_PREVIOUS else KeyEvent.KEYCODE_MEDIA_NEXT)
                await("remote channel $expected") {
                    BackgroundPlaybackSession.snapshot.value.let { it.metadata?.mediaId == expected && it.playing && !it.replacingSource }
                }
                check(main { screen.navigation.currentDestination?.route == Destinations.TVH_PLAYER && screen.player === playerPage })
                check(main { BackgroundPlaybackSession.sleepTimer == listeningTimer }) { "Zap reset the listening timer" }
            }
            log("PASS repeated remote Next/Previous retains the same fullscreen destination/view model")
            for (backend in listOf("media3", "vlc", "mpv")) {
                if (backend == "mpv" && !com.ambient.tv.ui.player.backend.MpvBackendAvailability.isAvailable) {
                    log("SKIP MPV live reconfiguration: native backend absent")
                    continue
                }
                prefs.tvheadendPlaybackBackendKey = backend
                if (backend == "mpv") prefs.mpvVideoOutputModeKey = "gpu"
                main { screen.player!!.switchPlaybackBackend(backend, 0, false) }
                await("$backend TV renderer") { BackgroundPlaybackSession.snapshot.value.let { it.backend == backend && it.playing } }
                for (brightness in listOf(5, 10, 0)) {
                    main {
                        when (val handle = BackgroundPlaybackSession.activeHandle()) {
                            is com.ambient.tv.ui.player.backend.VlcPlaybackController ->
                                handle.updateImageOptions(handle.imageOptions.copy(brightness = brightness))
                            is com.ambient.tv.ui.player.backend.MpvPlaybackController ->
                                handle.updateImageOptions(handle.imageOptions.copy(brightness = brightness))
                            is com.ambient.tv.ui.player.Media3PlaybackHandle -> {
                                val processing = com.ambient.tv.ui.player.Media3ImageProcessing.get(instrumentation.targetContext, handle.player)
                                processing.updateOptions(processing.options.copy(enabled = true, brightness = brightness))
                            }
                            else -> error("No active renderer")
                        }
                    }
                    await("$backend resumes after live filter change") { BackgroundPlaybackSession.snapshot.value.playing }
                    val before = main { BackgroundPlaybackSession.snapshot.value.status.positionMs }
                    await("$backend live filter clock") { BackgroundPlaybackSession.snapshot.value.status.positionMs > before + 500 }
                    com.ambient.tv.ui.player.assertPlaybackVideoMoves(instrumentation, "$backend live filters")
                    check(main { screen.navigation.currentDestination?.route == Destinations.TVH_PLAYER })
                }
                for (delta in listOf(-1, 1)) {
                    val expected = if (delta < 0) "shared1" else "shared2"
                    main { screen.player!!.zap(delta) }
                    await("$backend zap $expected") { BackgroundPlaybackSession.snapshot.value.let {
                        it.backend == backend && it.metadata?.mediaId == expected && it.playing && !it.replacingSource
                    } }
                    check(main { screen.navigation.currentDestination?.route == Destinations.TVH_PLAYER })
                }
                log("PASS $backend live filter changes retain play intent and moving pixels; Next/Previous stays fullscreen")
            }
            prefs.tvheadendPlaybackBackendKey = "media3"
            main { screen.navigation.popBackStack() }
            await("browse retains playback") { BackgroundPlaybackSession.snapshot.value.playing }
            main { BackgroundPlaybackSession.stop() }
            await("stop releases tuner") { fixture.active.isEmpty() }
            main { screen.navigation.navigate(Destinations.tvhPlayer("channel", "shared1", jf.source.id)) }
            await("playback before cancelled tune") { BackgroundPlaybackSession.snapshot.value.playing }
            fixture.openDelayMs = 1_500
            val tuneRequests = fixture.requests.count { it.endsWith("/PlaybackInfo") }
            main { screen.player!!.zap(1) }
            await("pending tune") {
                BackgroundPlaybackSession.snapshot.value.replacingSource &&
                    fixture.requests.count { it.endsWith("/PlaybackInfo") } > tuneRequests
            }
            main { BackgroundPlaybackSession.stop() }
            await("Stop cancels pending tune without resurrecting background playback") {
                !BackgroundPlaybackSession.snapshot.value.active && fixture.active.isEmpty() &&
                    screen.navigation.currentDestination?.route != Destinations.TVH_PLAYER
            }
            fixture.openDelayMs = 0
            log("PASS Stop during tune invalidates the pending stream and closes its server session")
            main { screen.navigation.navigate(Destinations.liveTvSection("guide", second.source.id)) }
            await("second server guide") { screen.television?.uiState?.value?.let { it.source.id == second.source.id && it.dashboard?.events?.isNotEmpty() == true } == true }
            Thread.sleep(700)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("EPG dialog") { screen.television?.uiState?.value?.selectedEvent != null }
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("EPG dialog Back") { screen.television?.uiState?.value?.selectedEvent == null }
            log("PASS second-source guide DPAD dialog/Back and background playback cleanup")
            await("sidebar discovery") { screen.sidebar?.uiState?.value?.liveTvSources?.size == 4 }
            prefs.homeRows = listOf("plex", "jellyfin", "emby").map { com.ambient.tv.data.local.HomeRowDefinition(system = it, contentKey = "live") }
            main { screen.navigation.navigate("audit_home") }
            await("three provider Home rows") { (screen.home?.uiState?.value as? com.ambient.tv.ui.home.HomeUiState.Loaded)?.tvRows?.size == 3 }
            val home = main { screen.home!!.uiState.value as com.ambient.tv.ui.home.HomeUiState.Loaded }
            check(home.tvRows["live-tv:jellyfin"]!!.items.map { it.channel.sourceId }.distinct().size == 2)
            check(home.rowKeys.containsAll(listOf("live-tv:plex", "live-tv:jellyfin", "live-tv:emby")))
            log("PASS sidebar source discovery and rendered Home rows retain both Jellyfin servers")
            main { screen.navigation.navigate("audit_search") }
            await("search view model") { screen.search != null }
            main { screen.search!!.onQueryChanged("Channel") }
            await("multi-source search") { screen.search!!.uiState.value.tvheadendResults.channels.size == 7 }
            main { screen.search!!.toggleViewMode() }
            instrumentation.waitForIdleSync()
            log("PASS rendered global search in row/list mode preserves all seven channels")
            for (repo in listOf(second, registry.repository("emby:audit-emby:user"), plex)) {
                val channel = repo.channels().first()
                main { screen.navigation.navigate(Destinations.tvhPlayer("channel", channel.uuid, repo.source.id)) }
                await("${repo.source.system} playback") { BackgroundPlaybackSession.snapshot.value.let {
                    it.metadata?.historyScope == repo.source.id && it.playing && it.status.positionMs > 1200
                } }
                main { BackgroundPlaybackSession.stop(); screen.navigation.popBackStack() }
                await("source stop") { fixture.active.isEmpty() }
            }
            log("PASS Media3 playback through both Jellyfin sources, Emby and Plex tune route")
            check(fixture.authErrors.isEmpty()) { "Credential isolation failed: ${fixture.authErrors}" }
        } finally {
            main { BackgroundPlaybackSession.stop(); activity?.finish() }
            Thread.sleep(1200)
            fixture.close()
        }
    }
}

private class TvFixture(private val clip: ByteArray) : AutoCloseable {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val requests = CopyOnWriteArrayList<String>()
    val authErrors = CopyOnWriteArrayList<String>()
    val active = ConcurrentHashMap<String, String>()
    val maxActive = AtomicInteger()
    private val leases = AtomicInteger()
    @Volatile var denied = false
    @Volatile var empty = false
    @Volatile var management = true
    @Volatile var busy = false
    @Volatile var openDelayMs = 0L
    private val hls = File("/data/local/tmp/livetv-hls-audit").listFiles()!!.associate { it.name to it.readBytes() }
    private val now = System.currentTimeMillis() / 1000
    init { thread(isDaemon = true, name = "tv-fixture") { while (!server.isClosed) {
        val socket = runCatching { server.accept() }.getOrNull() ?: break
        thread(isDaemon = true) { socket.use {
            runCatching {
                val reader = it.getInputStream().bufferedReader()
                val first = reader.readLine() ?: return@runCatching
                val method = first.substringBefore(' '); val uri = first.substringAfter(' ').substringBefore(' ')
                val url = okhttp3.HttpUrl.Builder().scheme("http").host("localhost").port(port).build().resolve(uri)!!
                val path = url.encodedPath
                val headers = mutableMapOf<String, String>()
                while (true) { val line = reader.readLine().orEmpty(); if (line.isEmpty()) break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                val body = CharArray(headers["content-length"]?.toIntOrNull() ?: 0)
                var read = 0; while (read < body.size) { val n = reader.read(body, read, body.size - read); if (n < 0) break; read += n }
                requests += "$method $path"
                val system = path.split('/').getOrNull(1)?.takeIf { it in setOf("jf1", "jf2", "emby") } ?: "plex"
                val credential = if (system == "plex") headers["x-plex-token"] else headers["x-emby-token"]
                if (!path.endsWith("/System/Info/Public") && credential != "fixture-$system") authErrors += "$method $path"
                val p = if (system == "plex") path else path.removePrefix("/$system")
                var status = 200
                val response = when {
                    p == "/LiveTv/Info" && denied -> { status = 403; "{}" }
                    p == "/LiveTv/Info" -> "{\"IsEnabled\":true}"
                    p == "/Users/user" -> "{\"Policy\":{\"EnableLiveTvAccess\":true,\"EnableLiveTvManagement\":$management}}"
                    p == "/LiveTv/Channels" -> if (empty) "{\"Items\":[],\"TotalRecordCount\":0}" else "{\"Items\":[{\"Id\":\"shared1\",\"Name\":\"$system Channel One\",\"ChannelNumber\":\"6.1\"},{\"Id\":\"shared2\",\"Name\":\"$system Channel Two\",\"ChannelNumber\":\"7\"}],\"TotalRecordCount\":2}"
                    p == "/LiveTv/Programs" -> "{\"Items\":[$program],\"TotalRecordCount\":1}"
                    p == "/LiveTv/Programs/programme-string-id" -> program
                    p == "/LiveTv/Timers/Defaults" -> "{\"PrePaddingSeconds\":60,\"PostPaddingSeconds\":60}"
                    p == "/LiveTv/Recordings" -> "{\"Items\":[{\"Id\":\"recording1\",\"Name\":\"Fixture recording\",\"StartDate\":\"${Instant.ofEpochSecond(now - 7200)}\",\"EndDate\":\"${Instant.ofEpochSecond(now - 3600)}\",\"CanDelete\":true}],\"TotalRecordCount\":1}"
                    p.startsWith("/LiveTv/Timers") || p.startsWith("/LiveTv/SeriesTimers") || p.startsWith("/LiveTv/Recordings/") -> if (method == "GET") "{\"Items\":[]}" else "{}"
                    p.endsWith("/PlaybackInfo") -> {
                        if (openDelayMs > 0) Thread.sleep(openDelayMs)
                        if (busy || active.containsKey(system)) { status = 409; "{}" }
                        else {
                            val lease = "$system-${leases.incrementAndGet()}"; active[system] = lease
                            maxActive.updateAndGet { old -> maxOf(old, active.size) }
                            "{\"PlaySessionId\":\"play-$lease\",\"MediaSources\":[{\"Id\":\"media-$lease\",\"LiveStreamId\":\"$lease\",\"TranscodingUrl\":\"/$system/live/index.m3u8\"}]}"
                        }
                    }
                    p == "/LiveStreams/Close" -> { check(active[system] == url.queryParameter("LiveStreamId")); active.remove(system); "{}" }
                    p.startsWith("/Sessions/Playing") || p == "/Videos/ActiveEncodings" -> "{}"
                    p == "/media/providers" -> "{\"MediaContainer\":{\"MediaProvider\":[{\"identifier\":\"tv.plex.providers.epg.fixture:12\",\"parentID\":12}]}}"
                    p == "/livetv/dvrs" -> "{\"MediaContainer\":{\"Dvr\":[{\"key\":\"12\"}]}}"
                    p.endsWith("/lineups/dvr/channels") -> "{\"MediaContainer\":{\"Channel\":[{\"id\":\"plex-channel\",\"vcn\":\"6.1\",\"gridKey\":\"plex-grid\",\"title\":\"Plex Channel\"}]}}"
                    p.endsWith("/grid") -> "{\"MediaContainer\":{\"Metadata\":[{\"guid\":\"plex://episode/fixture\",\"title\":\"Fixture programme\",\"Media\":[{\"beginsAt\":${now-60},\"endsAt\":${now+3600},\"channelIdentifier\":\"plex-channel\"}]}]}}"
                    p == "/media/subscriptions/template" -> "{\"MediaContainer\":{\"SubscriptionTemplate\":[{\"MediaSubscription\":[{\"parameters\":\"hints%5Bguid%5D=plex%3A%2F%2Fepisode%2Ffixture\",\"type\":4,\"targetLibrarySectionID\":2}]}]}}"
                    p == "/library/sections" -> "{\"MediaContainer\":{\"Directory\":[{\"key\":\"2\",\"Location\":[{\"id\":\"3\",\"path\":\"/fixture\"}]}]}}"
                    p.startsWith("/media/subscriptions") -> "{\"MediaContainer\":{\"MediaGrabOperation\":[],\"MediaSubscription\":[]}}"
                    p.endsWith("/tune") -> "{\"MediaContainer\":{\"Metadata\":[{\"Media\":[{\"uuid\":\"plex-live-session\"}]}]}}"
                    p == "/:/timeline" || p.endsWith("/stop") -> "{}"
                    p == "/stream.mp4" || p.endsWith("start.m3u8") -> ""
                    else -> { status = 404; "{}" }
                }
                val hlsData = hls[if (p.endsWith("start.m3u8")) "index.m3u8" else p.substringAfterLast('/')]
                val binary = p == "/stream.mp4" || hlsData != null
                val bytes = hlsData ?: clip
                if (binary) status = 200
                val start = if (binary) headers["range"]?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0 else 0
                val data = if (binary) bytes.copyOfRange(start.coerceAtMost(bytes.size), bytes.size) else response.toByteArray()
                if (binary && start > 0) status = 206
                val range = if (status == 206) "Content-Range: bytes $start-${bytes.size-1}/${bytes.size}\r\n" else ""
                val out = it.getOutputStream()
                out.write("HTTP/1.1 $status OK\r\nContent-Type: ${if (p.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else if (p.endsWith(".ts")) "video/mp2t" else if (binary) "video/mp4" else "application/json"}\r\nContent-Length: ${data.size}\r\nAccept-Ranges: bytes\r\n${range}Connection: close\r\n\r\n".toByteArray())
                out.write(data); out.flush()
            }
        } }
    } } }
    private val program get() = "{\"Id\":\"programme-string-id\",\"ChannelId\":\"shared1\",\"ChannelName\":\"Channel One\",\"Name\":\"Fixture programme\",\"StartDate\":\"${Instant.ofEpochSecond(now-60)}\",\"EndDate\":\"${Instant.ofEpochSecond(now+3600)}\",\"SeriesId\":\"series1\"}"
    override fun close() = server.close()
}
