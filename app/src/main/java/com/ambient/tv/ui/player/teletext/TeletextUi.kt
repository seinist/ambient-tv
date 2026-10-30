@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ambient.tv.ui.player.teletext

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.livetv.teletext.HbbtvApplication
import com.ambient.tv.livetv.teletext.LiveTeletext
import com.ambient.tv.livetv.teletext.TELETEXT_COLORS
import com.ambient.tv.livetv.teletext.TeletextPage
import com.ambient.tv.livetv.teletext.TeletextRow
import com.ambient.tv.livetv.teletext.layoutTeletextPage
import com.ambient.tv.livetv.teletext.teletextDecimal
import com.ambient.tv.livetv.teletext.teletextStepPage
import com.ambient.tv.livetv.teletext.text
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.PlayerPanelEntry
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.OsdButton
import com.ambient.tv.ui.player.applySubtitle
import com.ambient.tv.ui.player.videoImagePreferences
import kotlinx.coroutines.delay

/** UI state of live teletext, shared by the host, the panel entries and the OSD buttons. */
object TeletextUi {
    /** A live player that can show teletext is on screen. */
    var hostActive by mutableStateOf(false)
        internal set
    var viewerOpen by mutableStateOf(false)
    /** The subtitle panel lists teletext pages: decode meanwhile to find them. */
    internal var probing by mutableStateOf(false)
    /** "off", "auto" or a decimal page such as "150". */
    var subtitleChoice by mutableStateOf("off")
        private set
    /** A stored choice only replaces native subtitles on a live channel that carries teletext. */
    internal val subtitlesActive: Boolean
        get() = hostActive && subtitleChoice != "off" && LiveTeletext.status.value.hasTeletext
    private var lastOnChoice = "auto"

    internal fun load(context: Context) {
        subtitleChoice = videoImagePreferences(context).teletextSubtitleChoice
        if (subtitleChoice != "off") lastOnChoice = subtitleChoice
    }

    fun setSubtitleChoice(context: Context, choice: String) {
        BackgroundPlaybackSession.manualTeletextSubtitleSelection()
        updateSubtitleChoice(context, choice)
    }

    /** Native subtitle selection clears the overlay without selecting another native track. */
    internal fun clearSubtitleChoice(context: Context) {
        if (subtitleChoice != "off") updateSubtitleChoice(context, "off")
    }

    private fun updateSubtitleChoice(context: Context, choice: String) {
        subtitleChoice = choice
        if (choice != "off") lastOnChoice = choice
        videoImagePreferences(context).teletextSubtitleChoice = choice
    }

    fun toggleSubtitles(context: Context) = setSubtitleChoice(context, if (subtitleChoice == "off") lastOnChoice else "off")
}

private fun subtitleLanguages(context: Context): Set<String> =
    com.ambient.tv.data.local.LanguagePreferences.playbackLanguageTags(videoImagePreferences(context).playbackSubtitleLanguage).toSet()

/**
 * Teletext for the live channel at [url]: decoding while needed, the subtitle overlay and the
 * viewer. Placed once per live player page, above the engine surfaces.
 */
@Composable
fun LiveTeletextHost(
    url: String,
    headers: Map<String, String>,
    ownConnectionAllowed: Boolean,
    channelName: String?,
    channelKey: String,
    hbbtvFallback: HbbtvApplication? = null,
) {
    val context = LocalContext.current
    var activeChannelKey by remember { mutableStateOf(channelKey) }
    // Without Timeshift a second connection is needed; IPTV providers often allow only one.
    val readable = ownConnectionAllowed || com.ambient.tv.livetv.isLocalTimeshiftUrl(url)
    DisposableEffect(Unit) {
        TeletextUi.load(context)
        TeletextUi.hostActive = true
        onDispose {
            TeletextUi.hostActive = false
            TeletextUi.viewerOpen = false
            HbbtvUi.close()
            LiveTeletext.setNeeded(false)
            LiveTeletext.stop()
        }
    }
    // Probe every channel: teletext and HbbTV entries only appear when the stream carries them.
    LaunchedEffect(url, headers, readable, channelKey, hbbtvFallback) {
        // A renderer or Timeshift URL change keeps the current service open. A real channel
        // change also resets a decoder whose proxy URL happens to stay the same.
        if (activeChannelKey != channelKey) {
            TeletextUi.viewerOpen = false
            HbbtvUi.close()
            LiveTeletext.stop()
        }
        if (readable) LiveTeletext.start(url, headers) else LiveTeletext.stop()
        // Server metadata is only a fallback. If the stream later yields an AIT application,
        // LiveTeletext replaces this with the broadcaster-announced app automatically.
        LiveTeletext.setHbbtvFallback(hbbtvFallback)
        activeChannelKey = channelKey
    }
    val needed = TeletextUi.viewerOpen || TeletextUi.probing || TeletextUi.subtitleChoice != "off"
    LaunchedEffect(needed) { LiveTeletext.setNeeded(needed) }
    LaunchedEffect(TeletextUi.subtitleChoice) {
        LiveTeletext.setSubtitleChoice(TeletextUi.subtitleChoice, subtitleLanguages(context))
    }
    val status by LiveTeletext.status.collectAsState()
    val handle = BackgroundPlaybackSession.activeHandle()
    val subtitlesActive = status.hasTeletext && TeletextUi.subtitleChoice != "off" &&
        BackgroundPlaybackSession.snapshot.value.metadata?.isLive == true
    LaunchedEffect(handle, subtitlesActive) {
        // Restoring a choice or replacing the renderer must also disable its native subtitles.
        // Recheck after start(): composition may still have seen the previous channel's status.
        if (subtitlesActive && handle === BackgroundPlaybackSession.activeHandle() &&
            BackgroundPlaybackSession.snapshot.value.metadata?.isLive == true && TeletextUi.subtitlesActive) {
            handle?.applySubtitle(null)
        }
    }
    // Do not mount the previous channel's service under the new channel identity while its
    // reset effect is pending. This includes the HbbTV consent dialog and broadcast callbacks.
    if (activeChannelKey == channelKey) {
        TeletextSubtitleOverlay()
        if (TeletextUi.viewerOpen) TeletextViewer(onClose = { TeletextUi.viewerOpen = false })
        HbbtvHost(channelName, channelKey)
    }
}

/** Teletext key (TEXT) opens teletext, the red key the channel's HbbTV application, when offered. */
fun liveServiceKey(context: Context, keyCode: Int): Boolean {
    if (!TeletextUi.hostActive || TeletextUi.viewerOpen || HbbtvUi.open != null) return false
    val status = LiveTeletext.status.value
    return when (keyCode) {
        AndroidKeyEvent.KEYCODE_TV_TELETEXT -> status.hasTeletext.also { if (it) TeletextUi.viewerOpen = true }
        AndroidKeyEvent.KEYCODE_PROG_RED -> (status.hbbtv != null).also { if (it) HbbtvUi.request(context, status.hbbtv!!) }
        else -> false
    }
}

@Composable
private fun TeletextSubtitleOverlay() {
    val lines by LiveTeletext.subtitles.collectAsState()
    if (TeletextUi.subtitleChoice == "off" || TeletextUi.viewerOpen || lines.isEmpty()) return
    Box(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, bottom = 56.dp), contentAlignment = Alignment.BottomCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            lines.forEach { line ->
                // Black on black would vanish: black text is shown white.
                val rgb = TELETEXT_COLORS[line.color.takeIf { it != 0 } ?: 7]
                Text(
                    line.text,
                    color = Color(0xFF000000.toInt() or rgb),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Live-TV panel rows: open teletext and the broadcaster's HbbTV application. */
@Composable
fun TeletextPanelEntries() {
    if (!TeletextUi.hostActive) return
    val context = LocalContext.current
    val status by LiveTeletext.status.collectAsState()
    val navigation = LocalPlayerPanelNavigation.current
    if (status.hasTeletext) PlayerPanelEntry(
        icon = Icons.AutoMirrored.Filled.Article,
        title = stringResource(R.string.player_teletext),
        value = stringResource(R.string.player_teletext_open),
        onClick = { navigation?.close(); TeletextUi.viewerOpen = true },
    )
    status.hbbtv?.let { app ->
        PlayerPanelEntry(
            icon = Icons.Filled.ConnectedTv,
            title = stringResource(R.string.player_hbbtv),
            value = app.name ?: android.net.Uri.parse(app.url).host.orEmpty(),
            onClick = { navigation?.close(); HbbtvUi.request(context, app) },
        )
    }
}

/** Continue discovering subtitle pages while the shared subtitle panel is open, even when off. */
@Composable
internal fun ProbeTeletextSubtitlePages() {
    if (!TeletextUi.hostActive) return
    val status by LiveTeletext.status.collectAsState()
    if (!status.hasTeletext) return
    DisposableEffect(Unit) {
        TeletextUi.probing = true
        onDispose { TeletextUi.probing = false }
    }
}

/** Raw teletext choices for the shared subtitle selector; its native entry supplies "Off". */
@Composable
internal fun teletextSubtitleOptions(): List<Pair<String, String>> {
    if (!TeletextUi.hostActive) return emptyList()
    val status by LiveTeletext.status.collectAsState()
    if (!status.hasTeletext) return emptyList()
    val teletextLabel = stringResource(R.string.player_teletext)
    val pageLabel = stringResource(R.string.player_teletext_subtitle_page)
    val hearing = stringResource(R.string.player_teletext_hearing)
    val pages = buildList {
        status.announced.filter { it.subtitle }.distinctBy { it.number }.forEach { page ->
            add(teletextDecimal(page.number).toString() to listOfNotNull(
                pageLabel.format(teletextDecimal(page.number)),
                page.language.uppercase().takeIf { it.isNotBlank() },
                hearing.takeIf { page.hearingImpaired },
            ).joinToString(" · "))
        }
        status.subtitlePagesSeen.forEach { number ->
            val key = teletextDecimal(number).toString()
            if (none { it.first == key }) add(key to pageLabel.format(teletextDecimal(number)))
        }
        val chosen = TeletextUi.subtitleChoice
        chosen.toIntOrNull()?.takeIf { it in 100..899 }?.let { number ->
            if (none { it.first == chosen }) add(chosen to pageLabel.format(number))
        }
    }
    val auto = status.subtitlePage.takeIf { TeletextUi.subtitleChoice == "auto" }
    val options = listOf(
        "auto" to (auto?.let { stringResource(R.string.player_teletext_subtitles_auto_value, teletextDecimal(it)) }
            ?: stringResource(R.string.player_teletext_subtitles_auto)),
    ) + pages
    return options.map { (key, label) -> key to "$teletextLabel · $label" }
}

/** Opt-in action-bar buttons of the live player: teletext and teletext subtitles. */
@Composable
fun TeletextOsdButtons() {
    if (!TeletextUi.hostActive) return
    val context = LocalContext.current
    val status by LiveTeletext.status.collectAsState()
    status.hbbtv?.let { app ->
        PlayerIconButton(Icons.Filled.ConnectedTv, stringResource(R.string.player_hbbtv),
            { HbbtvUi.request(context, app) }, osd = OsdButton.HBBTV)
    }
    // Only for streams with teletext; elsewhere the buttons are not offered at all.
    if (!status.hasTeletext) return
    PlayerIconButton(Icons.AutoMirrored.Filled.Article, stringResource(R.string.player_teletext),
        { TeletextUi.viewerOpen = true }, osd = OsdButton.TELETEXT)
    PlayerIconButton(Icons.Filled.ClosedCaption, stringResource(R.string.player_teletext_subtitles),
        { TeletextUi.toggleSubtitles(context) }, selected = TeletextUi.subtitleChoice != "off",
        osd = OsdButton.TELETEXT_SUBTITLES)
}

/**
 * Full-screen teletext with app controls below the page. The reading area accepts ▲▼; the
 * toolbar uses ordinary D-pad focus navigation. Digits and channel/page keys work in either.
 */
@Composable
private fun TeletextViewer(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
            onDispose { }
        }
        val status by LiveTeletext.status.collectAsState()
        var number by remember { mutableIntStateOf(0x100) }
        var subcode by remember { mutableStateOf<Int?>(null) }
        var entry by remember { mutableStateOf("") }
        var mix by remember { mutableStateOf(false) }
        var showList by remember { mutableStateOf(false) }
        var showKeypad by remember { mutableStateOf(false) }
        var readingFocused by remember { mutableStateOf(false) }
        var focusedDynamicControl by remember { mutableStateOf<TeletextDynamicControl?>(null) }
        var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }
        val readingFocus = remember { FocusRequester() }
        val pageFocus = remember { FocusRequester() }
        val listFocus = remember { FocusRequester() }
        var blinkOn by remember { mutableStateOf(true) }
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(500); blinkOn = !blinkOn; tick++ } }
        val page = remember(status.revision, number, subcode, tick / 2) { LiveTeletext.page(number, subcode) }
        val header = remember(status.revision, tick / 2) { LiveTeletext.lastHeader() }
        val subcodes = remember(status.revision, number) { LiveTeletext.subcodes(number) }
        val received = remember(status.revision) { LiveTeletext.pageNumbers() }
        val links = page?.links?.take(4).orEmpty()
        fun go(target: Int) { number = target; subcode = null; entry = "" }
        fun follow(link: Int) { links.getOrNull(link)?.takeIf { it != 0 }?.let(::go) }
        fun closeKeypad() {
            showKeypad = false
            entry = ""
            returnFocus = pageFocus
        }
        fun closePageList() {
            showList = false
            // A Timeshift/renderer restart may have cleared the cache while this dialog was
            // open. Its disabled trigger cannot receive focus until pages have arrived again.
            returnFocus = if (received.isNotEmpty()) listFocus else pageFocus
        }
        fun enterDigit(digit: Int) {
            val result = appendTeletextDigit(entry, digit)
            entry = result.digits
            result.page?.let {
                go(it)
                if (showKeypad) closeKeypad()
            }
        }
        fun stepSubpage(forward: Boolean) {
            subcode = stepTeletextSubpage(subcode ?: page?.subcode, subcodes, forward)
        }
        fun controlModifier(dynamicControl: TeletextDynamicControl? = null): Modifier =
            Modifier.onFocusChanged { if (it.isFocused) focusedDynamicControl = dynamicControl }
        LaunchedEffect(Unit) { readingFocus.requestTeletextFocus() }
        // Remember this before applyChanges removes the focused node and Compose attempts its
        // own focus recovery; that must not replace our deterministic fallback with a neighbour.
        val focusedControlDisappeared = teletextControlDisappeared(focusedDynamicControl, links, subcodes.size, received.isNotEmpty())
        LaunchedEffect(links, subcodes.size > 1, received.isNotEmpty()) {
            if (focusedControlDisappeared && !showList && !showKeypad) pageFocus.requestFocus()
        }
        LaunchedEffect(showList, showKeypad, returnFocus) {
            val target = returnFocus
            if (!showList && !showKeypad && target != null) {
                // Let the child dialog release its window before returning to its exact trigger.
                target.requestTeletextFocus()
                returnFocus = null
            }
        }
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .background(if (mix) Color.Transparent else Color.Black)
                .safeDrawingPadding()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val digit = event.teletextDigit()
                    if (digit != null) {
                        if (event.nativeKeyEvent.repeatCount == 0) enterDigit(digit)
                        return@onPreviewKeyEvent true
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_PROG_RED -> { follow(0); return@onPreviewKeyEvent true }
                        AndroidKeyEvent.KEYCODE_PROG_GREEN -> { follow(1); return@onPreviewKeyEvent true }
                        AndroidKeyEvent.KEYCODE_PROG_YELLOW -> { follow(2); return@onPreviewKeyEvent true }
                        AndroidKeyEvent.KEYCODE_PROG_BLUE -> { follow(3); return@onPreviewKeyEvent true }
                    }
                    when (event.key) {
                        Key.ChannelUp, Key.PageUp -> { go(teletextStepPage(number, received, true)); true }
                        Key.ChannelDown, Key.PageDown -> { go(teletextStepPage(number, received, false)); true }
                        Key.DirectionUp -> if (readingFocused) {
                            go(teletextStepPage(number, received, true)); true
                        } else false
                        Key.DirectionDown -> if (readingFocused) {
                            go(teletextStepPage(number, received, false)); true
                        } else false
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (readingFocused) {
                            pageFocus.requestFocus(); true
                        } else false
                        else -> false
                    }
                },
        ) {
            val toolbarMaxHeight = maxHeight * 0.55f
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                // The Column reserves the toolbar's actual measured height. On a short phone
                // the toolbar scrolls, so every control remains reachable without covering text.
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .focusRequester(readingFocus)
                        .onFocusChanged {
                            readingFocused = it.isFocused
                            if (it.isFocused) focusedDynamicControl = null
                        }
                        .focusable(),
                ) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val pageHeight = minOf(maxHeight * 0.96f, maxWidth * 0.75f)
                        val shown = page ?: header?.let { TeletextPage(number, 0, it.nationalOption, false, false, it.header) }
                        if (shown != null) {
                            val rows = remember(shown, number) { layoutTeletextPage(shown, number) }
                            TeletextPageCanvas(rows, mix, blinkOn, Modifier.size(width = pageHeight * (4f / 3f), height = pageHeight))
                        }
                        val message = when {
                            status.noTeletext -> stringResource(R.string.player_teletext_none)
                            !status.hasTeletext -> stringResource(R.string.player_teletext_searching)
                            page == null -> stringResource(R.string.player_teletext_page_searching, teletextDecimal(number))
                            else -> null
                        }
                        if (message != null) Text(message, color = Color.White, fontSize = 20.sp,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 16.dp, vertical = 8.dp))
                        if (entry.isNotEmpty()) Text(entry.padEnd(3, '–'), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 12.dp))
                    }
                    if (readingFocused) Text(
                        stringResource(R.string.player_teletext_reading_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.65f)).padding(4.dp),
                    )
                }
                BroadcastToolbar(Modifier.heightIn(max = toolbarMaxHeight).verticalScroll(rememberScrollState())) {
                    if (links.any { it != 0 }) BroadcastControlRow {
                        // Key by colour, not its page target: incoming packets can change the
                        // labels without moving focus to another colour or a neighbouring action.
                        repeat(4) { index ->
                            key(index) {
                                val link = links.getOrNull(index) ?: 0
                                if (link != 0) BroadcastColorButton(
                                    index = index,
                                    text = teletextDecimal(link).toString(),
                                    modifier = controlModifier(TeletextDynamicControl.entries[index]),
                                    onClick = { go(link) },
                                )
                            }
                        }
                    }
                    BroadcastControlRow {
                        TvChip(
                            stringResource(R.string.player_teletext_page_previous),
                            icon = Icons.Filled.ChevronLeft,
                            modifier = controlModifier(),
                            onClick = { go(teletextStepPage(number, received, false)) },
                        )
                        val pageLabel = stringResource(R.string.player_teletext_page, teletextDecimal(number))
                        val inputLabel = stringResource(R.string.player_teletext_page_input)
                        TvChip(
                            pageLabel,
                            icon = Icons.Filled.Dialpad,
                            modifier = controlModifier().focusRequester(pageFocus)
                                .semantics { contentDescription = "$pageLabel · $inputLabel" },
                            onClick = { showKeypad = true },
                        )
                        TvChip(
                            stringResource(R.string.player_teletext_page_next),
                            icon = Icons.Filled.ChevronRight,
                            modifier = controlModifier(),
                            onClick = { go(teletextStepPage(number, received, true)) },
                        )
                    }
                    if (subcodes.size > 1) BroadcastControlRow {
                        TvChip(
                            stringResource(R.string.player_teletext_subpage_previous),
                            icon = Icons.Filled.ChevronLeft,
                            modifier = controlModifier(TeletextDynamicControl.PREVIOUS_SUBPAGE),
                            onClick = { stepSubpage(false) },
                        )
                        val index = subcodes.indexOf(subcode ?: page?.subcode).coerceAtLeast(0) + 1
                        Text(
                            stringResource(R.string.player_teletext_subpage, index, subcodes.size),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
                        )
                        TvChip(
                            stringResource(R.string.player_teletext_subpage_next),
                            icon = Icons.Filled.ChevronRight,
                            modifier = controlModifier(TeletextDynamicControl.NEXT_SUBPAGE),
                            onClick = { stepSubpage(true) },
                        )
                    }
                    BroadcastControlRow {
                        TvChip(
                            stringResource(R.string.player_teletext_start),
                            icon = Icons.Filled.Home,
                            modifier = controlModifier(),
                            onClick = { go(0x100) },
                        )
                        TvChip(
                            stringResource(R.string.player_teletext_pages),
                            icon = Icons.AutoMirrored.Filled.ViewList,
                            enabled = received.isNotEmpty(),
                            modifier = controlModifier(TeletextDynamicControl.PAGES).focusRequester(listFocus),
                            onClick = { showList = true },
                        )
                        TvChip(
                            stringResource(R.string.player_teletext_mix_value, stringResource(if (mix) R.string.player_on else R.string.player_off)),
                            icon = Icons.Filled.Layers,
                            selected = mix,
                            modifier = controlModifier(),
                            onClick = { mix = !mix },
                        )
                        TvChip(
                            stringResource(R.string.action_close),
                            icon = Icons.Filled.Close,
                            modifier = controlModifier(),
                            onClick = onClose,
                        )
                    }
                }
            }
        }
        if (showList) TeletextPageList(
            received = received,
            currentPage = number,
            revision = status.revision,
            onPick = { go(it); closePageList() },
            onDismiss = ::closePageList,
        )
        if (showKeypad) TeletextPageKeypad(
            entry = entry,
            onDigit = ::enterDigit,
            onErase = { entry = entry.dropLast(1) },
            onDismiss = ::closeKeypad,
        )
    }
}

private fun KeyEvent.teletextDigit(): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3; Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7; Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9; else -> null
}

private fun Modifier.teletextDigitInput(onDigit: (Int) -> Unit): Modifier = onPreviewKeyEvent { event ->
    val digit = event.teletextDigit()
    if (digit == null) false else {
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) onDigit(digit)
        true
    }
}

/** Modal windows and lazy rows can attach after composition; retry only the explicit focus move. */
private suspend fun FocusRequester.requestTeletextFocus() {
    repeat(8) {
        withFrameNanos { }
        if (runCatching { requestFocus() }.getOrDefault(false)) return
    }
}

@Composable
private fun TeletextPageKeypad(entry: String, onDigit: (Int) -> Unit, onErase: () -> Unit, onDismiss: () -> Unit) {
    val firstDigitFocus = remember { FocusRequester() }
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.player_teletext_page_input),
        hint = stringResource(R.string.player_teletext_page_input_hint),
        minWidth = 0.dp,
        maxWidth = 420.dp,
        maxHeightFraction = 0.9f,
        captureInitialFocus = false,
        footer = {
            TvActionButton(
                stringResource(R.string.player_teletext_erase),
                onClick = onErase,
                modifier = Modifier.teletextDigitInput(onDigit),
                icon = Icons.AutoMirrored.Filled.Backspace,
                enabled = entry.isNotEmpty(),
            )
            TvActionButton(
                stringResource(R.string.action_close),
                onClick = onDismiss,
                modifier = Modifier.teletextDigitInput(onDigit),
                icon = Icons.Filled.Close,
            )
        },
    ) {
        LaunchedEffect(Unit) { firstDigitFocus.requestTeletextFocus() }
        Column(
            Modifier.fillMaxWidth().teletextDigitInput(onDigit),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                entry.padEnd(3, '–'),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            repeat(3) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { column ->
                        val digit = row * 3 + column + 1
                        TvActionButton(
                            digit.toString(),
                            onClick = { onDigit(digit) },
                            enabled = digit != 9 || entry.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                                .then(if (digit == 1) Modifier.focusRequester(firstDigitFocus) else Modifier),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.weight(1f))
                TvActionButton("0", { onDigit(0) }, Modifier.weight(1f), enabled = entry.isNotEmpty())
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TeletextPageList(
    received: List<Int>,
    currentPage: Int,
    revision: Long,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialPage = remember { currentPage.takeIf { it in received } ?: received.firstOrNull() }
    val initialFocus = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = received.indexOf(initialPage).coerceAtLeast(0))
    AppDialog(onDismiss = onDismiss, title = stringResource(R.string.player_teletext_pages), scrollable = false, captureInitialFocus = false) {
        LaunchedEffect(Unit) { initialFocus.requestTeletextFocus() }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), state = listState) {
            items(received, key = { it }) { number ->
                // First line with text beneath the header, as a hint what the page holds.
                val snippet = remember(number, revision) {
                    LiveTeletext.page(number)?.let { page ->
                        layoutTeletextPage(page).drop(1).map { it.text().trim() }.firstOrNull { it.length >= 3 }
                    }.orEmpty()
                }
                TvActionButton(
                    "${teletextDecimal(number)}  $snippet".trimEnd(),
                    { onPick(number) },
                    modifier = if (number == initialPage) Modifier.focusRequester(initialFocus) else Modifier,
                    selected = number == currentPage,
                    fillWidth = true,
                )
            }
        }
    }
}

/** Draws 40 × 25 cells: background, text (double height stretched), mosaics as sixels. */
@Composable
private fun TeletextPageCanvas(rows: List<TeletextRow>, mix: Boolean, blinkOn: Boolean, modifier: Modifier) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER } }
    Canvas(modifier) {
        val cellW = size.width / 40f
        val cellH = size.height / 25f
        paint.textScaleX = 1f
        paint.textSize = cellH * 0.92f
        val glyph = paint.measureText("M").takeIf { it > 0f } ?: cellW
        paint.textScaleX = (cellW * 0.98f / glyph).coerceIn(0.5f, 2f)
        rows.forEachIndexed { r, row ->
            if (row.hidden) return@forEachIndexed
            val rowHeight = if (row.doubleHeight) cellH * 2 else cellH
            val top = r * cellH
            row.cells.forEachIndexed { c, cell ->
                val left = c * cellW
                if (!mix) drawRect(Color(0xFF000000.toInt() or TELETEXT_COLORS[cell.background]), Offset(left, top), Size(cellW + 0.6f, rowHeight + 0.6f))
                if ((cell.flash && !blinkOn) || cell.conceal) return@forEachIndexed
                val fg = Color(0xFF000000.toInt() or TELETEXT_COLORS[cell.foreground])
                val height = if (cell.doubleHeight) cellH * 2 else cellH
                if (cell.mosaic >= 0) {
                    val sw = cellW / 2f
                    val sh = height / 3f
                    val gap = if (cell.separated) cellW / 8f else 0f
                    for (bit in 0 until 6) if (cell.mosaic and (1 shl bit) != 0) {
                        drawRect(fg, Offset(left + (bit % 2) * sw + gap, top + (bit / 2) * sh + gap),
                            Size(sw - gap + 0.4f, sh - gap + 0.4f))
                    }
                } else if (cell.char != ' ') {
                    paint.color = android.graphics.Color.rgb(TELETEXT_COLORS[cell.foreground] shr 16 and 0xFF,
                        TELETEXT_COLORS[cell.foreground] shr 8 and 0xFF, TELETEXT_COLORS[cell.foreground] and 0xFF)
                    if (mix) paint.setShadowLayer(cellH * 0.12f, 0f, 0f, android.graphics.Color.BLACK) else paint.clearShadowLayer()
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        native.translate(left + cellW / 2f, top)
                        if (cell.doubleHeight) native.scale(1f, 2f)
                        native.drawText(cell.char.toString(), 0f, cellH * 0.8f, paint)
                        native.restore()
                    }
                }
            }
        }
    }
}
