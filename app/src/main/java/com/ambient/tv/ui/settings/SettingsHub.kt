package com.ambient.tv.ui.settings

import com.ambient.tv.ui.common.withTvFocusInsets

import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.focus.Focusability
import androidx.compose.ui.focus.getFocusedRect
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.common.OperationFeedbackScope
import com.ambient.tv.ui.common.OperationFeedbackContent
import com.ambient.tv.notifications.OperationFeedback

import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.isTabletUi
import com.ambient.tv.ui.mobile.settingsUsesSideIndex

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import com.ambient.tv.ui.sound.LocalUiSoundPlayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.*
import com.ambient.tv.R
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.data.local.homeSystemReady
import com.ambient.tv.ui.common.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Owns navigation only. Drafts and all source actions remain in SettingsViewModel. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SettingsHub(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    initialCategoryKey: String?,
    onExitGuardChanged: (SettingsExitGuard?) -> Unit,
    onBack: () -> Unit,
    onFocusMenu: () -> Unit = onBack,
    onDone: () -> Unit,
    onSave: (() -> Unit) -> Boolean,
    onRequestAppExit: (() -> Unit)? = null,
    content: @Composable (SettingsCategory, LazyListState, Modifier, () -> Unit, ((() -> Unit) -> Unit)) -> Unit,
) {
    val handheld = LocalHandheldUi.current
    val sideIndex = settingsUsesSideIndex(isTabletUi(), LocalConfiguration.current.screenWidthDp)
    val initialPage = remember(initialCategoryKey) { SettingsCategory.fromKey(initialCategoryKey) }
    var area by rememberSaveable(initialCategoryKey) { mutableStateOf(initialPage?.area ?: SettingsArea.START) }
    var page by rememberSaveable(initialCategoryKey) { mutableStateOf(initialPage) }
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var returningFromPage by remember { mutableStateOf(false) }
    var returningFromSearch by remember { mutableStateOf(false) }
    var returnToSearch by rememberSaveable { mutableStateOf(false) }
    var lastPage by rememberSaveable { mutableStateOf(initialPage) }
    var areaMenu by rememberSaveable { mutableStateOf(false) }
    // Deep-link / sidebar shortcut opened a page without showing the area index.
    var skipIndexOnPageBack by rememberSaveable(initialCategoryKey) { mutableStateOf(initialPage != null) }
    var exitOnPageBack by rememberSaveable(initialCategoryKey) { mutableStateOf(initialPage != null) }
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    OperationFeedbackScope(viewModel.feedback, "settings")
    OperationFeedbackScope(viewModel.feedback, viewModel.feedbackScope(page), pendingExit == null)
    val operationFeedback = state.operationFeedback?.takeIf {
        it.scope == "settings" || (pendingExit == null && it.scope == viewModel.feedbackScope(page))
    }
    val dirty by viewModel.pendingChanges.collectAsState()
    val companionVm: com.ambient.tv.companion.CompanionViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    SideEffect { companionVm.manager.settingsDirty = dirty }
    DisposableEffect(companionVm) { onDispose { companionVm.manager.settingsDirty = false } }
    val areaFocus = remember { SettingsArea.entries.associateWith { FocusRequester() } }
    val pageFocus = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    val searchFocus = remember { FocusRequester() }
    val searchButtonFocus = remember { FocusRequester() }
    val focusedRect = remember { FocusedRectProbe() }
    val contentFocus = remember { FocusRequester() }
    // Invisible holder that keeps focus inside Settings while a page closes. Without it, Left
    // removes the focused control with the page, focus falls back to the armed sidebar and the
    // rail opens instead of returning to the row the page was opened from.
    val parkFocus = remember { FocusRequester() }
    var parking by remember { mutableStateOf(false) }
    // Region that last held focus inside Settings. Coming back from the sidebar (Right) restores it;
    // the first entry lands on the selected category instead of the Back button.
    var lastRegionFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val dialogFocus = remember { FocusRequester() }
    val states = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val uiSounds = LocalUiSoundPlayer.current
    val indexState = rememberLazyListState()
    val areaTabsState = rememberLazyListState()
    val swipeThreshold = with(LocalDensity.current) { 56.dp.toPx() }
    val layoutDirection = LocalLayoutDirection.current
    val showAreaTabs = handheld && page == null && !sideIndex
    LaunchedEffect(area, showAreaTabs) {
        if (showAreaTabs) areaTabsState.animateScrollToItem(area.ordinal)
    }
    val fade = remember { Animatable(1f) }
    val navigationKey = page?.name ?: if (search) "search" else "area-${area.name}"
    var panelHasFocus by remember { mutableStateOf(false) }
    var panelIsFocused by remember { mutableStateOf(false) }
    var settlingPageFocus by remember { mutableStateOf(initialPage != null && !handheld) }

    fun requestExit(action: () -> Unit) {
        // Settings save on change; only fields still pending (addresses, sign-in data) are committed
        // here. If one is invalid, the leave dialog offers to discard it or keep editing.
        if (!viewModel.hasPendingChanges()) { action(); return }
        if (onSave(action)) return
        pendingExit = action
    }
    fun navState() = SettingsHubNav(
        page = page,
        search = search,
        returnToSearch = returnToSearch,
        areaMenu = handheld || areaMenu,
        skipIndexOnPageBack = skipIndexOnPageBack,
        exitOnPageBack = exitOnPageBack,
    )
    fun applyNav(next: SettingsHubNav) {
        if (page != null && next.page == null) {
            lastPage = page
            settlingPageFocus = false
            returningFromPage = true
        }
        // Search was opened from its header chip; leaving it lands there again.
        if (search && !next.search && next.page == null) returningFromSearch = true
        page = next.page
        search = next.search
        returnToSearch = next.returnToSearch
        areaMenu = next.areaMenu
        skipIndexOnPageBack = next.skipIndexOnPageBack
        exitOnPageBack = next.exitOnPageBack
        if (!next.search) query = ""
    }
    val linkedPages = remember { mutableStateListOf<Pair<SettingsHubNav, String>>() }
    val linkFocus = remember { mutableMapOf<String, FocusRequester>() }
    var returningLink by remember { mutableStateOf<String?>(null) }
    fun back() {
        if (linkedPages.isNotEmpty()) {
            val (origin, link) = linkedPages.removeAt(linkedPages.lastIndex)
            applyNav(origin)
            area = origin.page!!.area
            returningLink = link
            settlingPageFocus = true
            return
        }
        val next = navState().pop()
        if (next == null) requestExit(onBack) else applyNav(next)
    }
    // The index (no page, no search) is the Settings root: Back chip, Left and Back open the global menu.
    fun backOrMenu() {
        if (linkedPages.isEmpty() && page == null && !search) requestExit(onFocusMenu) else back()
    }
    fun dismissExitDialog() {
        pendingExit = null
        // Keep editing returns to the control that triggered the exit, not to the Back chip.
        // The page panel's focusRestorer brings back its own last row.
        val target = when {
            lastRegionFocus != null -> lastRegionFocus
            areaMenu -> areaFocus.getValue(area)
            else -> searchButtonFocus
        }
        scope.launch { withFrameNanos { }; runCatching { target?.requestFocus() } }
    }
    var searchLandingQuery by rememberSaveable { mutableStateOf<String?>(null) }
    fun openPage(target: SettingsCategory) {
        val resolved = if (target == SettingsCategory.ADVANCED) SettingsCategory.SPOTIFY else target
        searchLandingQuery = query.takeIf { search && it.isNotBlank() }
        returnToSearch = search
        area = resolved.area
        settlingPageFocus = true
        page = resolved
        lastPage = resolved
        areaMenu = false
        // Opened from the index (or search): Back returns to that index/search, not exit.
        skipIndexOnPageBack = false
        exitOnPageBack = false
    }
    fun selectArea(target: SettingsArea) {
        linkedPages.clear()
        returningLink = null
        area = target
        search = false
        returnToSearch = false
        areaMenu = false
        exitOnPageBack = false
        // Always land on the area index, even with a single configured system: jumping straight
        // into that system's page (formerly SETTINGS_035) hid the list of other sources.
        page = null
        settlingPageFocus = false
        skipIndexOnPageBack = false
    }
    suspend fun requestPanelContentFocus(attempts: Int = 24): Boolean {
        repeat(attempts) {
            withFrameNanos { }
            runCatching { contentFocus.requestFocus() }
            withFrameNanos { }
            if (panelHasFocus && !panelIsFocused) return true
            if (panelIsFocused) {
                runCatching { focusManager.moveFocus(FocusDirection.Enter) }
                withFrameNanos { }
                if (panelHasFocus && !panelIsFocused) return true
            }
        }
        return panelHasFocus && !panelIsFocused
    }

    val exitGuardCallback by rememberUpdatedState(onExitGuardChanged)
    DisposableEffect(viewModel) {
        exitGuardCallback { action -> requestExit(action) }
        onDispose { exitGuardCallback(null) }
    }
    LaunchedEffect(page) { viewModel.onPageOpened(page) }
    DisposableEffect(viewModel) { onDispose { viewModel.onPageOpened(null) } }
    LaunchedEffect(navigationKey) {
        fade.snapTo(0.72f)
        fade.animateTo(1f, tween(150))
    }
    val hdrControlFacts = rememberHdrControlFacts(state.uiHdrModeKey)
    val showSdrToHdr = com.ambient.tv.ui.display.sdrToHdrSwitchVisible(hdrControlFacts)
    val entries = SettingsCategory.entries.map { category ->
        val title = settingsCategoryTitle(category)
        val hint = stringResource(if (category == SettingsCategory.COMPANION && !handheld) R.string.companion_tv_only_hint else category.hintRes).let { settingsSystem(category)?.renamed(it) ?: it }
        Triple(category, title, hint)
    }.filter { (category, title, hint) ->
        // Empty ADVANCED pointer page is hidden; deep-links go to Spotify (fromKey + openPage).
        if (category == SettingsCategory.ADVANCED) return@filter false
        // The UPnP receiver only runs on TVs; phones are senders.
        if (category == SettingsCategory.RECEIVER && handheld) return@filter false
        // A renamed system is still found under its provider name.
        val haystack = buildString {
            append(title); append(' '); append(hint); append(' ')
            append(stringResource(category.titleRes)); append(' '); append(category.name)
            if (category == SettingsCategory.APPEARANCE) {
                append(' '); append(stringResource(R.string.settings_ui_hdr)); append(" hdr sdr")
                if (showSdrToHdr) {
                    append(' '); append(stringResource(R.string.settings_sdr_to_hdr))
                    append(" sdr to hdr sdr video zu hdr sdr->hdr sdr→hdr conversion konvertierung mpv vulkan")
                }
            }
            if (category == SettingsCategory.NAV_SOUNDS) {
                append(" menütöne menutoene navigationssounds navigation sounds sound-editor sound editor hall reverb echo")
                append(' '); append(stringResource(R.string.nav_editor_title))
            }
            if (category == SettingsCategory.COMPANION) {
                append(" pairing koppeln geräte devices remote fernsteuerung synchronisieren sync accounts konten übertragen transfer netzwerk network wake wol vpn wireguard hintergrund background")
                append(' '); append(stringResource(R.string.companion_connection_settings))
            }
            if (category == SettingsCategory.WAKE) append(" mac wol wake aufwecken server nas smb nfs plex jellyfin emby tvheadend dlna retry wiederholung wiederholen")
            if (category == SettingsCategory.RECEIVER) append(" upnp dlna receiver empfänger handy cast zuspielen")
            if (category == SettingsCategory.SPOTIFY) append(" advanced developer client id entwickler")
        }
        if (search) settingsSearchMatches(query, haystack)
        else category.area == area && (area != SettingsArea.SYSTEMS || systemListed(category, state))
    }.sortedBy { (category, _, _) ->
        if (!search && area == SettingsArea.NETWORK) listOf(SettingsCategory.COMPANION, SettingsCategory.RECEIVER, SettingsCategory.WIREGUARD, SettingsCategory.WAKE).indexOf(category)
        else if (category.area == SettingsArea.SYSTEMS && sourceConfigured(category, state)) 0 else 1
    }
    val visibleEntries = entries
    // Systems that are not set up yet are offered as setup cards (like the initial setup) below.
    val addableSystems = if (!search && area == SettingsArea.SYSTEMS) {
        SettingsCategory.entries.filter { it.area == SettingsArea.SYSTEMS && !systemListed(it, state) && settingsSystem(it) != null }
    } else emptyList()

    // LazyColumn index of a category's row, or of the "add service" card row it sits in
    // (list rows, optional empty hint, section title, then cards in rows of cardColumns).
    val cardColumns = if (handheld) 1 else 2
    fun indexOfPage(category: SettingsCategory?): Int {
        val row = visibleEntries.indexOfFirst { it.first == category }
        if (row >= 0) return row
        val card = addableSystems.indexOf(category)
        if (card < 0) return -1
        return visibleEntries.size + (if (visibleEntries.isEmpty()) 1 else 0) + 1 + card / cardColumns
    }
    // Back/Left from a page lands on the row or card it was opened from.
    suspend fun focusLastPage(): Boolean {
        val target = lastPage ?: return false
        val index = indexOfPage(target)
        if (index >= 0) indexState.scrollToItem(index)
        repeat(4) {
            withFrameNanos { }
            if (runCatching { pageFocus.getValue(target).requestFocus() }.getOrDefault(false)) return true
        }
        return false
    }

    BackHandler {
        if (!parking) {
            if (page == null) back()
            else {
                // Keep focus inside Settings while the focused page is removed. Otherwise
                // automatic focus repair selects the area rail and cancels row restoration.
                parking = true
                scope.launch {
                    try {
                        withFrameNanos { }
                        runCatching { parkFocus.requestFocus() }
                        back()
                        if (page == null && !focusLastPage()) requestPanelContentFocus()
                    } finally { parking = false }
                }
            }
        }
    }

    LaunchedEffect(navigationKey, areaMenu) {
        if (handheld) return@LaunchedEffect
        // A selected page settles into its first focusable row from the page panel itself.
        // Requesting Back here is what made every settings view open on the chrome button.
        if (page != null) return@LaunchedEffect
        withFrameNanos { }
        when {
            areaMenu -> {
                runCatching { areaFocus.getValue(area).requestFocus() }
            }
            search -> {
                if (returningFromPage) {
                    returningFromPage = false
                    if (focusLastPage()) return@LaunchedEffect
                }
                runCatching { searchFocus.requestFocus() }
            }
            returningFromSearch -> {
                returningFromSearch = false
                if (!runCatching { searchButtonFocus.requestFocus() }.getOrDefault(false)) {
                    runCatching { areaFocus.getValue(area).requestFocus() }
                }
            }
            lastPage?.area == area -> {
                if (focusLastPage()) return@LaunchedEffect
                runCatching { areaFocus.getValue(area).requestFocus() }
            }
            else -> {
                indexState.scrollToItem(0)
                val first = visibleEntries.firstOrNull()?.first ?: addableSystems.firstOrNull()
                if (first == null || !runCatching { pageFocus.getValue(first).requestFocus() }.getOrDefault(false)) {
                    runCatching { areaFocus.getValue(area).requestFocus() }
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize()
            // Only D-pad entry is redirected: a programmatic requestFocus() (e.g. Back focusing the row a
            // page was opened from) must land on its own target, not on the panel's first row.
            .focusProperties {
                onEnter = {
                    if (!handheld && requestedFocusDirection != FocusDirection.Enter) (lastRegionFocus ?: areaFocus.getValue(area)).requestFocus()
                }
            }
            .focusGroup()
            .padding(screenGutter()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (handheld) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Settings, null, Modifier.padding(12.dp))
                Text(page?.let { settingsCategoryTitle(it) } ?: stringResource(R.string.settings_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                androidx.compose.material3.IconButton(onClick = { linkedPages.clear(); returningLink = null; page = null; search = true; returningFromPage = false }) { Icon(Icons.Default.Search, stringResource(R.string.settings_search)) }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .focusProperties {
                        onEnter = {
                            if (settlingPageFocus) cancelFocusChange()
                        }
                    }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Settings, null, Modifier.size(32.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
                    Text(listOfNotNull(stringResource(area.titleRes), page?.let { settingsCategoryTitle(it) }).joinToString(" › "), style = MaterialTheme.typography.bodySmall)
                }
                TvChip(stringResource(R.string.action_search), modifier = Modifier.focusRequester(searchButtonFocus).onFocusChanged { if (it.isFocused) lastRegionFocus = searchButtonFocus }
                    .onPreviewKeyEvent { event ->
                        // Left from the leftmost header chip goes back, as the Back chip used to.
                        if (event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown) { backOrMenu(); true } else false
                    },
                    icon = Icons.Filled.Search, iconOnly = true) { linkedPages.clear(); returningLink = null; page = null; search = true; returningFromPage = false }
                if (area == SettingsArea.SYSTEMS && page != SettingsCategory.NOTIFICATIONS) {
                    TvChip(stringResource(R.string.notice_history), icon = Icons.Filled.Notifications, iconOnly = true) {
                        openPage(SettingsCategory.NOTIFICATIONS)
                    }
                }
            }
        }
        state.statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (pendingExit == null && state.statusMessage == null) operationFeedback?.let {
            OperationFeedbackContent(it, Modifier.fillMaxWidth())
        }
        if (area == SettingsArea.SYSTEMS || area == SettingsArea.NETWORK) {
            com.ambient.tv.ui.common.LocalNetworkPermissionSetting(onGranted = viewModel::refreshPageAfterPermissionGrant)
        }
        if (showAreaTabs) {
            androidx.compose.foundation.lazy.LazyRow(state = areaTabsState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SettingsArea.entries) { target -> TvChip(stringResource(target.titleRes), target == area, onClick = { selectArea(target) }) }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (sideIndex) {
                // Touch index only. This is not the television focus rail and does not handle D-pad.
                val areaScroll = rememberScrollState()
                Column(
                    Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                        .verticalScroll(areaScroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsArea.entries.forEach { target ->
                        TvChip(
                            text = stringResource(target.titleRes),
                            selected = target == area,
                            modifier = Modifier.fillMaxWidth(),
                            icon = areaIcon(target),
                            onClick = { selectArea(target) },
                        )
                    }
                }
            }
            if (!handheld) Box(Modifier.fillMaxHeight().width(240.dp)) {
                val areaScroll = rememberScrollState()
                Column(Modifier.fillMaxSize()
                    // Left from the category list always lands on the selected area, not a row at the same height.
                    .focusProperties { onEnter = { if (requestedFocusDirection != FocusDirection.Enter) areaFocus.getValue(area).requestFocus() } }
                    .focusGroup()
                    .wrapVerticalScrollFocus(areaScroll)
                    .verticalScroll(areaScroll).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f), RoundedCornerShape(14.dp)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsArea.entries.forEach { target ->
                        SettingsHubRow(stringResource(target.titleRes), null, target == area, areaIcon(target), singleLine = false,
                            modifier = Modifier.focusRequester(areaFocus.getValue(target))
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        areaMenu = true
                                        lastRegionFocus = null
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                // Vertical wrapping belongs to the list shell. Right still enters
                                // the selected area; Left still opens the main navigation.
                                if (event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown) {
                                    if (event.nativeKeyEvent.repeatCount == 0) uiSounds?.playSelect()
                                    if (area != target) selectArea(target)
                                    scope.launch {
                                        if (lastPage?.area == target && focusLastPage()) return@launch
                                        requestPanelContentFocus()
                                    }
                                    true
                                } else if (event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown &&
                                    target == SettingsArea.entries.first()) {
                                    // Up from the top row reaches the header buttons instead of wrapping.
                                    val moved = runCatching { searchButtonFocus.requestFocus() }.getOrDefault(false)
                                    if (moved) uiSounds?.playFocus()
                                    moved
                                } else if (event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown) {
                                    requestExit(onFocusMenu)
                                    true
                                } else false
                            }, onClick = {
                                selectArea(target)
                                scope.launch {
                                    if (lastPage?.area == target && focusLastPage()) return@launch
                                    requestPanelContentFocus()
                                }
                            })
                    }
                }
                // Sits in the column's own 8dp padding, so the rows keep their width.
                if (areaScroll.maxValue > 0) {
                    OverlayScrollbar(areaScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(top = 14.dp, bottom = 14.dp, end = 1.dp), contentFocus = areaFocus.getValue(area))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()
                .then(if (showAreaTabs && !search) Modifier.pointerInput(area, swipeThreshold, layoutDirection) {
                    var dragDistance = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragDistance = 0f },
                        onDragCancel = { dragDistance = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragDistance += amount
                        },
                        onDragEnd = {
                            val distance = if (layoutDirection == LayoutDirection.Rtl) -dragDistance else dragDistance
                            val step = when {
                                distance <= -swipeThreshold -> 1
                                distance >= swipeThreshold -> -1
                                else -> 0
                            }
                            if (step != 0) {
                                SettingsArea.entries.getOrNull(area.ordinal + step)?.let(::selectArea)
                            }
                            dragDistance = 0f
                        },
                    )
                } else Modifier)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.32f), RoundedCornerShape(14.dp)).padding(deviceDp(16.dp, 8.dp))) {
                Box(Modifier.focusRequester(parkFocus).focusProperties { canFocus = parking }.focusTarget())
                if (page == null && search) {
                    TvTextField(query, { query = it }, stringResource(R.string.settings_search_hint),
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus))
                    Spacer(Modifier.height(12.dp))
                }
                val panelModifier = Modifier.fillMaxSize()
                    .graphicsLayer { alpha = fade.value }
                    .focusRequester(contentFocus)
                    .onFocusChanged { state ->
                        panelHasFocus = state.hasFocus
                        // The panel's focusRestorer brings back its own last row.
                        if (state.hasFocus) {
                            lastRegionFocus = contentFocus
                            areaMenu = false
                        }
                        panelIsFocused = state.isFocused
                        if (state.hasFocus && !state.isFocused) settlingPageFocus = false
                    }
                    // No explicit fallback: focusRestorer() only has a remembered child to
                    // restore after the panel has been entered once. Passing backFocus as the
                    // fallback meant every *first* entry into a fresh panel (no history yet)
                    // went straight to the Back button instead of the panel's first row - the
                    // "DPAD right/enter only reaches Back" bug. The default fallback lets normal
                    // focus search land on the first focusable row instead.
                    .focusRestorer()
                    .focusGroup()
                    .focusProperties {
                        onExit = {
                            when {
                                requestedFocusDirection != FocusDirection.Left -> Unit
                                page != null -> cancelFocusChange()
                                else -> areaFocus.getValue(area).requestFocus()
                            }
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        // Right past the last control of a row reaches the header (Search; settings save on change).
                        if (event.key == Key.DirectionRight) {
                            val moved = if (focusManager.moveFocus(FocusDirection.Right)) {
                                true
                            } else {
                                runCatching { searchButtonFocus.requestFocus() }.getOrDefault(false)
                            }
                            if (moved) uiSounds?.playFocus()
                            return@onKeyEvent moved
                        }
                        if (event.key != Key.DirectionLeft) return@onKeyEvent false
                        if (page == null) return@onKeyEvent false
                        val leavePage: suspend () -> Unit = {
                            parking = true
                            withFrameNanos { }
                            runCatching { parkFocus.requestFocus() }
                            back()
                            if (page == null && !focusLastPage()) requestPanelContentFocus()
                            parking = false
                        }
                        val before = focusedRect.rect()
                        if (focusManager.moveFocus(FocusDirection.Left)) {
                            // Geometric search also counts a narrower control in a lower row as "left"
                            // of a full-width row. Only a target sharing the row is a real move.
                            scope.launch {
                                withFrameNanos { }
                                val after = focusedRect.rect()
                                if (before != null && after != null && (after.top >= before.bottom || after.bottom <= before.top)) leavePage()
                            }
                            return@onKeyEvent true
                        }
                        scope.launch { leavePage() }
                        true
                    }
                    .then(focusedRect.modifier)
                    .focusGroup()
                val current = page
                if (current == null) {
                    val indexOverflow = indexState.canScrollForward || indexState.canScrollBackward
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(state = indexState, contentPadding = PaddingValues(0.dp).withTvFocusInsets(), modifier = panelModifier.wrapFocusDownToTop(indexState).padding(end = if (indexOverflow) 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(visibleEntries, key = { it.first.name }) { (category, title, hint) ->
                                val summary = settingsPageSummary(category, state)
                                val subtitle = listOfNotNull(summary, hint).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n").ifBlank { null }
                                SettingsHubRow(title, subtitle, category == lastPage && !panelHasFocus, Icons.Filled.ChevronRight,
                                    modifier = Modifier.focusRequester(pageFocus.getValue(category)), onClick = { openPage(category) })
                            }
                            if (!search && area == SettingsArea.APP && onRequestAppExit != null) {
                                item(key = "app-exit") {
                                    SettingsHubRow(
                                        title = stringResource(R.string.settings_exit_app_action),
                                        subtitle = stringResource(R.string.settings_exit_app_hint),
                                        selected = false,
                                        icon = Icons.Filled.PowerSettingsNew,
                                        onClick = onRequestAppExit,
                                    )
                                }
                            }
                            if (visibleEntries.isEmpty()) item { Text(stringResource(R.string.settings_search_empty)) }
                            if (addableSystems.isNotEmpty()) {
                                item(key = "add-systems-title") {
                                    Text(
                                        stringResource(R.string.settings_add_system),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                                    )
                                }
                                items(addableSystems.chunked(cardColumns), key = { row -> "add-" + row.joinToString { it.name } }) { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        row.forEach { category ->
                                            com.ambient.tv.ui.setup.SetupSystemCard(
                                                settingsSystem(category)!!,
                                                Modifier.weight(1f).focusRequester(pageFocus.getValue(category)),
                                                onFocused = {},
                                                selected = category == lastPage && !panelHasFocus,
                                            ) { openPage(category) }
                                        }
                                        if (row.size == 1 && !handheld) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        if (indexOverflow) {
                            OverlayLazyScrollbar(indexState, Modifier.align(Alignment.CenterEnd).fillMaxHeight().graphicsLayer { alpha = fade.value }, contentFocus = contentFocus, actionsFocus = searchButtonFocus)
                        }
                    }
                } else {
                    key(current) {
                        states.SaveableStateProvider(current.name) {
                            val listState = rememberLazyListState()
                            val landingQuery = searchLandingQuery
                            CompositionLocalProvider(
                                LocalSettingsSearchLanding provides landingQuery,
                                LocalSettingsLinkFocus provides linkFocus,
                                // Right from the page scrollbar reaches the header, like the index list.
                                com.ambient.tv.ui.common.LocalScrollListActionFocus provides searchButtonFocus,
                                LocalOpenSettingsPage provides { target, link ->
                                    linkedPages.add(navState() to link)
                                    openPage(target)
                                    if (target == SettingsCategory.WAKE) searchLandingQuery = link.substringBefore('-')
                                },
                            ) {
                            LaunchedEffect(current, landingQuery) {
                                if (handheld) return@LaunchedEffect
                                settlingPageFocus = true
                                if (current != SettingsCategory.NAV_SOUNDS) withTimeoutOrNull(400) {
                                    snapshotFlow { listState.layoutInfo.totalItemsCount > 0 }.first { it }
                                }
                                val scrollTo = landingQuery?.let { if (current == SettingsCategory.WAKE) viewModel.wakeScrollIndex(state, it) else settingsSearchScrollIndex(current, it) }
                                if (scrollTo != null && scrollTo < listState.layoutInfo.totalItemsCount) {
                                    listState.scrollToItem(scrollTo)
                                }
                                val restore = returningLink
                                if (restore != null) {
                                    withFrameNanos { }
                                    val restored = runCatching { linkFocus[restore]?.requestFocus() == true }.getOrDefault(false)
                                    if (!restored) requestPanelContentFocus()
                                    returningLink = null
                                } else requestPanelContentFocus()
                                settlingPageFocus = false
                                if (landingQuery != null) searchLandingQuery = null
                            }
                            content(current, listState, panelModifier,
                                { scope.launch { requestPanelContentFocus() } }, ::requestExit)
                            }
                        }
                    }
                }
            }
        }
    }
    if (pendingExit != null) {
        if (handheld) {
            HandheldSettingsExitSheet(
                statusMessage = state.statusMessage,
                feedback = operationFeedback,
                onDismiss = ::dismissExitDialog,
                onDiscard = {
                    val action = pendingExit
                    viewModel.discardChanges()
                    pendingExit = null
                    action?.invoke()
                },
                onKeepEditing = ::dismissExitDialog,
            )
        } else {
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { dialogFocus.requestFocus() } }
            AppDialog(
                onDismiss = ::dismissExitDialog,
                title = stringResource(R.string.settings_leave_title),
                hint = stringResource(R.string.settings_leave_body),
                captureInitialFocus = false,
                footer = {
                    TvActionButton(stringResource(R.string.settings_discard), {
                        val action = pendingExit; viewModel.discardChanges(); pendingExit = null; action?.invoke()
                    })
                    TvActionButton(stringResource(R.string.settings_keep_editing), {
                        dismissExitDialog()
                    }, modifier = Modifier.focusRequester(dialogFocus))
                },
            ) {
                state.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                operationFeedback?.let { OperationFeedbackContent(it, Modifier.fillMaxWidth()) }
            }
        }
    }
}

/**
 * Handheld settings exit confirmation. A Material AlertDialog action slot is too cramped for the
 * three settings actions on narrow phones (and used to nest our TV-oriented action row inside it),
 * so phones use the same Material 3 bottom-sheet pattern as the rest of the handheld navigation.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HandheldSettingsExitSheet(
    statusMessage: String?,
    feedback: OperationFeedback?,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.Text(
                text = stringResource(R.string.settings_leave_title),
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            )
            androidx.compose.material3.Text(
                text = stringResource(R.string.settings_leave_body),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            statusMessage?.let {
                androidx.compose.material3.Text(
                    text = it,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(4.dp))
            feedback?.let { OperationFeedbackContent(it, Modifier.fillMaxWidth()) }
            androidx.compose.material3.FilledTonalButton(
                onClick = onKeepEditing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                androidx.compose.material3.Text(stringResource(R.string.settings_keep_editing))
            }
            androidx.compose.material3.TextButton(
                onClick = onDiscard,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.error,
                ),
            ) {
                androidx.compose.material3.Text(stringResource(R.string.settings_discard))
            }
        }
    }
}

private fun areaIcon(area: SettingsArea): ImageVector = when (area) {
    SettingsArea.START -> Icons.Filled.Home
    SettingsArea.SYSTEMS -> Icons.Filled.Storage
    SettingsArea.NETWORK -> Icons.Filled.SettingsEthernet
    SettingsArea.PLAYBACK -> Icons.Filled.PlayArrow
    SettingsArea.APP -> Icons.Filled.Info
}

/**
 * Systems & accounts lists only systems that are set up and signed in; the rest are offered as
 * add-system cards. The VPN page is infrastructure, so it is always listed.
 */
internal fun systemListed(category: SettingsCategory, state: SettingsUiState): Boolean =
    category == SettingsCategory.WIREGUARD ||
        category == SettingsCategory.NOTIFICATIONS || sourceConfigured(category, state)

internal fun settingsSystem(category: SettingsCategory): com.ambient.tv.ui.common.MediaSystem? = when (category) {
    SettingsCategory.PLEX -> com.ambient.tv.ui.common.MediaSystem.PLEX
    SettingsCategory.JELLYFIN -> com.ambient.tv.ui.common.MediaSystem.JELLYFIN
    SettingsCategory.EMBY -> com.ambient.tv.ui.common.MediaSystem.EMBY
    SettingsCategory.TVHEADEND -> com.ambient.tv.ui.common.MediaSystem.TVHEADEND
    SettingsCategory.IPTV -> com.ambient.tv.ui.common.MediaSystem.IPTV
    SettingsCategory.YOUTUBE -> com.ambient.tv.ui.common.MediaSystem.YOUTUBE
    SettingsCategory.TRAILERS -> com.ambient.tv.ui.common.MediaSystem.TRAILERS
    SettingsCategory.SPOTIFY -> com.ambient.tv.ui.common.MediaSystem.SPOTIFY
    SettingsCategory.DLNA -> com.ambient.tv.ui.common.MediaSystem.DLNA
    SettingsCategory.FILES -> com.ambient.tv.ui.common.MediaSystem.FILES
    else -> null
}

/** A system page is titled with the name the user gave that system. */
@Composable
internal fun settingsCategoryTitle(category: SettingsCategory): String =
    if (category == SettingsCategory.COMPANION) stringResource(
        if (LocalHandheldUi.current) R.string.companion_connect_tv else R.string.companion_pair_phone,
    ) else settingsSystem(category)?.customName ?: stringResource(category.titleRes)

/** Systems & accounts entries that are enabled and actually usable (can serve data / signed in). */
internal fun usableConfiguredSystems(state: SettingsUiState): List<SettingsCategory> =
    SettingsCategory.entries.filter { it.area == SettingsArea.SYSTEMS && sourceConfigured(it, state) }

/** When exactly one system qualifies, the Systems area opens that page instead of the index. */
internal fun singleUsableConfiguredSystem(state: SettingsUiState): SettingsCategory? =
    usableConfiguredSystems(state).singleOrNull()

internal fun sourceConfigured(category: SettingsCategory, state: SettingsUiState): Boolean = when (category) {
    SettingsCategory.PLEX -> state.plexEnabled && state.plexConfigured
    SettingsCategory.JELLYFIN -> state.jellyfinEnabled && state.jellyfinConfigured
    SettingsCategory.EMBY -> state.embyEnabled && state.embyConfigured
    SettingsCategory.TVHEADEND -> state.tvheadendEnabled && state.tvheadendHost.isNotBlank()
    SettingsCategory.YOUTUBE -> state.youtubeEnabled
    SettingsCategory.TRAILERS -> state.trailersConfigured
    SettingsCategory.SPOTIFY -> state.spotifyEnabled && state.spotifyWebAuthorized
    SettingsCategory.FILES -> state.filesEnabled && state.fileSourceCount > 0
    SettingsCategory.DLNA -> state.dlnaServerCount > 0
    SettingsCategory.IPTV -> state.iptvSourceCount > 0
    SettingsCategory.WIREGUARD -> state.wireGuardSummary.isNotBlank()
    else -> false
}

/** Home add-row chips: the system is on and can actually return data for that row. */
internal fun homeRowSourceAddable(system: String, contentKey: String, state: SettingsUiState): Boolean {
    if (!homeSystemReady(
            system,
            trailersReady = state.trailersConfigured,
            plexReady = sourceConfigured(SettingsCategory.PLEX, state),
            jellyfinReady = sourceConfigured(SettingsCategory.JELLYFIN, state),
            tvheadendReady = sourceConfigured(SettingsCategory.TVHEADEND, state),
            embyReady = sourceConfigured(SettingsCategory.EMBY, state),
            // Not gated on being signed in: which feeds need an account is decided per feed by
            // youtubeSupportedFeedKeys below, and Shorts/Music/News/Gaming/Live/Kids work
            // without one.
            youtubeReady = sourceConfigured(SettingsCategory.YOUTUBE, state) && state.youtubeAvailable,
            spotifyReady = sourceConfigured(SettingsCategory.SPOTIFY, state),
            photosReady = sourceConfigured(SettingsCategory.PLEX, state) ||
                sourceConfigured(SettingsCategory.JELLYFIN, state) || sourceConfigured(SettingsCategory.EMBY, state),
        )
    ) return false
    return system != HomeRowDefinition.SYSTEM_YOUTUBE || contentKey in state.youtubeSupportedFeedKeys
}

/** True when a Home row of this type is already in the editor list (enabled or disabled). */
internal fun homeRowAlreadyPresent(system: String, contentKey: String, state: SettingsUiState): Boolean =
    state.homeRows.any { it.system == system && it.contentKey == contentKey }

@Composable
private fun settingsPageSummary(category: SettingsCategory, state: SettingsUiState): String? {
    if (category == SettingsCategory.NOTIFICATIONS) return null
    if (category == SettingsCategory.DLNA) return null
    if (category == SettingsCategory.TRAILERS) return stringResource(if (state.trailersConfigured) R.string.trailers_connected else R.string.trailers_setup_hint)
    if (category == SettingsCategory.WIREGUARD) {
        return state.wireGuardSummary.ifBlank { null }
    }
    if (category.area == SettingsArea.SYSTEMS) {
        val enabled = when (category) {
            SettingsCategory.PLEX -> state.plexEnabled
            SettingsCategory.JELLYFIN -> state.jellyfinEnabled
            SettingsCategory.EMBY -> state.embyEnabled
            SettingsCategory.TVHEADEND -> state.tvheadendEnabled
            SettingsCategory.YOUTUBE -> state.youtubeEnabled
            SettingsCategory.SPOTIFY -> state.spotifyEnabled
            SettingsCategory.DLNA -> true
            SettingsCategory.FILES -> state.filesEnabled
            else -> false
        }
        val signedIn = when (category) {
            SettingsCategory.PLEX -> state.plexSignedIn
            SettingsCategory.JELLYFIN -> state.jellyfinConfigured
            SettingsCategory.EMBY -> state.embyConfigured
            SettingsCategory.YOUTUBE -> state.youtubeSignedIn
            SettingsCategory.SPOTIFY -> state.spotifyWebAuthorized
            else -> false
        }
        return stringResource(when {
            !enabled -> R.string.settings_source_disabled
            signedIn -> R.string.settings_source_signed_in
            category == SettingsCategory.YOUTUBE && !state.youtubeAuthKnown -> R.string.settings_source_check
            category == SettingsCategory.YOUTUBE -> R.string.settings_source_guest
            sourceConfigured(category, state) -> R.string.settings_source_configured
            else -> R.string.settings_source_not_configured
        })
    }
    return when (category) {
        SettingsCategory.PLAYBACK -> if (state.playbackBackendKey == "auto") stringResource(R.string.player_automatic) else state.playbackBackendKey.uppercase()
        SettingsCategory.BACKGROUND -> if (state.visibleBackgroundVideoPlayback) stringResource(R.string.settings_background_video_visibility, 100 - state.backgroundVideoDimPercent) else stringResource(R.string.player_off)
        SettingsCategory.LIBRARY -> "${state.libraryGridColumns} × ${state.libraryListRows}"
        SettingsCategory.SEEK -> stringResource(R.string.settings_seek_jump_seconds, state.bigSeekJumpSeconds)
        SettingsCategory.AUDIO -> when (state.audioSettings.selectedProfile) { "auto" -> stringResource(R.string.audio_profile_auto); "avr" -> "HDMI / AVR"; "headphones" -> stringResource(R.string.audio_headphones); else -> "TV" }
        else -> null
    }
}

@Composable
internal fun SettingsHubRow(title: String, subtitle: String?, selected: Boolean, icon: ImageVector, modifier: Modifier = Modifier,
    // A longer label is clipped while its column widens instead of briefly wrapping to two lines.
    singleLine: Boolean = false, onClick: () -> Unit) {
    val uiSounds = LocalUiSoundPlayer.current
    val activate = com.ambient.tv.ui.sound.rememberUiSoundAction(onClick = onClick)
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(onClick = activate, colors = transparentSurfaceColors(), scale = noSurfaceScale(), shape = ClickableSurfaceDefaults.shape(shape),
        modifier = modifier.fillMaxWidth().activateOnTvKey(onClick = activate).onFocusChanged { focused = it.isFocused; if (it.isFocused) uiSounds?.playFocus() }) {
        Row(Modifier.fillMaxWidth()
            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.17f) else if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f) else Color.Transparent, shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color.Transparent, shape)
            .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, softWrap = !singleLine, maxLines = if (singleLine) 1 else Int.MAX_VALUE, overflow = TextOverflow.Clip)
                subtitle?.takeIf { focused || LocalHandheldUi.current }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** Queries the focused descendant's bounds on demand (replaces the deprecated onFocusedBoundsChanged). */
private class FocusedRectProbe {
    private var node: FocusTargetModifierNode? = null
    val modifier: Modifier = FocusedRectElement(this)
    fun rect(): androidx.compose.ui.geometry.Rect? = node?.takeIf { it.node.isAttached }?.getFocusedRect()

    private class FocusedRectElement(val probe: FocusedRectProbe) : ModifierNodeElement<FocusedRectNode>() {
        override fun create() = FocusedRectNode(probe)
        override fun update(node: FocusedRectNode) { node.probe = probe }
        override fun equals(other: Any?) = other is FocusedRectElement && other.probe === probe
        override fun hashCode() = System.identityHashCode(probe)
    }

    private class FocusedRectNode(var probe: FocusedRectProbe) : DelegatingNode() {
        val target = delegate(FocusTargetModifierNode(focusability = Focusability.Never))
        override fun onAttach() { probe.node = target }
        override fun onDetach() { if (probe.node === target) probe.node = null }
    }
}
