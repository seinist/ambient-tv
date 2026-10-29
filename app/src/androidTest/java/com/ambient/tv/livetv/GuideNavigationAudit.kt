package com.ambient.tv.livetv

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.ambient.tv.R
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.ui.tvheadend.Guide
import com.ambient.tv.ui.tvheadend.TvhFocusTarget
import com.ambient.tv.ui.tvheadend.TvheadendHeader
import com.ambient.tv.ui.tvheadend.TvheadendTab
import com.ambient.tv.ui.tvheadend.TvheadendUiState
import com.ambient.tv.ui.tvheadend.LiveTvBrowseOptionsPanel
import com.ambient.tv.ui.tvheadend.guideFilterModel
import com.ambient.tv.ui.tvheadend.rememberLiveTvBrowseControls
import com.ambient.tv.ui.common.FilterBarSetting
import com.ambient.tv.ui.common.LocalFilterBarSetting
import com.ambient.tv.ui.common.rememberPageFocusChain
import java.io.File

internal class GuideNavigationAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private var focused: String? = null
    private var watched: String? = null
    private var touchMode = false
    private val options = mutableStateOf(GuideOptions(sort = GuideSort.NAME))
    private val browse = LiveTvBrowseState()
    private val channels = ('A'..'Z').flatMap { letter -> (1..3).map { index ->
        LiveTvChannel("$letter$index", "$letter Station $index", (letter - 'A') * 3 + index, null,
            tags = listOf(if (index == 1) "Favourites" else "Other"), sourceId = "fixture",
            kind = if (index == 1) LiveTvChannelKind.RADIO else LiveTvChannelKind.TV,
            access = if (index == 3) LiveTvChannelAccess.ENCRYPTED else LiveTvChannelAccess.FTA)
    } }
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }
    }
    private fun await(label: String, predicate: () -> Boolean) {
        repeat(100) { var done = false; main { done = predicate() }; if (done) return; Thread.sleep(50) }
        error("$label; focus=$focused, options=${options.value}, position=${browse.position("guide")}")
    }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); Thread.sleep(90) }
    private fun clickText(text: String) {
        var bounds: Rect? = null
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if ((node.text?.toString()?.lines()?.any { it.trim() == text } == true || node.contentDescription?.toString() == text) && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        for (attempt in 0 until 80) {
            val node = find(instrumentation.uiAutomation.rootInActiveWindow)
            if (node != null) {
                if (!touchMode) {
                    var target: android.view.accessibility.AccessibilityNodeInfo? = node
                    while (target != null && !target.isClickable) target = target.parent
                    if (target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) {
                        Thread.sleep(350)
                        return
                    }
                }
                val candidate = Rect().also(node::getBoundsInScreen)
                if (!candidate.isEmpty) { bounds = candidate; break }
            }
            Thread.sleep(50)
        }
        if (bounds == null) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.filesDir, "guide-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val rect = requireNotNull(bounds) { "Missing visible text: $text" }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "Touch injection failed for $text at $rect" }
            event.recycle()
        }
        Thread.sleep(250)
    }
    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".guideaudit")) { "Use isolated .guideaudit application" }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        val now = System.currentTimeMillis() / 1000L
        val events = channels.filter { it.kind == LiveTvChannelKind.TV }.flatMap { channel -> (0..12).map { index ->
            LiveTvProgramme("${channel.uuid}:$index", channel.uuid, channel.name, "Programme $index",
                startEpochSeconds = now + index * 3600, stopEpochSeconds = now + (index + 1) * 3600, sourceId = "fixture")
        } }
        fun render(handheld: Boolean) = main {
            activity.setContent {
                AmbientTvTheme {
                    var filtersShown by remember { mutableStateOf(false) }
                    CompositionLocalProvider(LocalHandheldUi provides handheld,
                        LocalFilterBarSetting provides FilterBarSetting(filtersShown) { filtersShown = it },
                        androidx.tv.material3.LocalContentColor provides androidx.tv.material3.MaterialTheme.colorScheme.onSurface) {
                        val controls = rememberLiveTvBrowseControls(browse, "guide")
                        val chain = rememberPageFocusChain()
                        val model = guideFilterModel(options.value, channels.flatMap { it.tags }.distinct()) { options.value = it }
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize()) {
                            TvheadendHeader(
                                state = TvheadendUiState(configured = true, tab = TvheadendTab.GUIDE),
                                tabFocusRequesters = remember { TvheadendTab.entries.associateWith { FocusRequester() } },
                                onTab = {}, onRefresh = {}, focusChain = chain,
                                browseControls = controls, activeFilterCount = model.activeCount,
                            )
                            Guide(browse, channels, events, emptyMap(), {}, remember { FocusRequester() }, TvhFocusTarget("channel:A1", "A1"),
                                { id, _ -> focused = id }, options.value, { options.value = it }, { watched = it }, true,
                                controls = controls, pageFocusChain = chain)
                            }
                            LiveTvBrowseOptionsPanel(controls, model)
                        }
                    }
                }
            }
        }
        try {
            render(false)
            clickText(activity.getString(R.string.guide_az))
            repeat(25) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            Thread.sleep(300) // End-of-list clamping may keep earlier rows above Z.
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left returns to composed Z row") { focused == "channel:Z1" }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Radio without EPG can play") { watched == "Z1" }
            log("PASS A-Z jump across 78 virtualized channels, left-to-row focus and radio without EPG")
            clickText(activity.getString(R.string.action_view))
            clickText(activity.getString(R.string.guide_kind))
            clickText(activity.getString(R.string.guide_radio))
            await("Radio selector applied") { options.value.kind == LiveTvChannelKind.RADIO }
            clickText(activity.getString(R.string.guide_access))
            clickText(activity.getString(R.string.guide_fta))
            await("FTA selector applied") { options.value.access == LiveTvChannelAccess.FTA }
            clickText(activity.getString(R.string.guide_group))
            clickText("Favourites")
            await("Group selector applied") { options.value.tag == "Favourites" }
            key(KeyEvent.KEYCODE_BACK)
            clickText(activity.getString(R.string.guide_az))
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Filtered row receives focus") { focused?.startsWith("channel:") == true && focused?.endsWith("1") == true }
            main { options.value = options.value.copy(onlyWithEpg = true) }
            clickText(activity.getString(R.string.library_filter_reset))
            await("Empty filter can reset") { !options.value.filtersActive }
            log("PASS combined Radio + FTA + group filter, empty EPG result and reset")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(activity.filesDir, "guide-tv.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            touchMode = true
            render(true)
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            clickText(activity.getString(R.string.guide_az))
            clickText("Z")
            await("Handheld alphabet jumps") { browse.position("guide")?.itemId?.firstOrNull()?.let { it >= 'V' } == true }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(activity.filesDir, "guide-handheld.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            log("PASS handheld alphabet tap")
        } finally { main { activity.finish() } }
    }
}
