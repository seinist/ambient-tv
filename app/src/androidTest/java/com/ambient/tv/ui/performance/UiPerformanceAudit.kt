package com.ambient.tv.ui.performance

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.data.local.HomeHeroStyle
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.common.ContentRowData
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.MediaTileStyle
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.animateTvScrollToItem
import com.ambient.tv.ui.common.centerItemInViewport
import com.ambient.tv.ui.common.rememberRowCenteringState
import com.ambient.tv.ui.common.RowCenteringState
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import com.ambient.tv.ui.home.*
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.section.*
import com.ambient.tv.ui.theme.AmbientTvTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Production layouts, local artwork and real D-pad input; measurements are diagnostics, not a release benchmark. */
internal class UiPerformanceAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private val focus = AtomicReference<String?>(null)
    private val phase = AtomicReference("idle")
    private data class Sample(val phase: String, val total: Long, val layout: Long, val draw: Long,
        val delay: Long, val budget: Long, val gpu: Long, val droppedReports: Int,
        val sync: Long, val command: Long, val swap: Long, val intendedVsync: Long, val actualVsync: Long)
    private val samples = ConcurrentLinkedQueue<Sample>()
    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }
    }
    private fun await(description: String, predicate: () -> Boolean) {
        repeat(200) { if (predicate()) return; Thread.sleep(25) }
        error("Timeout: $description; focus=${focus.get()}")
    }

    fun run(browsePerformance: Boolean = false, browseMode: String = "combined",
        uiProfile: String = "balanced", uiScenario: String = "all", uiTrace: Boolean = false) {
        check(instrumentation.targetContext.packageName.endsWith(".uiperfaudit")) { "Use isolated .uiperfaudit" }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        val callbackThread = HandlerThread("UiFrameAudit").apply { start() }
        @Suppress("DEPRECATION")
        val fallbackBudget = (1_000_000_000.0 / activity.windowManager.defaultDisplay.refreshRate.coerceAtLeast(1f)).toLong()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            val tag = phase.get()
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (tag != "idle" && total > 0 && metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L
                samples.add(Sample(tag, total, metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                    metrics.getMetric(FrameMetrics.DRAW_DURATION), metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION),
                    deadline.takeIf { it > 0 } ?: fallbackBudget,
                    if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.GPU_DURATION) else -1L, dropped,
                    metrics.getMetric(FrameMetrics.SYNC_DURATION), metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                    metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION), metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                    metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)))
            }
        }
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(callbackThread.looper)) }
            if (uiTrace) android.os.Debug.startMethodTracingSampling(
                java.io.File(instrumentation.targetContext.filesDir, "ui-sampling.trace").absolutePath,
                16 * 1024 * 1024, 10_000,
            )
            if (browsePerformance) {
                if (browseMode == "ambient-visual") { auditAmbientBounds(activity); auditAmbientPause(activity) }
                else auditBrowse(activity, browseMode)
                return
            }
            val profile = PerformanceProfile.resolve(PerformanceMode.fromKey(uiProfile), lowEndDevice = false)
            log("UI_PROFILE $uiProfile focusedItemShadow=${profile.allowFocusedItemShadow} scenario=$uiScenario")
            if (uiScenario == "all") {
                auditSingleDraw(activity)
                auditIsolatedPlaceholderPulse(activity)
                auditExplicitRowScroll(activity)
                auditArtworkConstraints(activity)
            }
            val url = "android.resource://${instrumentation.targetContext.packageName}/${R.drawable.ic_ambient_tv}"
            val rows = (0 until 20).map { row -> ContentRowData("Row $row", (0 until 64).map { column -> item("$row:$column", url) },
                style = MediaTileStyle.POSTER, id = "row-$row", showDescription = false) }
            for (style in listOf(HomeHeroStyle.DEPTH, HomeHeroStyle.STATIC)) {
                if (uiScenario != "all" && uiScenario != "home" && uiScenario != "home-$style") continue
                focus.set(null)
                val state = mutableStateOf(HomeUiState.Loaded(rows, focusedRatingKey = "0:0", focusedRowId = "row-0",
                    rowKeys = rows.map { it.id }, initialFocusTarget = HomeInitialFocusTarget.CONTENT))
                val hero = MutableStateFlow<HomeHeroContent?>(HomeHeroContent("0:0", "Title 0:0", artworkUrl = url))
                main { activity.setContent {
                    androidx.compose.runtime.key(state) {
                        CompositionLocalProvider(LocalHandheldUi provides false, LocalPerformanceProfile provides profile) {
                            AmbientTvTheme {
                                LibraryContent(state.value, hero, style, 60, HomeHeroStyle.SOFT, HomeFocusSnapshot("0:0", "row-0"),
                                    onItemClick = {}, onItemFocused = { row, media ->
                                        focus.set(media.ratingKey)
                                        hero.value = HomeHeroContent(media.ratingKey, media.title, artworkUrl = url, mediaItem = media)
                                        if (state.value.initialFocusTarget != HomeInitialFocusTarget.NONE) state.value = state.value.copy(
                                            initialFocusTarget = HomeInitialFocusTarget.NONE, focusedRatingKey = media.ratingKey, focusedRowId = row)
                                    }, onOpenSettings = {}, onWatchLiveChannel = {}, onLiveTvFocused = {}, onShortcutFocused = {},
                                    onOpenHomeContent = {}, tvheadendImageHeaders = emptyMap(), entranceProgress = { 1f })
                            }
                        }
                    }
                } }
                await("Home initial media focus") { focus.get() == "0:0" }
                walk("home-$style", true, List(7) { KeyEvent.KEYCODE_DPAD_DOWN } +
                    List(16) { KeyEvent.KEYCODE_DPAD_RIGHT } + List(16) { KeyEvent.KEYCODE_DPAD_LEFT } +
                    List(7) { KeyEvent.KEYCODE_DPAD_UP })
                auditRapidNavigation("home-$style")
            }
            val libraryScenarios = listOf(
                SectionViewMode.POSTER to "library-grid",
                SectionViewMode.LANDSCAPE to "library-landscape",
                SectionViewMode.INFO_LIST to "library-info",
                SectionViewMode.CINEMATIC to "library-cinematic",
            )
            for ((viewMode, tag) in libraryScenarios) {
                if (uiScenario != "all" && uiScenario != tag) continue
                focus.set(null)
                val state = mutableStateOf(SectionUiState.Loaded("Library", SectionPage.LIBRARY, rows.flatMap { it.items },
                    emptyList(), emptyList(), SectionSort.TITLE, SectionSortOrder.ASCENDING, SectionFilter.ALL,
                    SectionQualityFilter.ALL, viewMode = viewMode, focusedRatingKey = "0:0"))
                main { activity.setContent {
                    androidx.compose.runtime.key(state) {
                        CompositionLocalProvider(LocalHandheldUi provides false, LocalPerformanceProfile provides profile) {
                            AmbientTvTheme {
                                SectionContent(state.value, MediaSystem.PLEX, onItemClick = {}, onCollectionClick = { _, _ -> },
                                    onOpenOverview = {}, onBack = null, onPage = {}, onItemFocused = { media ->
                                        focus.set(media.ratingKey); state.value = state.value.copy(focusedRatingKey = media.ratingKey)
                                    }, onRecommendationItemFocused = { _, _ -> }, onCollectionFocused = {}, onSetSort = {},
                                    onSetSortOrder = {}, onSetFilterGroup = { _, _ -> }, onResetFilters = {}, onSetView = {},
                                    onSetGridColumns = {}, onSetListRows = {}, onSelectLetter = {})
                            }
                        }
                    }
                } }
                await("Library initial media focus") { focus.get() == "0:0" }
                val keys = when (viewMode) {
                    SectionViewMode.CINEMATIC -> List(7) { KeyEvent.KEYCODE_DPAD_RIGHT } +
                        List(7) { KeyEvent.KEYCODE_DPAD_LEFT } + listOf(KeyEvent.KEYCODE_DPAD_RIGHT)
                    SectionViewMode.INFO_LIST -> List(7) { KeyEvent.KEYCODE_DPAD_DOWN } + List(7) { KeyEvent.KEYCODE_DPAD_UP }
                    SectionViewMode.LANDSCAPE -> List(7) { KeyEvent.KEYCODE_DPAD_DOWN } +
                        listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT) + List(7) { KeyEvent.KEYCODE_DPAD_UP }
                    else -> navigationKeys()
                }
                walk(tag, false, keys)
            }
            phase.set("idle")
            Thread.sleep(200)
            log("PASS production Home and library traverse new rows without losing media focus")
        } finally {
            if (uiTrace) android.os.Debug.stopMethodTracing()
            // Keep earlier measurements when a later scenario fails its focus assertion.
            phase.set("idle")
            Thread.sleep(200)
            java.io.File(instrumentation.targetContext.filesDir, if (browsePerformance) "browse-frames.csv" else "ui-frames.csv").writeText(
                "phase,total_ns,layout_ns,draw_ns,delay_ns,budget_ns,dropped_reports,sync_ns,command_ns,swap_ns,intended_vsync_ns,actual_vsync_ns\n" +
                    samples.joinToString("\n") { "${it.phase},${it.total},${it.layout},${it.draw},${it.delay},${it.budget},${it.droppedReports},${it.sync},${it.command},${it.swap},${it.intendedVsync},${it.actualVsync}" })
            samples.map { it.phase.substringBefore('/') }.distinct().forEach(::report)
            main { activity.window.removeOnFrameMetricsAvailableListener(listener); activity.finish() }
            callbackThread.quitSafely()
        }
    }

    /** Paired on-device workload: production scheduler/cache/UI, controlled metadata responses. */
    private fun auditBrowse(activity: UiCatalogActivity, mode: String) {
        val context = instrumentation.targetContext
        val deps = dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext,
            com.ambient.tv.ui.player.PlaybackAuditDependencies::class.java)
        val disk = com.ambient.tv.data.browse.BrowseDiskStore(context)
        val cache = com.ambient.tv.data.repository.PlexMediaCache().apply { configurePersistence(disk) { "perf-fixture" } }
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val url = "android.resource://${context.packageName}/${R.drawable.ic_ambient_tv}"
        val rows = (0 until 20).map { row -> ContentRowData("Row $row", (0 until 64).map { col -> item("$row:$col", url) },
            style = MediaTileStyle.POSTER, id = "row-$row", showDescription = false) }
        val fixture = rows.flatMap { it.items }.map { it.copy(summary = "Metadata content ".repeat(60)) }
        val payload = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(MediaItem.serializer()), fixture.take(100))
        val started = AtomicInteger()
        val completed = AtomicInteger()
        val cancelled = AtomicInteger()
        val priority = AtomicInteger()
        val repository = object : com.ambient.tv.domain.repository.MediaRepository by deps.mediaRepository() {
            override suspend fun getMetadataFast(ratingKey: String): Result<MediaItem?> = cache.withGeneration {
                val requestId = started.incrementAndGet()
                android.os.Trace.beginAsyncSection("browse-fetch", requestId)
                priority.set(android.os.Process.getThreadPriority(android.os.Process.myTid()))
                try {
                    kotlinx.coroutines.delay(if (ratingKey.startsWith("slow")) 2_800 else 30)
                    val decoded = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(MediaItem.serializer()), payload)
                    cache.setSectionItems("prefetch", decoded)
                    completed.incrementAndGet()
                    Result.success(decoded.first())
                } catch (e: kotlinx.coroutines.CancellationException) { cancelled.incrementAndGet(); throw e }
                finally { android.os.Trace.endAsyncSection("browse-fetch", requestId) }
            }
        }
        val prefetch = com.ambient.tv.data.browse.MetadataPrefetcher(repository, deps.sourceAvailability(), deps.browseScopes())
        val budget = com.ambient.tv.data.browse.BrowseWorkBudget
        budget.foreground = true
        val decodeStarts = AtomicInteger()
        val oldLoader = coil3.SingletonImageLoader.get(context)
        val loader = oldLoader.newBuilder().eventListener(object : coil3.EventListener() {
            override fun decodeStart(request: coil3.request.ImageRequest, decoder: coil3.decode.Decoder, options: coil3.request.Options) {
                decodeStarts.incrementAndGet()
            }
        }).build()
        coil3.SingletonImageLoader.setUnsafe(loader)
        var enabled = false
        var sequence = 0
        val state = mutableStateOf(HomeUiState.Loaded(rows, focusedRatingKey = "0:0", focusedRowId = "row-0",
            rowKeys = rows.map { it.id }, initialFocusTarget = HomeInitialFocusTarget.CONTENT))
        val hero = MutableStateFlow<HomeHeroContent?>(HomeHeroContent("0:0", "Title 0:0", artworkUrl = url))
        try {
            main { activity.setContent {
                CompositionLocalProvider(LocalHandheldUi provides false, LocalPerformanceProfile provides PerformanceProfile.Full) {
                    AmbientTvTheme {
                        // UiCatalogActivity does not have MainActivity's input hook; mirror that hook here.
                        Box(Modifier.fillMaxSize().onPreviewKeyEventForBrowseAudit { budget.interact() }) {
                            LibraryContent(state.value, hero, HomeHeroStyle.DEPTH, 60, HomeHeroStyle.SOFT,
                                HomeFocusSnapshot("0:0", "row-0"), onItemClick = {}, onItemFocused = { row, media ->
                                    focus.set(media.ratingKey)
                                    hero.value = HomeHeroContent(media.ratingKey, media.title, artworkUrl = url, mediaItem = media)
                                    if (state.value.initialFocusTarget != HomeInitialFocusTarget.NONE) state.value = state.value.copy(
                                        initialFocusTarget = HomeInitialFocusTarget.NONE, focusedRatingKey = media.ratingKey, focusedRowId = row)
                                    if (enabled) prefetch.detail("focus-${sequence++}")
                                }, onOpenSettings = {}, onWatchLiveChannel = {}, onLiveTvFocused = {}, onShortcutFocused = {},
                                onOpenHomeContent = {}, tvheadendImageHeaders = emptyMap(), entranceProgress = { 1f })
                        }
                    }
                }
            } }
            await("browse initial focus") { focus.get() == "0:0" }
            val keys = List(4) { KeyEvent.KEYCODE_DPAD_DOWN } + List(12) { KeyEvent.KEYCODE_DPAD_RIGHT } +
                List(12) { KeyEvent.KEYCODE_DPAD_LEFT } + List(4) { KeyEvent.KEYCODE_DPAD_UP }
            walk("browse-warmup", true, keys)
            Thread.sleep(2_000)
            val beforeDecode = decodeStarts.get()
            prefetch.detail("initial-complete")
            await("idle metadata completion") { completed.get() > 0 }
            check(priority.get() >= android.os.Process.THREAD_PRIORITY_BACKGROUND)
            check(decodeStarts.get() == beforeDecode) { "Idle metadata prefetch caused an image decode" }
            log("PASS idle prefetch runs at background priority=${priority.get()} with zero image decodes")
            val order = if (mode == "split") listOf("baseline", "prefetch-only", "disk-only", "prefetch",
                "prefetch", "disk-only", "prefetch-only", "baseline") else listOf("baseline", "prefetch", "prefetch", "baseline")
            for ((index, workload) in order.withIndex()) {
                val on = workload.startsWith("prefetch")
                val write = workload in setOf("prefetch", "disk-only")
                enabled = on
                Thread.sleep(2_500)
                if (on) {
                    val count = started.get()
                    prefetch.detail("slow-$index")
                    await("slow prefetch start") { started.get() > count }
                }
                if (write) cache.setSectionItems("large-$index", fixture)
                val startsBefore = started.get()
                val cancelsBefore = cancelled.get()
                val tag = "browse-$workload-$index"
                auditShell("dumpsys gfxinfo ${context.packageName} reset")
                walk(tag, true, keys)
                java.io.File(context.filesDir, "$tag-gfxinfo.txt").writeText(auditShell("dumpsys gfxinfo ${context.packageName} framestats"))
                if (on) {
                    check(cancelled.get() > cancelsBefore) { "Input did not cancel speculative request" }
                    check(started.get() == startsBefore) { "Speculation started during continuous D-pad input" }
                    log("PASS $tag input cancels active request; no speculative starts during navigation")
                }
            }
            enabled = false
            budget.interact()
            phase.set("browse-refetch/update")
            repeat(12) { update ->
                main {
                    val incoming = rows.map { row -> row.copy(items = row.items.filterNot { it.ratingKey.endsWith(":63") }
                        .map { if (it.ratingKey.endsWith(":4")) it.copy(title = "Update $update") else it }) }
                    state.value = state.value.copy(rows = incoming)
                }
                Thread.sleep(400)
            }
            phase.set("idle")
            log("PASS 12 authoritative list updates retain media focus=${focus.get()}")
            Thread.sleep(2_500)
            val persisted = kotlinx.coroutines.runBlocking { disk.read("perf-fixture", "section:large-${if (mode == "split") 5 else 2}") }
            check(persisted.isNotEmpty()) { "Queued disk snapshot never persisted" }
            log("PASS deferred ${fixture.size}-item snapshot persisted after navigation")
        } finally {
            enabled = false
            budget.foreground = false
            budget.interact()
            main { activity.setContent { Box(Modifier.fillMaxSize()) } }
            coil3.SingletonImageLoader.setUnsafe(oldLoader)
            loader.shutdown()
        }
    }

    private fun auditAmbientPause(activity: UiCatalogActivity) {
        val reference = AtomicReference<androidx.compose.runtime.State<Float>?>(null)
        main { activity.setContent { reference.set(rememberIdleAmbientPhase(1_000)) } }
        await("ambient starts while idle") { (reference.get()?.value ?: 0f) > 0.05f }
        val phase = checkNotNull(reference.get())
        com.ambient.tv.data.browse.BrowseWorkBudget.interact()
        Thread.sleep(100)
        val paused = phase.value
        Thread.sleep(250)
        check(phase.value == paused) { "Ambient phase kept animating during input" }
        await("ambient resumes after quiet interval") { phase.value != paused }
        check(kotlin.math.abs(phase.value - paused) < 0.15f) { "Ambient jumped forward after input" }
        log("PASS ambient pauses during navigation and resumes without a phase jump")
    }

    private fun auditAmbientBounds(activity: UiCatalogActivity) {
        val cases = listOf(HomeHeroStyle.DEPTH to 0f, HomeHeroStyle.DEPTH to 0.5f, HomeHeroStyle.DEPTH to 1f,
            HomeHeroStyle.SWEEP to 0f, HomeHeroStyle.SWEEP to 0.5f, HomeHeroStyle.SWEEP to 1f,
            HomeHeroStyle.ARTWORK_COLOR to 0.5f, HomeHeroStyle.STATIC to 0.5f)
        for ((style, phase) in cases) {
            fun draw(bounded: Boolean): android.graphics.Bitmap {
                main { activity.setContent {
                    Box(Modifier.fillMaxSize().homeHeroAmbient(Color(0xFFFFB000), Color(0xFF5080FF),
                        { Color(0xFFCC4488) }, style, { phase }, 1f, 1f, 1.32f, boundedPaint = bounded))
                } }
                instrumentation.waitForIdleSync()
                Thread.sleep(250)
                return checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            }
            val before = draw(false)
            val after = draw(true)
            try {
                check(before.width == after.width && before.height == after.height)
                val first = IntArray(before.width)
                val second = IntArray(before.width)
                var mismatches = 0
                var checked = 0
                for (y in 0 until before.height step 4) {
                    before.getPixels(first, 0, before.width, 0, y, before.width, 1)
                    after.getPixels(second, 0, after.width, 0, y, after.width, 1)
                    for (x in 0 until before.width step 4) {
                        checked++
                        if (listOf(0, 8, 16, 24).any { shift ->
                            kotlin.math.abs(((first[x] ushr shift) and 255) - ((second[x] ushr shift) and 255)) > 1
                        }) mismatches++
                    }
                }
                check(mismatches == 0) { "$style at $phase changed $mismatches/$checked sampled pixels" }
                log("PASS bounded ambient $style phase=$phase matches full-surface reference ($checked sampled pixels)")
            } finally { before.recycle(); after.recycle() }
        }
    }

    private fun auditShell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }

    private fun auditSingleDraw(activity: UiCatalogActivity) {
        val passes = AtomicInteger()
        val childDraws = AtomicInteger()
        main { activity.setContent {
            Box(Modifier.fillMaxSize().drawWithContent { passes.incrementAndGet(); drawContent() }
                .fadeRowsBehindHomeHero(true).drawWithContent { childDraws.incrementAndGet(); drawContent(); drawRect(Color.Blue) })
        } }
        await("mask frame") { passes.get() > 0 }
        main { check(passes.get() == childDraws.get()) { "Mask drew its child tree multiple times" } }
        log("PASS Home mask records its child draw tree once per draw pass")
    }

    private fun auditIsolatedPlaceholderPulse(activity: UiCatalogActivity) {
        val parentDraws = AtomicInteger()
        main { activity.setContent {
            CompositionLocalProvider(LocalPerformanceProfile provides PerformanceProfile.Full) {
                AmbientTvTheme {
                    Box(Modifier.fillMaxSize().drawWithContent {
                        drawRect(Color.Black)
                        drawContent()
                    }.fadeRowsBehindHomeHero(true).drawWithContent {
                        parentDraws.incrementAndGet()
                        drawContent()
                    }) {
                        Box(Modifier.align(Alignment.Center).size(96.dp).mediaFocusMotion(false)) {
                            com.ambient.tv.ui.common.PulsingArtworkPlaceholder()
                        }
                    }
                }
            }
        } }
        await("placeholder draw") { parentDraws.get() > 0 }
        Thread.sleep(400)
        val initialDraws = parentDraws.get()
        val brightness = (0 until 8).map {
            Thread.sleep(250)
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val pixel = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
            screenshot.recycle()
            android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
        }
        check(brightness.max() - brightness.min() >= 3) { "Placeholder pulse is frozen behind cached layers: $brightness" }
        check(parentDraws.get() == initialDraws) {
            "Placeholder pulse re-recorded its parent list: $initialDraws -> ${parentDraws.get()}"
        }
        log("PASS placeholder visibly pulses through Home mask and focus layer without redrawing the parent list")
        main { activity.setContent { Box(Modifier.fillMaxSize()) } }
    }

    private fun auditArtworkConstraints(activity: UiCatalogActivity) {
        val context = instrumentation.targetContext
        val file = java.io.File(context.cacheDir, "ui-audit-large-artwork.png")
        val bitmap = android.graphics.Bitmap.createBitmap(1600, 2400, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.BLUE)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val results = java.util.concurrent.CopyOnWriteArrayList<coil3.request.SuccessResult>()
        val previous = coil3.SingletonImageLoader.get(context)
        val loader = previous.newBuilder().eventListener(object : coil3.EventListener() {
            override fun onSuccess(request: coil3.request.ImageRequest, result: coil3.request.SuccessResult) {
                if (request.data == file) results.add(result)
            }
        }).build()
        coil3.SingletonImageLoader.setUnsafe(loader)
        try {
            main { activity.setContent {
                Box(Modifier.fillMaxSize()) {
                    com.ambient.tv.ui.common.ArtworkImage(file, "Sized artwork",
                        Modifier.fillMaxWidth(0.1f).height(120.dp))
                }
            } }
            await("constraint-sized artwork") { results.isNotEmpty() }
            Thread.sleep(250)
            check(results.size == 1) { "Artwork request restarted after layout" }
            val drawable = results.single().image
            check(drawable.width in 1..800 && drawable.height in 1..1200) {
                "Artwork decoded original dimensions: ${drawable.width}x${drawable.height}"
            }
            log("PASS artwork uses view constraints without restarting the request or decoding the full image")
        } finally {
            main { activity.setContent { Box(Modifier.fillMaxSize()) } }
            coil3.SingletonImageLoader.setUnsafe(previous)
            loader.shutdown()
            file.delete()
        }
    }

    private fun navigationKeys() = List(7) { KeyEvent.KEYCODE_DPAD_DOWN } +
        List(5) { KeyEvent.KEYCODE_DPAD_RIGHT } + List(7) { KeyEvent.KEYCODE_DPAD_UP }

    private fun auditExplicitRowScroll(activity: UiCatalogActivity) {
        val state = LazyListState()
        lateinit var rowCentering: RowCenteringState
        lateinit var animationScope: kotlinx.coroutines.CoroutineScope
        main { activity.setContent {
            animationScope = androidx.compose.runtime.rememberCoroutineScope()
            rowCentering = rememberRowCenteringState(state)
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(20) { index ->
                    Box(Modifier.fillMaxWidth().height(if (index % 2 == 0) 96.dp else 208.dp))
                }
            }
        } }
        await("mixed-height row layout") { state.layoutInfo.visibleItemsInfo.isNotEmpty() }
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(animationScope.coroutineContext) {
                state.animateTvScrollToItem(12, 32)
                check(kotlin.math.abs(state.layoutInfo.visibleItemsInfo.first { it.index == 12 }.offset + 32) <= 2)
                state.animateTvScrollToItem(2)
                check(kotlin.math.abs(state.layoutInfo.visibleItemsInfo.first { it.index == 2 }.offset) <= 2)
                state.centerItemInViewport(11, topInset = 80)
                val info = state.layoutInfo
                val row = info.visibleItemsInfo.first { it.index == 11 }
                val center = (info.viewportStartOffset + 80 + info.viewportEndOffset) / 2
                check(kotlin.math.abs(row.offset + row.size / 2 - center) <= 2)
                // Interrupt in-flight motion with both same-direction and reverse targets.
                state.scrollToItem(2)
                var move = launch { rowCentering.centerItem(7, topInset = 80) }
                kotlinx.coroutines.delay(80)
                move.cancelAndJoin()
                val interruptedIndex = state.firstVisibleItemIndex
                val interruptedOffset = state.firstVisibleItemScrollOffset
                move = launch { rowCentering.centerItem(10, topInset = 80) }
                androidx.compose.runtime.withFrameNanos { }
                kotlinx.coroutines.delay(1) // Observe after the animation's callback for that frame.
                check(state.firstVisibleItemIndex != interruptedIndex ||
                    state.firstVisibleItemScrollOffset != interruptedOffset) {
                    "Retarget inserted a stationary frame instead of continuing the scroll"
                }
                kotlinx.coroutines.delay(80)
                move.cancelAndJoin()
                rowCentering.centerItem(4, topInset = 80)
                val finalInfo = state.layoutInfo
                val finalRow = finalInfo.visibleItemsInfo.first { it.index == 4 }
                val finalCenter = (finalInfo.viewportStartOffset + 80 + finalInfo.viewportEndOffset) / 2
                check(kotlin.math.abs(finalRow.offset + finalRow.size / 2 - finalCenter) <= 2)
                rowCentering.centerItem(0, topInset = 80)
                check(!state.canScrollBackward)
                rowCentering.centerItem(19, topInset = 80)
                check(!state.canScrollForward)

            }
        }
        log("PASS soft explicit scroll reaches mixed-height rows in both directions and centers below the hero")
    }

    private fun walk(tag: String, home: Boolean, keys: List<Int> = navigationKeys()) {
        Thread.sleep(1_500) // Exclude entrance and first image decode; cold-cache/network needs a separate device run.
        val inputNs = mutableListOf<Long>()
        android.os.Trace.beginSection(tag)
        for (key in keys) {
            val direction = when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                KeyEvent.KEYCODE_DPAD_UP -> "up"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                else -> "left"
            }
            phase.set("$tag/$direction")
            val before = focus.get()
            val start = System.nanoTime()
            instrumentation.sendKeyDownUpSync(key)
            await("$tag focus after key $key") { focus.get() != null && focus.get() != before }
            val after = checkNotNull(focus.get())
            if (home && key == KeyEvent.KEYCODE_DPAD_DOWN) check(after.substringBefore(':') != before?.substringBefore(':'))
            inputNs += System.nanoTime() - start
            log("STEP $tag/$direction $before -> $after elapsedMs=${inputNs.last() / 1_000_000.0}")
            Thread.sleep(350)
            check(focus.get() == after) { "$tag focus changed again without a new key: $after -> ${focus.get()}" }
        }
        phase.set("idle")
        android.os.Trace.endSection()
        log("METRIC $tag injected-key-to-observed-focus p95Ms=${percentile(inputNs, .95) / 1_000_000.0}; includes injection/poll overhead")
    }

    private fun auditRapidNavigation(tag: String) {
        Thread.sleep(500)
        for (key in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT)) {
            phase.set("$tag-rapid/${if (key == KeyEvent.KEYCODE_DPAD_RIGHT) "right" else "left"}")
            repeat(16) {
                val before = checkNotNull(focus.get())
                instrumentation.sendKeyDownUpSync(key)
                await("rapid focus move") { focus.get() != before }
                val after = checkNotNull(focus.get())
                check(after.substringBefore(':') == before.substringBefore(':')) { "Rapid horizontal input left its row" }
                Thread.sleep(80)
            }
        }
        for (key in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT)) {
            phase.set("$tag-held/${if (key == KeyEvent.KEYCODE_DPAD_RIGHT) "right" else "left"}")
            val initial = checkNotNull(focus.get())
            val downTime = android.os.SystemClock.uptimeMillis()
            // Cross the acceleration threshold, then reverse direction without a queued tail.
            repeat(if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 32 else 20) { count ->
                instrumentation.sendKeySync(KeyEvent(downTime, android.os.SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN, key, count, 0, -1, 0, 0, android.view.InputDevice.SOURCE_DPAD))
                Thread.sleep(80)
                check(focus.get()?.substringBefore(':') == initial.substringBefore(':')) { "Held input left its row" }
            }
            instrumentation.sendKeySync(KeyEvent(downTime, android.os.SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, key, 0, 0, -1, 0, 0, android.view.InputDevice.SOURCE_DPAD))
            val released = focus.get()
            check(released != initial) { "Held direction did not advance focus" }
            Thread.sleep(400)
            check(focus.get() == released) { "Focus continued moving after key release" }
        }
        phase.set("idle")
        log("PASS $tag rapid taps, held repeats, reversal and immediate stop on release")
    }

    private fun report(tag: String) {
        val frames = samples.filter { it.phase.startsWith("$tag/") }
        check(frames.isNotEmpty()) { "No FrameMetrics for $tag" }
        fun p95(field: (Sample) -> Long) = percentile(frames.map(field).filter { it >= 0 }, .95) / 1_000_000.0
        log(String.format(Locale.ROOT,
            "METRIC %s frames=%d totalP95Ms=%.3f layoutP95Ms=%.3f drawP95Ms=%.3f mainDelayP95Ms=%.3f overBudget=%d droppedReports=%d",
            tag, frames.size, p95 { it.total }, p95 { it.layout }, p95 { it.draw }, p95 { it.delay },
            frames.count { it.total > it.budget }, frames.sumOf { it.droppedReports }))
        if (frames.any { it.gpu >= 0 }) log("METRIC $tag gpuP95Ms=${p95 { it.gpu }}")
        for (direction in listOf("down", "up", "right", "left")) {
            val directional = frames.filter { it.phase == "$tag/$direction" }
            if (directional.isNotEmpty()) log("METRIC $tag/$direction frames=${directional.size} " +
                "totalP95Ms=${percentile(directional.map { it.total }, .95) / 1_000_000.0} " +
                "overBudget=${directional.count { it.total > it.budget }}")
        }
        log("PASS FrameMetrics captured for $tag; no device-independent performance threshold asserted")
    }

    private fun percentile(values: List<Long>, fraction: Double): Long = values.sorted().let { sorted ->
        if (sorted.isEmpty()) -1L else sorted[(kotlin.math.ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)]
    }

    private fun item(key: String, art: String) = MediaItem(ratingKey = key, title = "Title $key", type = "movie",
        index = null, thumbUrl = art, artUrl = art, year = 2026, summary = null, tagline = null,
        durationMs = 5_400_000L, viewOffsetMs = null, parentTitle = null, parentRatingKey = null,
        grandparentTitle = null, grandparentRatingKey = null, contentRating = "12", rating = 8.0, studio = null)
}

private fun Modifier.onPreviewKeyEventForBrowseAudit(action: () -> Unit): Modifier =
    this.then(Modifier.onPreviewKeyEvent { action(); false })
