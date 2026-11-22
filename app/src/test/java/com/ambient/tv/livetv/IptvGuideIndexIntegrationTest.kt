package com.ambient.tv.livetv

import android.app.Application
import com.ambient.tv.data.iptv.IptvSource
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class IptvGuideIndexIntegrationTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val docs = ConcurrentHashMap<String, String>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val beforeGuide = AtomicReference<() -> Unit>({})
    private lateinit var server: HttpServer
    private lateinit var index: ProgrammeGuideIndex
    private lateinit var dir: java.io.File
    private lateinit var base: String
    private lateinit var config: IptvSource
    private var now = 0L
    private val date = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

    @Before fun before() {
        context.deleteDatabase("programme-guides-v1.db")
        index = ProgrammeGuideIndex(context)
        dir = kotlin.io.path.createTempDirectory("iptv-guide-index").toFile()
        now = Instant.now().epochSecond
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:${server.address.port}"
        listOf("/list", "/guide", "/second").forEach { path ->
            server.createContext(path) { exchange ->
                counts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                if (path != "/list") beforeGuide.get().invoke()
                val bytes = docs[path].orEmpty().toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        docs["/list"] = playlist()
        docs["/guide"] = guide(programme("c1", "One", -60, 3600) + programme("c2", "Two", -60, 3600) +
            programme("c1", "Later", 3600, 7200))
        config = IptvSource(id = "provider", name = "Provider", playlistUrl = "$base/list", epgUrl = "$base/guide")
        server.start()
    }
    @After fun after() {
        beforeGuide.set({})
        server.stop(0); index.close(); dir.deleteRecursively(); context.deleteDatabase("programme-guides-v1.db")
    }
    private fun playlist(guides: String = "") = "#EXTM3U $guides\n#EXTINF:-1 tvg-id=\"c1\",One\n$base/live1\n#EXTINF:-1 tvg-id=\"c2\",Two\n$base/live2\n"
    private fun programme(channel: String, title: String, from: Long, to: Long) =
        "<programme channel=\"$channel\" start=\"${date.format(Instant.ofEpochSecond(now + from))}\" stop=\"${date.format(Instant.ofEpochSecond(now + to))}\"><title>$title</title></programme>"
    private fun guide(programmes: String) = "<tv><channel id=\"c1\"><display-name>One</display-name></channel><channel id=\"c2\"><display-name>Two</display-name></channel>$programmes</tv>"
    private fun repository(value: IptvSource = config, valid: () -> Boolean = { true }) =
        IptvLiveTvRepository(LiveTvSource("iptv:${value.id}", "iptv", value.name), value, OkHttpClient(), dir, index, valid)

    @Test fun restartAndChannelQueriesUseThePersistedIndexWithoutReopeningOrDownloadingXml() = runBlocking {
        val first = repository()
        assertEquals(listOf("One", "Two"), first.epgEvents(now, 1, false).map { it.title })
        assertEquals(1, counts["/guide"]?.get())
        dir.listFiles().orEmpty().filter { it.extension == "xml" }.forEach { assertTrue(it.delete()) }
        index.close(); index = ProgrammeGuideIndex(context)
        val restarted = repository(config.copy(name = "Renamed label"))
        val channel = restarted.channels().first { it.name == "One" }
        assertEquals(listOf("One"), restarted.channelEpgEvents(channel.uuid, now, 1).map { it.title })
        assertEquals(listOf("Later"), restarted.channelEpgEvents(channel.uuid, now + 3600, 1).map { it.title })
        assertEquals(1, counts["/guide"]?.get())
    }

    @Test fun truncatedForcedRefreshRetainsLastGoodGenerationAndCanRecoverImmediatelyOnNextManualRefresh() = runBlocking {
        val repo = repository()
        val original = repo.epgEvents(now, 1, false)
        docs["/guide"] = "<tv>" + (0 until 300).joinToString("") { programme("c1", "Partial $it", it.toLong(), it + 60L) }
        assertEquals(original, repo.epgEvents(now, 1, true))
        assertTrue(repo.servingCachedData)
        docs["/guide"] = guide(programme("c1", "Recovered", -60, 3600))
        assertEquals(listOf("Recovered"), repo.epgEvents(now, 1, true).map { it.title })
        assertFalse(repo.servingCachedData)
    }

    @Test fun malformedPlaylistResponseCannotReplaceTheGuideScopeOrLastGoodRawPlaylist() = runBlocking {
        val repo = repository()
        val original = repo.epgEvents(now, 1, false)
        docs["/list"] = "<html><body>Provider temporarily unavailable</body></html>"
        assertEquals(2, repo.channels(force = true).size)
        assertEquals(original, repo.epgEvents(now, 1, false))
        assertTrue(repo.servingCachedData)
        assertEquals(2, repository().channels().size)
        assertEquals(1, counts["/guide"]?.get())
    }

    @Test fun failureOfSecondFeedCannotPublishTheFirstFeedsPartialReplacement() = runBlocking {
        docs["/list"] = playlist("url-tvg=\"$base/guide,$base/second\"")
        docs["/guide"] = guide(programme("c1", "Old one", -60, 3600))
        docs["/second"] = guide(programme("c2", "Old two", -60, 3600))
        val repo = repository(config.copy(epgUrl = ""))
        assertEquals(listOf("Old one", "Old two"), repo.epgEvents(now, 1, false).map { it.title })
        docs["/guide"] = guide(programme("c1", "New one", -60, 3600))
        docs["/second"] = "<tv><programme"
        assertEquals(listOf("Old one", "Old two"), repo.epgEvents(now, 1, true).map { it.title })
        assertTrue(repo.servingCachedData)
    }

    @Test fun changingSourceDuringDownloadRejectsTheResultAndDoesNotReplaceItsOldGuide() = runBlocking {
        val valid = AtomicBoolean(true)
        val repo = repository(valid = valid::get)
        val old = repo.epgEvents(now, 1, false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        beforeGuide.set { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        docs["/guide"] = guide(programme("c1", "Obsolete", -60, 3600))
        val refresh = async(Dispatchers.Default) { runCatching { repo.epgEvents(now, 1, true) } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            valid.set(false); release.countDown()
            assertTrue(withTimeout(5_000) { refresh.await() }.isFailure)
        } finally { release.countDown(); beforeGuide.set({}) }
        assertEquals(old, repository().epgEvents(now, 1, false))
    }
}
