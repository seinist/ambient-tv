package com.ambient.tv.ui.common

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.tv.material3.Text
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme

/** Real D-pad events through the production list/settings/modal shells, without server access. */
internal class ScrollListFocusAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private var focused: String? = null
    private val focusHistory = mutableListOf<String>()
    private val bounds = mutableMapOf<String, Rect>()
    private fun mark(id: String) = Modifier.onGloballyPositioned { bounds[id] = it.boundsInWindow() }.onFocusChanged {
        if (it.isFocused) { focused = id; focusHistory += id }
        else if (focused == id) focused = null
    }
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun awaitFocus(id: String) {
        repeat(100) {
            var matches = false
            main { matches = focused == id }
            if (matches) return
            Thread.sleep(50)
        }
        error("Expected $id; focused=$focused; history=${focusHistory.takeLast(8)}")
    }
    private fun requestFocus(requester: FocusRequester) {
        repeat(100) {
            var accepted = false
            main { accepted = runCatching { requester.requestFocus() }.getOrDefault(false) }
            if (accepted) return
            Thread.sleep(50)
        }
        error("Focus target did not become ready after layout")
    }
    private fun key(code: Int, expected: String? = null) {
        instrumentation.sendKeyDownUpSync(code)
        if (expected != null) awaitFocus(expected) else {
            Thread.sleep(100)
            instrumentation.waitForIdleSync()
        }
    }

    fun run(dialogsOnly: Boolean = false) {
        check(instrumentation.targetContext.packageName.let { it.endsWith(".settingsaudit") || it.endsWith(".focusaudit") || it.endsWith(".settingsaudit") || it.endsWith(".selectaudit") || it.endsWith(".backaudit") }) {
            "Use an isolated .focusaudit/.selectaudit/.backaudit application"
        }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        try {
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            if (dialogsOnly) {
                auditDialog(activity)
                auditDialogButtonRows(activity)
                return
            }
            auditFocusScaleEdges(activity)
            auditScrollbarEntry(activity, grid = false)
            auditScrollbarEntry(activity, grid = true)
            auditScrollbarEntry(activity, composed = true)
            auditPlayerPanelScrollbar(activity)
            auditScrollbar(activity)
            auditGridScrollbar(activity)
            auditLazyList(activity, settings = true)
            auditLazyList(activity, settings = false)
            auditSettingsControls(activity)
            auditDetailOverlay(activity)
            auditNestedSettings(activity)
            auditGrid(activity)
            auditGrid(activity, reverse = true)
            auditGridHeader(activity, withCategories = false)
            auditGridHeader(activity, withCategories = true)
            auditGridHeader(activity, withCategories = false, list = true)
            auditGridHeader(activity, withCategories = true, list = true)
            auditComposedColumn(activity)
            auditDialog(activity)
            auditDialogButtonRows(activity)
            auditHandheld(activity)
        } finally { main { activity.finish() } }
    }

    private fun auditFocusScaleEdges(activity: UiCatalogActivity) {
        val first = FocusRequester()
        var viewport = Rect.Zero
        val cards = mutableMapOf<Int, androidx.compose.ui.layout.LayoutCoordinates>()
        fun cardBounds(index: Int): Rect {
            val coordinates = cards.getValue(index)
            val top = coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
            val bottom = coordinates.localToWindow(androidx.compose.ui.geometry.Offset(0f, coordinates.size.height.toFloat()))
            return Rect(top.x, top.y, bottom.x, bottom.y)
        }
        main {
            focused = null
            activity.setContent {
                AmbientTvTheme {
                    TvLazyColumn(
                        modifier = Modifier.fillMaxWidth().height(260.dp).padding(24.dp)
                            .onGloballyPositioned { viewport = it.boundsInWindow() },
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(6) { index ->
                            val active = remember { mutableStateOf(false) }
                            TvActionButton("Scaled card $index", {},
                                Modifier.fillMaxWidth().height(100.dp)
                                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                    .mediaFocusMotion(active.value, focusedScale = 1.08f)
                                    .onGloballyPositioned { cards[index] = it }
                                    .onFocusChanged { active.value = it.isFocused }
                                    .then(mark("scaled:$index")), fillWidth = true)
                        }
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
        requestFocus(first)
        awaitFocus("scaled:0")
        Thread.sleep(700)
        main { val card = cardBounds(0); check(card.top >= viewport.top) { "First scaled card clipped: $card in $viewport" } }
        repeat(5) { index -> key(KeyEvent.KEYCODE_DPAD_DOWN, "scaled:${index + 1}") }
        Thread.sleep(900)
        main { val card = cardBounds(5); check(card.bottom <= viewport.bottom) { "Last scaled card clipped: $card in $viewport" } }
        log("Scaled focus cards remain inside both viewport edges")
    }

    private fun auditScrollbarEntry(activity: UiCatalogActivity, grid: Boolean = false, composed: Boolean = false) {
        val header = FocusRequester()
        val first = FocusRequester()
        main {
            focused = null
            activity.setContent {
                AmbientTvTheme {
                    Column(Modifier.fillMaxSize().padding(32.dp)) {
                        PageHeader(actions = {
                            TvActionButton("Actions", {}, mark("bar-actions").focusRequester(header))
                        }) { Text("Scrollbar navigation") }
                        val list = androidx.compose.foundation.lazy.rememberLazyListState()
                        val tiles = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                        val scroll = rememberScrollState()
                        val contentFocus = remember { FocusRequester() }
                        val row: @androidx.compose.runtime.Composable (Int) -> Unit = { index ->
                            TvActionButton("Row $index", {}, mark("entry:$index")
                                .then(if (index == (if (grid) 1 else 0)) Modifier.focusRequester(first) else Modifier)
                                .fillMaxWidth().height(60.dp), fillWidth = true)
                        }
                        if (composed) Box(Modifier.weight(1f).fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(end = 16.dp)
                                .focusRequester(contentFocus).focusGroup()
                                .wrapVerticalScrollFocus(scroll).verticalScroll(scroll)) {
                                repeat(40) { row(it) }
                            }
                            OverlayScrollbar(scroll, Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
                                .fillMaxHeight(), contentFocus = contentFocus)
                        } else if (grid) TvLazyVerticalGrid(GridCells.Fixed(2), state = tiles,
                            modifier = Modifier.weight(1f).overlayLazyGridScrollbar(tiles)) {
                            items(40) { row(it) }
                        } else TvLazyColumn(state = list,
                            modifier = Modifier.weight(1f).overlayLazyScrollbar(list)) {
                            items(40) { row(it) }
                        }
                    }
                }
            }
        }
        Thread.sleep(700)
        requestFocus(first)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        main { check(focused == null) { "Expected scrollbar, got $focused" } }
        key(KeyEvent.KEYCODE_DPAD_RIGHT, "bar-actions")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        main { check(focused?.startsWith("entry:") == true) { "Down from actions entered scrollbar: $focused" } }
        requestFocus(first)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        main { check(focused?.startsWith("entry:") == true) }
        log("PASS scrollbar entry (grid=$grid, composed=$composed): Right reaches actions, Down/Left enter content")
    }

    private fun auditPlayerPanelScrollbar(activity: UiCatalogActivity) {
        val first = FocusRequester()
        var backCalls = 0
        main {
            focused = null
            activity.setContent {
                AmbientTvTheme {
                    UnifiedPlayerSidePanel(title = "Video / Audio", onBack = { backCalls++ }) {
                        repeat(40) { index ->
                            TvActionButton("Setting $index", {}, mark("av:$index")
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                .fillMaxWidth().height(60.dp), fillWidth = true)
                        }
                    }
                }
            }
        }
        Thread.sleep(700)
        requestFocus(first)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        main { check(focused?.startsWith("av:") == true) { "Panel Left did not return to settings: $focused" } }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        main { check(backCalls == 1) { "Right from Video/Audio scrollbar did not reach bottom Back" } }
        log("PASS player Video/Audio panel scrollbar: Left returns to settings; Right activates bottom Back")
    }

    private fun auditScrollbar(activity: UiCatalogActivity) {
        val first = FocusRequester()
        lateinit var state: LazyListState
        main {
            focused = null
            activity.setContent {
                AmbientTvTheme {
                    state = androidx.compose.foundation.lazy.rememberLazyListState()
                    TvLazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(40.dp).overlayLazyScrollbar(state)) {
                        items(100) { index ->
                            TvActionButton("Paging row $index", {}, modifier = mark("paging:$index")
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                .fillMaxWidth().height(60.dp), fillWidth = true)
                        }
                    }
                }
            }
        }
        Thread.sleep(700)
        requestFocus(first)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        main { check(focused == null) { "Right did not focus the scrollbar: $focused" } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        Thread.sleep(500)
        main {
            val viewport = state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset
            val height = state.layoutInfo.visibleItemsInfo.first().size
            val offset = state.firstVisibleItemIndex * height + state.firstVisibleItemScrollOffset
            check(kotlin.math.abs(offset - viewport) <= 2) { "Expected one page ($viewport px), got $offset" }
            check(focused == null) { "Paging moved focus into content" }
        }
        key(KeyEvent.KEYCODE_DPAD_UP)
        Thread.sleep(500)
        main { check(state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) }
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        main { check(focused?.startsWith("paging:") == true) { "Left did not return to content: $focused" } }
        log("PASS focusable list scrollbar pages by viewport, stays focused at top and returns with Left")
    }

    private fun auditGridScrollbar(activity: UiCatalogActivity) {
        val first = FocusRequester()
        lateinit var state: androidx.compose.foundation.lazy.grid.LazyGridState
        main {
            focused = null
            activity.setContent {
                AmbientTvTheme {
                    state = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                    TvLazyVerticalGrid(columns = GridCells.Fixed(2), state = state,
                        modifier = Modifier.fillMaxSize().padding(40.dp).overlayLazyGridScrollbar(state)) {
                        items(100) { index ->
                            TvActionButton("Paging tile $index", {}, modifier = mark("tile:$index")
                                .then(if (index == 1) Modifier.focusRequester(first) else Modifier)
                                .fillMaxWidth().height(100.dp), fillWidth = true)
                        }
                    }
                }
            }
        }
        Thread.sleep(700)
        requestFocus(first)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        main { check(focused == null) { "Grid Right did not focus the scrollbar: $focused" } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        Thread.sleep(500)
        main {
            val viewport = state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset
            val height = state.layoutInfo.visibleItemsInfo.first().size.height
            val offset = state.firstVisibleItemIndex / 2 * height + state.firstVisibleItemScrollOffset
            check(kotlin.math.abs(offset - viewport) <= 2) { "Expected grid page ($viewport px), got $offset" }
        }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        main { check(focused?.startsWith("tile:") == true) { "Grid Left did not return to content: $focused" } }
        log("PASS grid scrollbar pages by viewport and returns to a visible tile")
    }

    private fun assertVisible(activity: UiCatalogActivity, id: String) {
        // Relocation animations must settle on the focused control, not leave the page at
        // the opposite edge after a successful focus request.
        Thread.sleep(700)
        main {
            check(focused == id) { "Focus changed after wrapping: $focused, expected $id" }
            val rect = checkNotNull(bounds[id])
            check(rect.height > 0f && rect.top >= 0f && rect.bottom <= activity.window.decorView.height) {
                "Focused $id is outside the viewport: $rect"
            }
        }
    }

    private fun auditLazyList(activity: UiCatalogActivity, settings: Boolean) {
        val first = FocusRequester()
        lateinit var state: LazyListState
        var saved = 0
        main {
            focused = null; focusHistory.clear()
            activity.setContent {
                androidx.compose.runtime.key(settings) {
                    AmbientTvTheme {
                        state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = 15)
                        val saveFocus = remember { FocusRequester() }
                        Column(Modifier.fillMaxSize().padding(32.dp)) {
                            PageHeader(actions = {
                                TvActionButton("Back", {}, mark("back"))
                                TvActionButton("Save", { saved++ }, mark("save").focusRequester(saveFocus))
                            }) { Text("Focus audit") }
                            val rows: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                                items((0..199).toList(), key = { it }) { index ->
                                    if (index !in 15..180) Text("Non-focusable section $index", Modifier.height(60.dp))
                                    else TvActionButton("Row $index", {}, modifier = mark("row:$index")
                                        .then(if (index == 15) Modifier.focusRequester(first) else Modifier)
                                        .fillMaxWidth().height(60.dp), enabled = index != 16, fillWidth = true)
                                }
                            }
                            if (settings) LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth()
                                .onSettingsActionEdge(saveFocus).wrapFocusDownToTop(state), content = rows)
                            else TvLazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth(), content = rows)
                        }
                    }
                }
            }
        }
        Thread.sleep(600)
        requestFocus(first)
        main { focusHistory.clear() }
        key(KeyEvent.KEYCODE_DPAD_UP, "row:180")
        assertVisible(activity, "row:180")
        main {
            check(state.firstVisibleItemIndex > 150) { "Wrap did not reveal the lazy tail" }
            check(focusHistory.none { it == "save" || it == "back" }) { "Up escaped to header before wrapping" }
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN, "row:15")
        assertVisible(activity, "row:15")
        main { check(state.firstVisibleItemIndex <= 15) }
        key(KeyEvent.KEYCODE_DPAD_DOWN, "row:17")
        key(KeyEvent.KEYCODE_DPAD_UP, "row:15")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, if (settings) "save" else "back")
        if (!settings) key(KeyEvent.KEYCODE_DPAD_RIGHT, "save")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        main { check(saved == 1) { "Save was focused but could not be activated" } }
        key(KeyEvent.KEYCODE_DPAD_LEFT, "back")
        requestFocus(first)
        val now = android.os.SystemClock.uptimeMillis()
        repeat(10) { repeatCount ->
            instrumentation.sendKeySync(KeyEvent(now, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_UP, repeatCount))
        }
        instrumentation.sendKeySync(KeyEvent(now, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0))
        key(KeyEvent.KEYCODE_DPAD_RIGHT, if (settings) "save" else "back")
        Thread.sleep(350)
        main { check(focused == if (settings) "save" else "back") { "Pending wrap stole focus back from an action" } }
        log("PASS ${if (settings) "settings" else "shared"} lazy list: Up/Down wrap, 200 rows, blank endpoint viewports, disabled rows, header Back/Save reachable")
    }

    private fun auditSettingsControls(activity: UiCatalogActivity) {
        val first = FocusRequester()
        lateinit var state: LazyListState
        main {
            activity.setContent {
                AmbientTvTheme {
                    state = androidx.compose.foundation.lazy.rememberLazyListState()
                    Column(Modifier.fillMaxSize().padding(32.dp)) {
                        PageHeader(actions = { TvActionButton("Back", {}, mark("settings-back")) }) { Text("Settings") }
                        LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth()
                            .focusRestorer().focusGroup().wrapFocusDownToTop(state)) {
                            item {
                                com.ambient.tv.ui.settings.SettingsSelect(
                                    options = listOf(com.ambient.tv.ui.settings.SettingsSelectOption("Selected", true, {})),
                                    modifier = mark("settings-select").focusRequester(first),
                                )
                            }
                            items(35) { index ->
                                Column {
                                    Text("Section $index")
                                    TvActionButton("Setting $index", {}, mark("setting:$index").fillMaxWidth(), fillWidth = true)
                                }
                            }
                            item {
                                Row(Modifier.focusGroup()) {
                                    TvActionButton("Final left", {}, mark("settings-last-left"))
                                    TvActionButton("Final right", {}, mark("settings-last-right"))
                                    TvActionButton("Disabled", {}, enabled = false)
                                }
                            }
                            item { Text("Non-focusable footer", Modifier.height(120.dp)) }
                        }
                    }
                }
            }
        }
        Thread.sleep(600)
        requestFocus(first)
        // Exercise the selector's return-focus effect before wrapping from its trigger.
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        // The underlying trigger retains Compose focus while the popup owns the Android
        // window. Wait for dismissal before sending a key intended for the page.
        key(KeyEvent.KEYCODE_BACK)
        awaitFocus("settings-select")
        key(KeyEvent.KEYCODE_DPAD_UP, "settings-last-right")
        main { check(state.firstVisibleItemIndex > 25) }
        key(KeyEvent.KEYCODE_DPAD_DOWN, "settings-select")
        key(KeyEvent.KEYCODE_DPAD_UP, "settings-last-right")
        log("PASS settings: real select after popup dismissal, focusRestorer, final nested row and disabled/footer exclusion")
    }

    private fun auditDetailOverlay(activity: UiCatalogActivity) {
        val first = FocusRequester()
        lateinit var state: LazyListState
        main {
            focused = null; focusHistory.clear()
            activity.setContent {
                AmbientTvTheme {
                    state = androidx.compose.foundation.lazy.rememberLazyListState()
                    Box(Modifier.fillMaxSize()) {
                        TvLazyColumn(state = state, modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 80.dp)) {
                            item { TvActionButton("Play", {}, mark("detail-play").focusRequester(first)) }
                            items(30) { index ->
                                Row(Modifier.height(100.dp).focusGroup()) {
                                    TvActionButton("Related $index", {}, mark("detail:$index"))
                                }
                            }
                            item { Text("Credits", Modifier.height(160.dp)) }
                        }
                        PageUtilityBar(onBack = {}, onSettings = {},
                            modifier = Modifier.onFocusChanged { if (it.hasFocus) focusHistory += "utility" })
                    }
                }
            }
        }
        Thread.sleep(600)
        requestFocus(first)
        main { focusHistory.clear() }
        key(KeyEvent.KEYCODE_DPAD_UP, "detail:29")
        main {
            check(state.firstVisibleItemIndex > 20)
            check("utility" !in focusHistory) { "Wrap focused fixed Back while showing the detail tail" }
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN, "detail-play")
        main { check(state.firstVisibleItemIndex == 0) }
        log("PASS detail: fixed Back/Settings overlay never receives wrap focus while the tail is visible")
    }

    // Mirrors SettingsHub's explicit right-edge Save path, outside the settings list helper.
    private fun Modifier.onSettingsActionEdge(save: FocusRequester): Modifier =
        this.onKeyEvent { event ->
            if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && event.key == androidx.compose.ui.input.key.Key.DirectionRight) {
                save.requestFocus()
            } else false
        }

    /** A single lazy item can contain many controls, with a selector before the true last one. */
    private fun auditNestedSettings(activity: UiCatalogActivity) {
        val first = FocusRequester()
        main {
            activity.setContent {
                AmbientTvTheme {
                    Column(Modifier.fillMaxSize().padding(32.dp)) {
                        PageHeader(actions = { TvActionButton("Back", {}, mark("nested-back")) }) { Text("Settings") }
                        val state = androidx.compose.foundation.lazy.rememberLazyListState()
                        Column(Modifier.weight(1f).focusRestorer().focusGroup()) {
                            LazyColumn(state = state, modifier = Modifier.fillMaxSize().wrapFocusDownToTop(state)) {
                                item {
                                    Column {
                                        PlayerDropUpSelector(label = "Select", value = "One",
                                            options = listOf("one" to "One", "two" to "Two"), selectedKey = "one",
                                            onSelected = {}, modifier = Modifier.focusRequester(first)
                                                .onFocusChanged { if (it.hasFocus) { focused = "nested-select"; focusHistory += "nested-select" } })
                                        repeat(25) { index ->
                                            TvActionButton("Setting $index", {}, mark("nested:$index")
                                                .fillMaxWidth().height(60.dp), fillWidth = true)
                                        }
                                        Row(Modifier.fillMaxWidth().focusRestorer().focusGroup()) {
                                            TvActionButton("Last left", {}, mark("nested-last-left"))
                                            TvActionButton("Last right", {}, mark("nested-last-right"))
                                        }
                                        Text("Informational footer", Modifier.height(80.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Thread.sleep(600)
        requestFocus(first)
        main { focusHistory.clear() }
        key(KeyEvent.KEYCODE_DPAD_UP, "nested-last-right")
        assertVisible(activity, "nested-last-right")
        main { check("nested-back" !in focusHistory && "nested-select" !in focusHistory) }
        key(KeyEvent.KEYCODE_DPAD_DOWN, "nested-select")
        key(KeyEvent.KEYCODE_DPAD_UP, "nested-last-right")
        log("PASS nested settings: selector, restored groups, oversized lazy item and non-focusable footer")
    }

    private fun auditGrid(activity: UiCatalogActivity, reverse: Boolean = false) {
        val first = FocusRequester()
        main {
            activity.setContent {
                AmbientTvTheme {
                    TvLazyVerticalGrid(columns = GridCells.Fixed(3), reverseLayout = reverse, modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((0..89).toList(), key = { it }) { index ->
                            TvActionButton("Tile $index", {}, mark("tile:$index")
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                .height(80.dp), enabled = index < 87, fillWidth = true)
                        }
                    }
                }
            }
        }
        Thread.sleep(600)
        requestFocus(first)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, "tile:1")
        key(KeyEvent.KEYCODE_DPAD_LEFT, "tile:0")
        key(if (reverse) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP, "tile:86")
        key(if (reverse) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, "tile:0")
        log("PASS lazy grid (reverse=$reverse): vertical wrap, disabled final row, horizontal tile traversal preserved")
    }

    private fun auditGridHeader(activity: UiCatalogActivity, withCategories: Boolean, list: Boolean = false) {
        val first = FocusRequester()
        main {
            focused = null; focusHistory.clear()
            activity.setContent {
                AmbientTvTheme {
                    Column(Modifier.fillMaxSize().padding(32.dp)) {
                        PageHeader(actions = { TvActionButton("View", {}, mark("grid-view")) }) { Text("Library") }
                        if (withCategories) Row(Modifier.fillMaxWidth().focusGroup()) {
                            TvChip("All", modifier = mark("grid-all"), onClick = {})
                            TvChip("Movies", modifier = mark("grid-movies"), onClick = {})
                        }
                        if (list) TvLazyColumn(allowUpExit = true, modifier = Modifier.weight(1f)) {
                            items((0..89).toList(), key = { it }) { index ->
                                TvActionButton("Tile $index", {}, mark("header-tile:$index")
                                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                    .fillMaxWidth().height(80.dp), fillWidth = true)
                            }
                        } else TvLazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f)) {
                            items((0..89).toList(), key = { it }) { index ->
                                TvActionButton("Tile $index", {}, mark("header-tile:$index")
                                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                    .height(80.dp), fillWidth = true)
                            }
                        }
                    }
                }
            }
        }
        Thread.sleep(600)
        requestFocus(first)
        key(KeyEvent.KEYCODE_DPAD_DOWN, if (list) "header-tile:1" else "header-tile:3")
        key(KeyEvent.KEYCODE_DPAD_UP, "header-tile:0")
        key(KeyEvent.KEYCODE_DPAD_UP, if (withCategories) null else "grid-view")
        if (withCategories) {
            var category = ""
            main {
                check(focused == "grid-all" || focused == "grid-movies") { "Up skipped categories: $focused" }
                category = focused!!
            }
            if (category == "grid-movies") key(KeyEvent.KEYCODE_DPAD_LEFT, "grid-all")
            key(KeyEvent.KEYCODE_DPAD_RIGHT, "grid-movies")
            key(KeyEvent.KEYCODE_DPAD_UP, "grid-view")
            requestFocus(first)
            key(KeyEvent.KEYCODE_DPAD_UP)
            main { check(focused == "grid-all" || focused == "grid-movies") }
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        var returnedTile = ""
        main {
            check(focused in setOf("header-tile:0", "header-tile:1", "header-tile:2")) {
                "Header did not return to the grid's first row: $focused"
            }
            returnedTile = focused!!
        }
        assertVisible(activity, returnedTile)
        log("PASS page header (list=$list, categories=$withCategories): Up reaches categories/View; Down returns to grid")
    }

    private fun auditComposedColumn(activity: UiCatalogActivity) {
        val first = FocusRequester()
        main {
            activity.setContent {
                AmbientTvTheme {
                    val scroll = rememberScrollState()
                    Column(Modifier.fillMaxSize().padding(32.dp).wrapVerticalScrollFocus(scroll).verticalScroll(scroll)) {
                        repeat(160) { index ->
                            TvActionButton("Control $index", {}, mark("control:$index")
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                .fillMaxWidth().height(60.dp), fillWidth = true)
                        }
                        Row(Modifier.fillMaxWidth().focusGroup()) {
                            TvActionButton("Last left", {}, mark("last-left"))
                            TvActionButton("Last right", {}, mark("last-right"))
                        }
                    }
                }
            }
        }
        Thread.sleep(600)
        requestFocus(first)
        key(KeyEvent.KEYCODE_DPAD_UP, "last-right")
        key(KeyEvent.KEYCODE_DPAD_DOWN, "control:0")
        log("PASS composed scroll column: more than 128 controls and final row's rightmost control reached")
    }

    private fun auditDialog(activity: UiCatalogActivity) {
        val show = mutableStateOf(true)
        val first = FocusRequester()
        main {
            activity.setContent {
                AmbientTvTheme {
                    PageHeader(actions = { TvActionButton("Page behind dialog", {}, mark("behind")) }) { Text("Page") }
                    if (show.value) AppDialog(onDismiss = { show.value = false }, title = "List modal", scrollable = false, fillHeight = true,
                        footer = {
                            TvActionButton("Cancel", { show.value = false }, mark("cancel"))
                            TvActionButton("Apply", {}, mark("apply"))
                        }) {
                        val modalList = androidx.compose.foundation.lazy.rememberLazyListState()
                        TvLazyColumn(Modifier.fillMaxWidth().height(240.dp).overlayLazyScrollbar(modalList), state = modalList) {
                            items((0..39).toList()) { index ->
                                TvActionButton("Choice $index", {}, mark("choice:$index")
                                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(), fillWidth = true)
                            }
                        }
                    }
                }
            }
        }
        Thread.sleep(700)
        requestFocus(first)
        main { focusHistory.clear() }
        key(KeyEvent.KEYCODE_DPAD_UP, "choice:39")
        key(KeyEvent.KEYCODE_DPAD_DOWN, "choice:0")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        main { check(focused == null) { "Modal did not enter scrollbar: $focused" } }
        key(KeyEvent.KEYCODE_DPAD_RIGHT, "cancel")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, "apply")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, "cancel")
        key(KeyEvent.KEYCODE_DPAD_LEFT, "apply")
        key(KeyEvent.KEYCODE_DPAD_LEFT, "cancel")
        main { check("behind" !in focusHistory) { "Modal shortcut focused the underlying page" } }
        key(KeyEvent.KEYCODE_BACK)
        main { check(!show.value) }
        log("PASS modal list wraps independently; Cancel/Apply and hardware Back remain reachable; no background-page focus")
    }

    private fun auditDialogButtonRows(activity: UiCatalogActivity) {
        val first = FocusRequester()
        val middleEnabled = mutableStateOf(false)
        var middleApplied = false
        main {
            activity.setContent {
                AmbientTvTheme {
                    val enabledForFrame = middleEnabled.value
                    androidx.compose.runtime.SideEffect { middleApplied = enabledForFrame }
                    AppDialog(onDismiss = {}, title = "Horizontal button navigation", footer = {
                        TvActionButton("Close", {}, mark("row-close"))
                    }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvActionButton("Left", {}, mark("row-left").focusRequester(first))
                            TvActionButton("Middle", {}, mark("row-middle"), enabled = enabledForFrame)
                            TvActionButton("Right", {}, mark("row-right"))
                        }
                        Row {
                            TvActionButton("Other row", {}, mark("other-row"))
                        }
                    }
                }
            }
        }
        Thread.sleep(700)
        requestFocus(first)
        key(KeyEvent.KEYCODE_DPAD_LEFT, "row-right")
        repeat(3) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT, "row-left")
            key(KeyEvent.KEYCODE_DPAD_RIGHT, "row-right")
        }
        key(KeyEvent.KEYCODE_DPAD_LEFT, "row-left")
        main { middleEnabled.value = true }
        // Android idle does not guarantee Compose has applied the next frame.
        repeat(60) {
            var applied = false
            main { applied = middleApplied }
            if (!applied) Thread.sleep(25)
        }
        main { check(middleApplied) }
        instrumentation.waitForIdleSync()
        key(KeyEvent.KEYCODE_DPAD_RIGHT, "row-middle")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, "row-right")
        key(KeyEvent.KEYCODE_DPAD_DOWN, "other-row")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, "row-close")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, "row-close")
        key(KeyEvent.KEYCODE_DPAD_LEFT, "row-close")
        log("PASS dialog content/footer cycle horizontally; disabled actions skipped; vertical navigation retained")
    }

    private fun auditHandheld(activity: UiCatalogActivity) {
        val first = FocusRequester()
        main {
            activity.setContent {
                CompositionLocalProvider(LocalHandheldUi provides true) {
                    AmbientTvTheme {
                        Column(Modifier.fillMaxSize().padding(32.dp)) {
                            TvActionButton("Handheld header", {}, mark("handheld-header"), fillWidth = true)
                            TvLazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                                items((0..19).toList()) { index ->
                                    TvActionButton("Handheld $index", {}, mark("handheld:$index")
                                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(), fillWidth = true)
                                }
                            }
                        }
                    }
                }
            }
        }
        Thread.sleep(600)
        requestFocus(first)
        key(KeyEvent.KEYCODE_DPAD_UP, "handheld-header")
        log("PASS handheld retains natural header focus instead of TV wrapping")
    }
}
