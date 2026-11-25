package com.ambient.tv.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ambient.tv.ui.display.UiResolutionPopup as Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.*
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import kotlin.math.ceil

private val AlphabetBarEntryHeight = 22.dp
/** The visible letter pill and handle: narrower than the touch/focus column around it. */
private val AlphabetPillWidth = 22.dp

/**
 * Finger-scrolling does not move DPAD focus. On handhelds, the A–Z marker therefore follows
 * the first visible item; TVs keep the focused item as the authoritative letter.
 */
internal fun alphabetTrackedLetter(handheld: Boolean, scrollLetter: Char?, focusedLetter: Char?): Char? =
    if (handheld) scrollLetter ?: focusedLetter else focusedLetter ?: scrollLetter

/**
 * The shared right-rail focus requester must enter at the relevant letter, not always A.
 * While inside the rail remember the last focused letter. When content focus moves to a new
 * letter outside the rail, prefer that letter on the next entry.
 */
internal class AlphabetBarFocusMemory(initialSelected: Char?) {
    private var observedSelected = initialSelected
    var entryLetter by mutableStateOf(initialSelected)
        private set

    fun observeSelection(selected: Char?, railFocused: Boolean) {
        if (selected == observedSelected) return
        observedSelected = selected
        if (!railFocused && selected != null) entryLetter = selected
    }

    fun focused(letter: Char) {
        entryLetter = letter
    }

    fun target(letters: List<Char>, selected: Char?): Char? =
        entryLetter?.takeIf { it in letters }
            ?: selected?.takeIf { it in letters }
            ?: letters.firstOrNull()
}

@Composable
fun AlphabetBar(
    letters: List<Char>,
    selected: Char?,
    onSelect: (Char?) -> Unit,
    gridFocus: FocusRequester,
    modifier: Modifier = Modifier,
    onReturnToContent: (() -> Unit)? = null,
    firstEntryFocusRequester: FocusRequester? = null,
) {
    // Entries keep a natural height and the bar is centered; it only compresses when the full
    // alphabet does not fit, instead of stretching a few letters across the whole column.
    // Phone keeps a slightly wider gutter so letter taps stay reachable beside the grid; the
    // visible handle stays a narrow pill inside it.
    val handheld = LocalHandheldUi.current
    val barWidth = if (handheld) 40.dp else 30.dp
    val density = LocalDensity.current
    val readableEntry = with(density) { 18.sp.toDp() }
    val naturalEntry = if (handheld) maxOf(20.dp, readableEntry) else AlphabetBarEntryHeight
    var touchLetter by remember(letters) { mutableStateOf<Char?>(null) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val activeLetter = if (handheld) touchLetter ?: selected else selected
    val focusMemory = remember(letters) { AlphabetBarFocusMemory(selected) }
    var railFocused by remember(letters) { mutableStateOf(false) }
    SideEffect { focusMemory.observeSelection(selected, railFocused) }
    val entryFocusLetter = focusMemory.target(letters, selected)
    val reducedEffects = LocalPerformanceProfile.current.reducedEffects
    val focusAlpha by animateFloatAsState(
        targetValue = if (!handheld && railFocused) 0.10f else 0f,
        animationSpec = tween(if (reducedEffects) 100 else 160),
        label = "Alphabet rail focus",
    )
    val accent = MaterialTheme.colorScheme.primary

    BoxWithConstraints(
        modifier.width(barWidth).padding(end = 4.dp, top = 8.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val entryHeight = minOf(naturalEntry, maxHeight / letters.size.coerceAtLeast(1))
        val railHeight = entryHeight * letters.size
        val labelStep = if (handheld && entryHeight > 0.dp)
            ceil(readableEntry / entryHeight).toInt().coerceAtLeast(1) else 1
        val activeIndex = letters.indexOf(activeLetter)
        Box(
            Modifier.fillMaxWidth().height(railHeight).testTag("alphabet-rail")
                .then(if (handheld) Modifier.alphabetTouchScrub(
                    letters = letters,
                    layoutKey = railHeight,
                    onActiveLetter = { touchLetter = it },
                    onSelect = { currentOnSelect(it) },
                ) else Modifier),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(accent.copy(alpha = focusAlpha), RoundedCornerShape(9.dp))
                    .onFocusChanged { railFocused = it.hasFocus }
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            Key.DirectionLeft -> {
                                // Consume both edges: a held Left must not spill into the page menu.
                                if (event.type == KeyEventType.KeyDown &&
                                    event.nativeKeyEvent.repeatCount == 0) {
                                    if (onReturnToContent != null) onReturnToContent()
                                    else runCatching { gridFocus.requestFocus() }
                                }
                                true
                            }
                            // The rail has no neighbour on the right. Never send Right to the
                            // header action buttons or let geometric focus search escape there.
                            Key.DirectionRight -> true
                            // Keep the first/last letter in the rail, not the page header.
                            Key.DirectionUp -> railFocused && focusMemory.entryLetter == letters.firstOrNull()
                            Key.DirectionDown -> railFocused && focusMemory.entryLetter == letters.lastOrNull()
                            else -> false
                        }
                    }
                    .focusGroup(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                letters.forEachIndexed { index, letter ->
                    val entryModifier = Modifier.weight(1f).then(
                        if (letter == entryFocusLetter && firstEntryFocusRequester != null)
                            Modifier.focusRequester(firstEntryFocusRequester) else Modifier,
                    )
                    AlphabetBarEntry(
                        text = letter.toString(),
                        selected = activeLetter == letter,
                        gridFocus = gridFocus,
                        modifier = entryModifier,
                        onFocused = { focusMemory.focused(letter) },
                        isFirst = index == 0,
                        isLast = index == letters.lastIndex,
                        showLabel = !handheld || alphabetRailLabelVisible(index, letters.size, labelStep, activeIndex),
                        compact = handheld && labelStep > 1,
                    ) { onSelect(letter) }
                }
            }
            if (handheld && labelStep > 1 && activeIndex >= 0 && railHeight > 0.dp) {
                val markerHeight = minOf(maxOf(20.dp, readableEntry), railHeight)
                val markerTop = (entryHeight * (activeIndex + .5f) - markerHeight / 2)
                    .coerceIn(0.dp, railHeight - markerHeight)
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .offset { IntOffset(0, markerTop.roundToPx()) }
                        .width(maxOf(AlphabetPillWidth, readableEntry + 4.dp)).height(markerHeight)
                        .background(accent, RoundedCornerShape(6.dp))
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(activeLetter.toString(), color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.wrapContentHeight(unbounded = true))
                }
            }
            if (handheld && touchLetter != null && activeIndex >= 0) {
                val gapPx = with(density) { 16.dp.roundToPx() }
                val marginPx = with(density) { 8.dp.roundToPx() }
                val position = remember(activeIndex, letters.size, gapPx, marginPx) {
                    AlphabetPreviewPositionProvider((activeIndex + .5f) / letters.size, gapPx, marginPx)
                }
                Popup(
                    popupPositionProvider = position,
                    properties = PopupProperties(focusable = false, dismissOnBackPress = false,
                        dismissOnClickOutside = false),
                ) {
                    val size = maxOf(64.dp, with(density) { 48.sp.toDp() } + 16.dp)
                    Box(
                        Modifier.size(size).shadow(8.dp, RoundedCornerShape(16.dp))
                            .background(accent, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(touchLetter.toString(), modifier = Modifier.testTag("alphabet-preview"),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlphabetBarEntry(
    text: String,
    selected: Boolean,
    gridFocus: FocusRequester,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
    showLabel: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val entryModifier = modifier.fillMaxWidth().testTag("alphabet-letter-$text")
        .semantics {
            this.selected = selected
            if (!showLabel) contentDescription = text
        }.focusProperties {
        left = gridFocus
        // Parent-level D-pad acceleration calls moveFocus() directly, bypassing key handlers.
        // Hard focus boundaries prevent even fast repeated Right/Up/Down from escaping the rail.
        right = FocusRequester.Cancel
        if (isFirst) up = FocusRequester.Cancel
        if (isLast) down = FocusRequester.Cancel
    }
        .activateOnTvKey(onClick = onClick).onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        }
    val shape = RoundedCornerShape(5.dp)
    val accent = MaterialTheme.colorScheme.primary
    if (LocalHandheldUi.current) {
        Box(
            entryModifier.clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(AlphabetPillWidth).fillMaxHeight()
                .background(when {
                    selected && !compact -> accent
                    focused -> accent.copy(alpha = .22f)
                    else -> Color.Transparent
                }, shape))
            if (showLabel) Text(
                text = text,
                modifier = Modifier.wrapContentHeight(unbounded = true),
                fontSize = if (selected || focused) 14.sp else 12.sp,
                lineHeight = 18.sp,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = .72f),
            ) else Box(Modifier.size(2.dp).background(Color.White.copy(alpha = .42f), RoundedCornerShape(1.dp)))
        }
        return
    }
    // A filled pill like the phone's handle: no outlines as selection marks, one centred
    // shape per letter instead of a box across the whole rail.
    val fill by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            focused -> accent
            selected -> accent.copy(alpha = 0.24f)
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "Alphabet letter",
    )
    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.width(AlphabetPillWidth).fillMaxHeight().padding(vertical = 1.dp)
                    .background(fill, shape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (selected || focused) 12.sp else 11.sp,
                        fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = when {
                        focused -> MaterialTheme.colorScheme.onPrimary
                        selected -> Color.White
                        else -> Color.White.copy(alpha = 0.5f)
                    },
                )
            }
        }
    }
    Surface(
            onClick = onClick,
            colors = transparentSurfaceColors(),
            scale = noSurfaceScale(),
            border = noSurfaceBorder(),
            modifier = entryModifier,
    ) { content() }
}
