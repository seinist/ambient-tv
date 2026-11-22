package com.ambient.tv.ui.games

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.ambient.tv.R
import com.ambient.tv.games.GameStateEntry
import com.ambient.tv.games.GameStateKind
import com.ambient.tv.games.GameStateStore
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.retryFocusFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** The same explicit Load / Rename / Delete actions work with touch and a TV remote. */
@Composable
internal fun GameStateStrip(
    open: Boolean,
    states: List<GameStateEntry>,
    coreStamp: String,
    title: (GameStateEntry) -> String,
    screenshot: (GameStateEntry) -> File?,
    onLoad: (GameStateEntry) -> Unit,
    onDelete: (GameStateEntry) -> Unit,
    onRename: (GameStateEntry, String) -> Unit,
    onClose: () -> Unit,
    enabled: Boolean = true,
) {
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val rowState = rememberLazyListState()
    val window = LocalWindowInfo.current
    var selectedId by remember { mutableStateOf<String?>(null) }
    var returnFocusId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(returnFocusId, open) {
        val target = returnFocusId?.takeIf { open } ?: return@LaunchedEffect
        if (withTimeoutOrNull(5_000) { snapshotFlow { window.isWindowFocused }.first { it } } != null) {
            val index = states.indexOfFirst { it.id == target }
            if (index >= 0 && rowState.layoutInfo.visibleItemsInfo.none { it.index == index }) rowState.scrollToItem(index)
            retryFocusFor(1_500) { runCatching { requesters[target]?.requestFocus() == true }.getOrDefault(false) }
        }
        if (returnFocusId == target) returnFocusId = null
    }
    SideEffect { requesters.keys.retainAll(states.mapTo(hashSetOf()) { it.id }) }
    LaunchedEffect(open, states.isEmpty()) {
        selectedId = null
        if (open && states.isNotEmpty()) {
            withFrameNanos { }
            runCatching { requesters[states.first().id]?.requestFocus() }
        }
    }
    val context = LocalContext.current
    AnimatedVisibility(
        visible = open && states.isNotEmpty(),
        enter = expandVertically(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(350, easing = FastOutSlowInEasing)) + fadeOut(tween(200)),
    ) {
        val formatter = remember(context) {
            android.text.format.DateFormat.getMediumDateFormat(context) to android.text.format.DateFormat.getTimeFormat(context)
        }
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.Back, Key.Escape -> { if (event.type == KeyEventType.KeyUp) onClose(); true }
                    Key.DirectionUp -> { if (event.type == KeyEventType.KeyDown) onClose(); true }
                    Key.DirectionDown -> true
                    else -> false
                }
            },
        ) {
            itemsIndexed(states, key = { _, entry -> entry.id + entry.createdAt }) { index, entry ->
                var focused by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(150), label = "stateScale")
                val shape = RoundedCornerShape(10.dp)
                val requester = requesters.getOrPut(entry.id) { FocusRequester() }
                val file by produceState<File?>(null, entry.id, entry.createdAt) {
                    value = withContext(Dispatchers.IO) { screenshot(entry) }
                }
                val image = remember(file, entry.id, entry.createdAt) {
                    ImageRequest.Builder(context).data(file)
                        .memoryCacheKey("game-state:${file?.path}:${entry.createdAt}")
                        .size(480, 360).build()
                }
                Column(
                    Modifier.width(200.dp)
                        .focusRequester(requester)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .onFocusChanged { focused = it.isFocused }
                        .clip(shape).background(Color.Black.copy(alpha = if (focused) 0.72f else 0.5f))
                        .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.12f), shape)
                        .activateOnTvKey(onClick = { if (enabled) selectedId = entry.id })
                        .clickable(enabled = enabled, role = Role.Button) { selectedId = entry.id },
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black)) {
                        if (file != null) AsyncImage(image, contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title(entry), color = Color.White, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        val date = java.util.Date(entry.createdAt)
                        Text(
                            if (entry.coreStamp != coreStamp) stringResource(R.string.game_state_other_core)
                            else formatter.first.format(date) + " " + formatter.second.format(date),
                            color = Color.White.copy(alpha = 0.72f), maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(stringResource(R.string.game_state_actions), color = Color.White.copy(alpha = .72f),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    states.firstOrNull { it.id == selectedId }?.takeIf { open && enabled }?.let { entry ->
        GameStateActionsDialog(entry, title(entry), onDismiss = { selectedId = null; returnFocusId = entry.id },
            onLoad = { selectedId = null; onLoad(entry) },
            onDelete = {
                selectedId = null
                val at = states.indexOfFirst { it.id == entry.id }
                returnFocusId = states.getOrNull(at + 1)?.id ?: states.getOrNull(at - 1)?.id
                if (returnFocusId == null) onClose()
                onDelete(entry)
            },
            onRename = { label -> selectedId = null; returnFocusId = entry.id; onRename(entry, label) })
    }
}

@Composable
internal fun GameStateActionsDialog(
    entry: GameStateEntry,
    title: String,
    onDismiss: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    var rename by remember(entry.id) { mutableStateOf(false) }
    var deleting by remember(entry.id) { mutableStateOf(false) }
    var label by remember(entry.id) { mutableStateOf(entry.label.orEmpty()) }
    when {
        rename -> AppDialog(
            onDismiss = { rename = false }, title = stringResource(R.string.game_state_rename),
            footer = {
                TvActionButton(stringResource(R.string.files_cancel), onClick = { rename = false })
                TvActionButton(stringResource(R.string.files_save), onClick = { onRename(label) })
            },
        ) {
            TvTextField(label, { label = it.filterNot(Char::isISOControl).take(GameStateStore.MAX_LABEL) },
                stringResource(R.string.game_state_name), modifier = Modifier.fillMaxWidth())
        }
        deleting -> AppDialog(
            onDismiss = { deleting = false }, title = stringResource(R.string.game_state_delete_title, title),
            footer = {
                TvActionButton(stringResource(R.string.files_cancel), onClick = { deleting = false })
                TvActionButton(stringResource(R.string.saved_profiles_delete), onClick = onDelete)
            }, content = null,
        )
        else -> AppDialog(onDismiss = onDismiss, title = title) {
            TvActionButton(stringResource(R.string.game_state_load), onClick = onLoad, modifier = Modifier.fillMaxWidth())
            if (entry.kind == GameStateKind.MANUAL) TvActionButton(stringResource(R.string.game_state_rename),
                onClick = { rename = true }, modifier = Modifier.fillMaxWidth())
            TvActionButton(stringResource(R.string.saved_profiles_delete), onClick = { deleting = true }, modifier = Modifier.fillMaxWidth())
        }
    }
}
