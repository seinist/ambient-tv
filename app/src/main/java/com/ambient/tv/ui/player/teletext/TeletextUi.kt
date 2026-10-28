package com.ambient.tv.ui.player.teletext

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.livetv.teletext.LiveTeletext
import com.ambient.tv.livetv.teletext.TELETEXT_COLORS
import com.ambient.tv.livetv.teletext.TeletextPage
import com.ambient.tv.livetv.teletext.TeletextRow
import com.ambient.tv.livetv.teletext.layoutTeletextPage
import com.ambient.tv.livetv.teletext.teletextDecimal
import com.ambient.tv.livetv.teletext.teletextNumber
import com.ambient.tv.livetv.teletext.teletextStepPage
import com.ambient.tv.livetv.teletext.text
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.common.PlayerPanelEntry
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.player.OsdButton
import com.ambient.tv.ui.player.videoImagePreferences
import kotlinx.coroutines.delay

/** UI state of live teletext, shared by the host, the panel entries and the OSD buttons. */
object TeletextUi {
    /** A live player that can show teletext is on screen. */
    var hostActive by mutableStateOf(false)
        internal set
    var viewerOpen by mutableStateOf(false)
    /** The settings panel lists teletext subtitle pages: decode meanwhile to find them. */
    internal var probing by mutableStateOf(false)
    /** "off", "auto" or a decimal page such as "150". */
    var subtitleChoice by mutableStateOf("off")
        private set
    private var lastOnChoice = "auto"

    internal fun load(context: Context) {
        subtitleChoice = videoImagePreferences(context).teletextSubtitleChoice
        if (subtitleChoice != "off") lastOnChoice = subtitleChoice
    }

    fun setSubtitleChoice(context: Context, choice: String) {
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
fun LiveTeletextHost(url: String, headers: Map<String, String>, ownConnectionAllowed: Boolean, channelName: String?) {
    val context = LocalContext.current
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
    LaunchedEffect(url, headers, readable) {
        if (readable) LiveTeletext.start(url, headers) else LiveTeletext.stop()
    }
    val needed = TeletextUi.viewerOpen || TeletextUi.probing || TeletextUi.subtitleChoice != "off"
    LaunchedEffect(needed) { LiveTeletext.setNeeded(needed) }
    LaunchedEffect(TeletextUi.subtitleChoice) {
        LiveTeletext.setSubtitleChoice(TeletextUi.subtitleChoice, subtitleLanguages(context))
    }
    TeletextSubtitleOverlay()
    if (TeletextUi.viewerOpen) TeletextViewer(onClose = { TeletextUi.viewerOpen = false })
    HbbtvHost(channelName)
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

/** Settings panel rows of a live player: open teletext and choose teletext subtitles. */
@Composable
fun TeletextPanelEntries() {
    if (!TeletextUi.hostActive) return
    val context = LocalContext.current
    val status by LiveTeletext.status.collectAsState()
    val navigation = LocalPlayerPanelNavigation.current
    status.hbbtv?.let { app ->
        PlayerPanelEntry(
            icon = Icons.Filled.ConnectedTv,
            title = stringResource(R.string.player_hbbtv),
            value = app.name ?: android.net.Uri.parse(app.url).host.orEmpty(),
            onClick = { navigation?.close(); HbbtvUi.request(context, app) },
        )
    }
    // Shown only when the stream carries teletext.
    if (!status.hasTeletext) return
    DisposableEffect(Unit) {
        TeletextUi.probing = true
        onDispose { TeletextUi.probing = false }
    }
    PlayerPanelEntry(
        icon = Icons.AutoMirrored.Filled.Article,
        title = stringResource(R.string.player_teletext),
        value = stringResource(R.string.player_teletext_open),
        onClick = { navigation?.close(); TeletextUi.viewerOpen = true },
    )
    val pageLabel = stringResource(R.string.player_teletext_subtitle_page)
    val hearing = stringResource(R.string.player_teletext_hearing)
    val pages = buildList {
        status.announced.filter { it.subtitle }.forEach { page ->
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
        if (chosen != "off" && chosen != "auto" && none { it.first == chosen }) add(chosen to pageLabel.format(chosen.toIntOrNull() ?: 0))
    }
    val auto = status.subtitlePage.takeIf { TeletextUi.subtitleChoice == "auto" }
    val options = listOf(
        "off" to stringResource(R.string.player_off),
        "auto" to (auto?.let { stringResource(R.string.player_teletext_subtitles_auto_value, teletextDecimal(it)) }
            ?: stringResource(R.string.player_teletext_subtitles_auto)),
    ) + pages
    PlayerDropUpSelector(
        label = stringResource(R.string.player_teletext_subtitles),
        value = options.firstOrNull { it.first == TeletextUi.subtitleChoice }?.second ?: options.first().second,
        options = options,
        selectedKey = TeletextUi.subtitleChoice,
        icon = Icons.Filled.ClosedCaption,
        onSelected = { TeletextUi.setSubtitleChoice(context, it) },
    )
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

private val LINK_COLORS = listOf(Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFDD835), Color(0xFF00ACC1))

/**
 * Full-screen teletext over the picture. Digits or ▲▼ / channel keys choose the page, the colour
 * keys follow the page's links; the bar below has subpages, a page list, index, transparency.
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
        var blinkOn by remember { mutableStateOf(true) }
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(500); blinkOn = !blinkOn; tick++ } }
        val page = remember(status.revision, number, subcode, tick / 2) { LiveTeletext.page(number, subcode) }
        val header = remember(status.revision, tick / 2) { LiveTeletext.lastHeader() }
        val subcodes = remember(status.revision, number) { LiveTeletext.subcodes(number) }
        val received = remember(status.revision) { LiveTeletext.pageNumbers() }
        fun go(target: Int) { number = target; subcode = null; entry = "" }
        fun follow(link: Int) { page?.links?.getOrNull(link)?.takeIf { it != 0 }?.let(::go) }
        fun stepSubpage(forward: Boolean) {
            if (subcodes.isEmpty()) return
            val index = subcodes.indexOf(subcode ?: page?.subcode).takeIf { it >= 0 } ?: (subcodes.size - 1)
            subcode = subcodes[(index + if (forward) 1 else subcodes.size - 1) % subcodes.size]
        }
        val firstButton = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { firstButton.requestFocus() } }
        Box(
            Modifier.fillMaxSize()
                .background(if (mix) Color.Transparent else Color.Black)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val digit = when (event.key) {
                        Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2
                        Key.Three, Key.NumPad3 -> 3; Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5
                        Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7; Key.Eight, Key.NumPad8 -> 8
                        Key.Nine, Key.NumPad9 -> 9; else -> -1
                    }
                    if (digit >= 0) {
                        // A page starts with 1..8.
                        if (entry.isEmpty() && digit !in 1..8) return@onPreviewKeyEvent true
                        entry += digit
                        if (entry.length == 3) teletextNumber(entry.toInt())?.let(::go) ?: run { entry = "" }
                        return@onPreviewKeyEvent true
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_PROG_RED -> { follow(0); return@onPreviewKeyEvent true }
                        AndroidKeyEvent.KEYCODE_PROG_GREEN -> { follow(1); return@onPreviewKeyEvent true }
                        AndroidKeyEvent.KEYCODE_PROG_YELLOW -> { follow(2); return@onPreviewKeyEvent true }
                        AndroidKeyEvent.KEYCODE_PROG_BLUE -> { follow(3); return@onPreviewKeyEvent true }
                    }
                    when (event.key) {
                        Key.DirectionUp, Key.ChannelUp, Key.PageUp -> { go(teletextStepPage(number, received, true)); true }
                        Key.DirectionDown, Key.ChannelDown, Key.PageDown -> { go(teletextStepPage(number, received, false)); true }
                        else -> false
                    }
                },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = 72.dp), contentAlignment = Alignment.Center) {
                // 4:3 page, as large as fits: height-bound on a TV, width-bound on a phone.
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
                if (entry.isNotEmpty()) Text(entry.padEnd(3, '-'), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 12.dp))
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                page?.links?.take(4)?.forEachIndexed { i, link ->
                    if (link != 0) TeletextLinkButton(LINK_COLORS[i], teletextDecimal(link).toString()) { go(link) }
                }
                TvActionButton("", { stepSubpage(false) }, enabled = subcodes.size > 1, icon = Icons.Filled.ChevronLeft)
                if (subcodes.size > 1) {
                    val index = subcodes.indexOf(subcode ?: page?.subcode).coerceAtLeast(0) + 1
                    Text("$index/${subcodes.size}", color = Color.White, fontSize = 18.sp)
                }
                TvActionButton("", { stepSubpage(true) }, enabled = subcodes.size > 1, icon = Icons.Filled.ChevronRight)
                TvActionButton(stringResource(R.string.player_teletext_pages), { showList = true }, enabled = received.isNotEmpty())
                // Always enabled, so the dialog has focus and receives digits and ▲▼ from the start.
                TvActionButton(stringResource(R.string.player_teletext_index), { go(0x100) }, Modifier.focusRequester(firstButton))
                TvActionButton(stringResource(R.string.player_teletext_mix), { mix = !mix }, selected = mix)
                TvActionButton(stringResource(R.string.back), onClose)
            }
        }
        if (showList) TeletextPageList(received, onPick = { go(it); showList = false }, onDismiss = { showList = false })
    }
}

@Composable
private fun TeletextLinkButton(color: Color, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(containerColor = color.copy(alpha = 0.8f), focusedContainerColor = color),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
    ) {
        Text(label, color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable
private fun TeletextPageList(received: List<Int>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AppDialog(onDismiss = onDismiss, title = stringResource(R.string.player_teletext_pages), scrollable = false) {
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(received) { number ->
                // First line with text beneath the header, as a hint what the page holds.
                val snippet = remember(number) {
                    LiveTeletext.page(number)?.let { page ->
                        layoutTeletextPage(page).drop(1).map { it.text().trim() }.firstOrNull { it.length >= 3 }
                    }.orEmpty()
                }
                TvActionButton("${teletextDecimal(number)}  $snippet".trimEnd(), { onPick(number) }, fillWidth = true)
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
