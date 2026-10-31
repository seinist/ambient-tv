package com.ambient.tv.ui.files

import com.ambient.tv.ui.common.leftKeyAction
import com.ambient.tv.ui.theme.screenGutter
import androidx.compose.foundation.clickable
import com.ambient.tv.ui.mobile.LocalHandheldUi

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.OperationFeedbackContent
import com.ambient.tv.ui.common.OperationFeedbackScope
import com.ambient.tv.ui.common.FeedbackLayout
import com.ambient.tv.data.files.*
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.FullscreenSpinner
import com.ambient.tv.ui.common.MarqueeTitle
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.pageChips
import com.ambient.tv.ui.common.pageContentUp
import androidx.compose.foundation.focusGroup
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.overlayLazyScrollbar
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.noSurfaceBorder
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.accessibleAccent
import com.ambient.tv.ui.theme.appControlSurface
import kotlinx.coroutines.delay

/** List key and focus id of the ".." row; entry ids start with "files:", sources with "source:". */
private const val UP_ID = "up"

@Composable
fun FilesScreen(onPlay: (String, Boolean) -> Unit, onBack: () -> Unit, contentActive: Boolean, onOpenPhotos: () -> Unit = {}, viewModel: FilesViewModel = hiltViewModel()) {
    val handheld = LocalHandheldUi.current
    LaunchedEffect(viewModel) { viewModel.applyViewerReturn() }
    val state by viewModel.state.collectAsState()
    val sourceId = state.folders.lastOrNull()?.sourceId
    val sources = if (state.sources.firstOrNull { it.id == sourceId }?.kind == "local") emptySet()
        else setOf(sourceId?.let { "files:$it" } ?: "files")
    com.ambient.tv.ui.common.RefreshOnEnterAndReconnect(sources, viewModel::refreshOnReconnect)
    var editor by remember { mutableStateOf<FileSource?>(null) }
    var deleteSource by remember { mutableStateOf<FileSource?>(null) }
    var replaceLocalId by rememberSaveable { mutableStateOf<String?>(null) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val top = remember { FocusRequester() }
    DisposableEffect(state.entries, state.sources) {
        val validIds = state.entries.map { it.id }.toSet() + state.sources.map { "source:${it.id}" }
        requesters.keys.retainAll(validIds)
        onDispose { }
    }
    val locationKey = state.folders.lastOrNull()?.let { "${it.sourceId}:${it.path}" } ?: viewModel.tab
    var keepHeaderFocus by remember(locationKey, contentActive) { mutableStateOf(false) }
    val initialScroll = remember(locationKey) { viewModel.scroll(locationKey) }
    val list = remember(locationKey) { LazyListState(initialScroll.first, initialScroll.second) }
    DisposableEffect(list) { onDispose { viewModel.saveScroll(locationKey, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addLocal(uri, replaceLocalId)
        replaceLocalId = null
    }
    fun pickFolder(id: String? = null) {
        replaceLocalId = id
        try { picker.launch(null) } catch (_: ActivityNotFoundException) { viewModel.pickerUnavailable() }
    }
    val dialogOpen = editor != null || deleteSource != null
    OperationFeedbackScope(viewModel.feedback, "browse", !dialogOpen)
    editor?.let { OperationFeedbackScope(viewModel.feedback, "editor:${it.id}") }
    BackHandler(contentActive && !dialogOpen) {
        if (state.loading) viewModel.cancelLoading() else if (!viewModel.up()) onBack()
    }
    val sourceList = state.folders.isEmpty() && viewModel.tab == "browse"
    val ids = if (sourceList) state.sources.map { "source:${it.id}" } else state.entries.map { it.id }
    val emptyPending = com.ambient.tv.ui.common.awaitingEmptyBrowseResult(ids.isEmpty(), state.loading, state.refreshing)
    ReportSidebarContentReady(!emptyPending && !state.loading)
    // Same condition as FilesViewModel.up(): a pinned share's root has no parent inside this page.
    val canUp = state.folders.isNotEmpty() && !(viewModel.pinnedSource && state.folders.size == 1)
    val upFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(state.revision, locationKey, contentActive, dialogOpen, ids, state.loading, state.error.takeIf { ids.isEmpty() }, keepHeaderFocus) {
        if (handheld || !contentActive || dialogOpen || state.loading || keepHeaderFocus) return@LaunchedEffect
        val id = state.focusId?.takeIf { it in ids } ?: ids.firstOrNull()
        // The ".." row leads the list: it shifts the entry indices and is the focus of an empty folder.
        val onUp = canUp && (id == null || state.focusId == UP_ID)
        val index = if (onUp) 0 else ids.indexOf(id).let { if (it >= 0 && canUp) it + 1 else it }
        if (index >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
        delay(60)
        val requester = if (state.error != null && ids.isEmpty()) retryFocus else if (onUp) upFocus else id?.let { requesters[it] } ?: top
        if (!runCatching { requester.requestFocus() }.getOrDefault(false)) runCatching { top.requestFocus() }
    }
    LaunchedEffect(state.editorSaved) { if (state.editorSaved > 0) editor = null }
    val focusChain = com.ambient.tv.ui.common.rememberPageFocusChain()
    Column(Modifier.fillMaxSize().padding(start = screenGutter(), end = screenGutter(), bottom = screenGutter(), top = com.ambient.tv.ui.common.pageHeaderTop(screenGutter())), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader(focusChain = focusChain, actions = {
            // Handhelds have system Back; the chip only shows while there is a level to go up (or a load to cancel).
            if (!handheld || canUp || state.loading) TvChip(stringResource(R.string.files_back), modifier = Modifier.focusRequester(top).leftKeyAction { if (state.loading) viewModel.cancelLoading() else if (!viewModel.up()) onBack() }, icon = Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true) {
                if (state.loading) viewModel.cancelLoading() else if (!viewModel.up()) onBack()
            }
            TvChip(stringResource(R.string.action_folder), icon = Icons.Default.CreateNewFolder, iconOnly = true) { pickFolder() }
            TvChip(stringResource(R.string.action_nas), icon = Icons.Default.Storage, iconOnly = true) { viewModel.resetEditor(); editor = FileSource(name = "", kind = "smb") }
            TvChip(stringResource(R.string.action_reload), icon = Icons.Default.Refresh,
                loading = state.loading || state.refreshing, iconOnly = true,
                onClick = { keepHeaderFocus = true; viewModel.reload() })
            // Folder slideshow (optionally with subfolders, see photo settings).
            if (state.entries.any { it.image }) {
                TvChip(stringResource(R.string.photos_slideshow), icon = Icons.Default.Slideshow, iconOnly = true) { if (viewModel.preparePhotos(null)) onOpenPhotos() }
            }
        }) { com.ambient.tv.ui.common.SystemPageTitle(com.ambient.tv.ui.common.MediaSystem.FILES) }
        com.ambient.tv.ui.common.LocalNetworkPermissionSetting(onGranted = viewModel::refresh)
        val rootLabel = stringResource(when (viewModel.tab) {
            "favorites" -> R.string.files_favorites
            "recent" -> R.string.files_recent
            "continue" -> R.string.files_continue
            else -> R.string.files_sources
        })
        FilesBreadcrumb(
            root = if (viewModel.pinnedSource) null else rootLabel,
            folders = state.folders,
            focusChain = focusChain,
            onSelect = viewModel::breadcrumb,
        )
        if (ids.isNotEmpty()) state.error?.let { message ->
            OperationFeedbackContent(message, Modifier.fillMaxWidth())
        }
        // Up from the list: breadcrumb (parent folder), then the header actions.
        Box(Modifier.fillMaxWidth().weight(1f).pageContentUp(focusChain)
            .onFocusChanged { if (it.hasFocus) keepHeaderFocus = false }.focusGroup()) {
            if (state.error != null && ids.isEmpty()) {
                OperationFeedbackContent(state.error!!, Modifier.align(Alignment.Center).widthIn(max = 680.dp), FeedbackLayout.PAGE,
                    actionLabel = stringResource(if (state.folders.isEmpty()) R.string.files_back else R.string.home_retry),
                    onAction = if (state.folders.isEmpty()) onBack else viewModel::refresh,
                    actionModifier = Modifier.focusRequester(retryFocus), requestActionFocus = !keepHeaderFocus)
            } else if (emptyPending) {
                FullscreenSpinner(Modifier.align(Alignment.Center))
            } else {
                val context = LocalContext.current
                LazyColumn(allowUpExit = true, state = list, modifier = Modifier.fillMaxSize().overlayLazyScrollbar(list), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (canUp) item(UP_ID) {
                        FilesBrowserRow(
                            title = "..",
                            // Names the target: the parent folder, or the list this source was opened from.
                            meta = state.folders.getOrNull(state.folders.size - 2)?.name ?: rootLabel,
                            icon = Icons.Default.ArrowUpward,
                            onOpen = { if (!viewModel.up()) onBack() },
                            focusModifier = Modifier.focusRequester(upFocus).onFocusChanged { if (it.isFocused) viewModel.focus(UP_ID) },
                            // Same width as the favorite action, so all rows end on one edge.
                            trailing = { if (!handheld) Spacer(Modifier.width(48.dp)) },
                        )
                    }
                    if (ids.isEmpty()) item("empty") {
                        Text(
                            stringResource(if (sourceList) R.string.files_no_sources else R.string.files_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = if (canUp) 8.dp else 0.dp),
                        )
                    }
                    if (sourceList) items(state.sources, key = { "source:${it.id}" }) { source ->
                        val id = "source:${source.id}"
                        val kind = if (source.kind == "local") stringResource(R.string.files_local) else source.kind.uppercase()
                        FilesBrowserRow(
                            title = source.name,
                            meta = kind,
                            icon = if (source.kind == "local") Icons.Default.Folder else Icons.Default.Storage,
                            onOpen = { viewModel.openSource(source) },
                            focusModifier = Modifier.focusRequester(remember(id, requesters) { requesters.getOrPut(id) { FocusRequester() } })
                                .onFocusChanged { if (it.isFocused) viewModel.focus(id) },
                            trailing = {
                                TvChip(stringResource(R.string.files_edit), icon = Icons.Default.Edit, iconOnly = handheld) {
                                    if (source.kind == "local") pickFolder(source.id) else { viewModel.resetEditor(); editor = source }
                                }
                                if (source.kind != "local" && source.wolMac.isNotBlank()) {
                                    TvChip(stringResource(R.string.files_wake), icon = Icons.Default.PowerSettingsNew) { viewModel.wake(source) }
                                }
                                TvChip(stringResource(R.string.files_remove_source), icon = Icons.Default.Delete, iconOnly = handheld) { deleteSource = source }
                            },
                        )
                    } else {
                    // Album folders: cover and album/artist above the tracks.
                    // LazyListScope is not composable: plain value, recomputed with the list content.
                    val album = state.tags.values.mapNotNull { it.album }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    if (state.folderCover != null || album != null) item("album-header") {
                        val artist = remember(state.tags) {
                            state.tags.values.mapNotNull { it.albumArtist ?: it.artist }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)) {
                            state.folderCover?.let { cover ->
                                coil3.compose.AsyncImage(model = cover, contentDescription = null, modifier = Modifier.size(96.dp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            }
                            Column {
                                album?.let { Text(it, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                            }
                        }
                    }
                    items(state.entries, key = { it.id }) { entry ->
                        val history = state.history[entry.id]
                        val favorite = entry.id in state.favorites
                        val tags = state.tags[entry.id]
                        val resume = if (history != null && history.positionMs > 0 && !history.completed) stringResource(R.string.files_resume_at, history.positionMs / 60_000) else ""
                        FilesBrowserRow(
                            title = tags?.title?.let { title -> tags.track?.let { "%02d · %s".format(it, title) } ?: title } ?: entry.name,
                            detail = resume.ifEmpty {
                                // The album already stands in the header; the artist only where it differs per track.
                                listOfNotNull(tags?.artist, tags?.album?.takeIf { it != album }).joinToString(" · ")
                            },
                            meta = if (!entry.directory && entry.size > 0) android.text.format.Formatter.formatShortFileSize(context, entry.size) else "",
                            icon = when {
                                entry.directory -> Icons.Default.Folder
                                entry.image -> Icons.Default.Image
                                entry.audio -> Icons.Default.AudioFile
                                else -> Icons.Default.VideoFile
                            },
                            onOpen = { viewModel.open(entry, onPlay, openPhotos = onOpenPhotos) },
                            focusModifier = Modifier.focusRequester(remember(entry.id, requesters) { requesters.getOrPut(entry.id) { FocusRequester() } })
                                .onFocusChanged { if (it.isFocused) viewModel.focus(entry.id) },
                            trailing = {
                                FilesRowAction(
                                    icon = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    label = stringResource(if (favorite) R.string.files_unfavorite else R.string.files_favorite),
                                    active = favorite,
                                    onClick = { viewModel.favorite(entry) },
                                )
                            },
                        )
                    }
                    }
                    if (state.more) item("more") { TvActionButton(stringResource(R.string.files_more), viewModel::more, loading = state.loading || state.refreshing) }
                }
            }
        }
    }
    // SSH keys load like WireGuard configs: storage/USB, Files sources, or the system picker where it exists.
    var keyDocumentCallback by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    val keyDocumentPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val callback = keyDocumentCallback; keyDocumentCallback = null
        if (uri != null && callback != null) viewModel.loadSshKeyUri(uri, callback)
    }
    val filesContext = androidx.compose.ui.platform.LocalContext.current
    val hasDocumentPicker = remember {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
        // Android TV registers a stub that only shows "no app can do this"; it is not a picker.
        filesContext.packageManager.queryIntentActivities(intent, 0)
            .any { it.activityInfo.packageName != "com.android.tv.frameworkpackagestubs" }
    }
    editor?.let { source ->
        val sshKeys = remember(source.id, state.revision) {
            SshKeySupport(
                stored = viewModel.hasSshKey(source),
                browse = { onKey -> viewModel.sshKeyImportSource(source.id, onKey) },
                documentPicker = if (hasDocumentPicker) { onKey ->
                    keyDocumentCallback = onKey
                    // Key files have no registered MIME type, so the picker cannot filter reliably.
                    runCatching { keyDocumentPicker.launch(arrayOf("*/*")) }.onFailure { keyDocumentCallback = null }
                } else null,
            )
        }
        NetworkSourceDialog(source, state.saving, state.testing, state.editorFeedback, viewModel.wireGuardConfigured(),
            onDismiss = { viewModel.resetEditor(); editor = null }, onTest = viewModel::testNetwork, onSave = viewModel::saveNetwork,
            keyChallenge = state.keyChallenge, sshKeys = sshKeys)
    }
    deleteSource?.let { source ->
        // Cancel is first in the footer so a stray OK never removes the source.
        AppDialog(
            onDismiss = { deleteSource = null },
            title = source.name,
            hint = stringResource(R.string.files_remove_hint),
            footer = {
                TvActionButton(stringResource(R.string.files_cancel), { deleteSource = null })
                TvActionButton(stringResource(R.string.files_remove_source), { deleteSource = null; viewModel.remove(source) })
            },
        )
    }
}

/** Path as one quiet text line: ancestors are jump targets, the last segment is the current folder. */
@Composable
private fun FilesBreadcrumb(root: String?, folders: List<FolderLocation>, focusChain: com.ambient.tv.ui.common.PageFocusChain, onSelect: (Int) -> Unit) {
    val crumbs = listOfNotNull(root?.let { -1 to it }) + folders.mapIndexed { index, folder -> index to folder.name }
    val row = rememberLazyListState()
    // Deep paths keep the current folder in view; ancestors scroll back in with D-pad left.
    LaunchedEffect(crumbs.size) { if (crumbs.isNotEmpty()) row.scrollToItem(crumbs.lastIndex) }
    // The current folder is plain text; the parent crumb is where Up from the list lands.
    val chips = if (crumbs.size > 1) Modifier.pageChips(focusChain) else Modifier
    LazyRow(state = row, modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).then(chips), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(crumbs) { position, (index, name) ->
            Row(if (position == crumbs.lastIndex - 1) Modifier.focusRequester(focusChain.selectedChip) else Modifier, verticalAlignment = Alignment.CenterVertically) {
                if (position > 0) androidx.compose.material3.Icon(
                    Icons.Default.ChevronRight, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f),
                )
                FilesCrumb(name, current = position == crumbs.lastIndex) { onSelect(index) }
            }
        }
    }
}

@Composable
private fun FilesCrumb(name: String, current: Boolean, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    val style = MaterialTheme.typography.labelLarge
    if (current) {
        Text(name, Modifier.padding(2.dp).padding(padding), color = MaterialTheme.colorScheme.onSurface, style = style,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
        return
    }
    val shape = RoundedCornerShape(LocalUiTokens.current.controlRadius)
    if (LocalHandheldUi.current) {
        Text(name, Modifier.padding(2.dp).clip(shape).clickable(onClick = onClick).padding(padding),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = style, maxLines = 1)
        return
    }
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        border = noSurfaceBorder(),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        modifier = Modifier.padding(2.dp).activateOnTvKey(onClick = onClick).onFocusChanged { focused = it.isFocused },
    ) {
        // Chrome only while focused, so the path reads as text instead of a row of buttons.
        Text(name, (if (focused) Modifier.appControlSurface(true, shape = shape) else Modifier).padding(padding),
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, style = style, maxLines = 1)
    }
}

/** Icon-only action at the end of a row; [active] tints it with the accent (e.g. a set favorite). */
@Composable
private fun FilesRowAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val accent = accessibleAccent(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surface)
    if (LocalHandheldUi.current) {
        androidx.compose.material3.IconButton(onClick) {
            androidx.compose.material3.Icon(icon, label, tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LocalUiTokens.current.controlRadius)
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        border = noSurfaceBorder(),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        modifier = Modifier.padding(2.dp).activateOnTvKey(onClick = onClick).onFocusChanged { focused = it.isFocused },
    ) {
        Box(Modifier.size(44.dp).appControlSurface(focused, shape = shape).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(20.dp), tint = when {
                active -> accent
                focused -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
            })
        }
    }
}

/**
 * One list line: icon and name left, [detail] (resume position) and [meta] (size / source kind)
 * right-aligned inside the row, [trailing] actions after it.
 */
@Composable
private fun FilesBrowserRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onOpen: () -> Unit,
    focusModifier: Modifier,
    detail: String = "",
    meta: String = "",
    trailing: @Composable RowScope.() -> Unit,
) {
    val handheld = LocalHandheldUi.current
    if (handheld) {
        androidx.compose.material3.ListItem(
            headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = detail.takeIf { it.isNotEmpty() }?.let { text -> { Text(text) } },
            leadingContent = { androidx.compose.material3.Icon(icon, null) },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    trailing()
                }
            },
            modifier = Modifier.clickable(onClick = onOpen),
        )
    } else {
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(LocalUiTokens.current.controlRadius)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onOpen,
                colors = transparentSurfaceColors(),
                scale = noSurfaceScale(),
                border = noSurfaceBorder(),
                shape = ClickableSurfaceDefaults.shape(shape = shape),
                modifier = Modifier.weight(1f).padding(2.dp).then(focusModifier)
                    .activateOnTvKey(onClick = onOpen).onFocusChanged { focused = it.isFocused },
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).appControlSurface(focused, shape = shape).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    MarqueeTitle(title, focused = focused, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge)
                    if (detail.isNotEmpty()) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    // Fixed minimum so sizes form one right-aligned column down the list.
                    if (meta.isNotEmpty()) Text(meta, Modifier.widthIn(min = 64.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, maxLines = 1)
                }
            }
            trailing()
        }
    }
}
