package com.ambient.tv.ui.telegram

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.data.telegram.telegramMediaIds
import com.ambient.tv.ui.common.BrowseStatusKind
import com.ambient.tv.ui.common.BrowseStatusPanel
import com.ambient.tv.ui.common.ContentRow
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.PageChipRow
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.SystemPageTitle
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.pageHeaderTop
import com.ambient.tv.ui.common.handheldSwipeTabs
import com.ambient.tv.ui.common.rememberPageFocusChain
import com.ambient.tv.ui.common.rememberRowColumnFocus
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.theme.screenGutter

private val sections = listOf(
    "recent" to R.string.telegram_recent,
    "channels" to R.string.telegram_channels,
    "videos" to R.string.telegram_videos,
    "youtube" to R.string.telegram_youtube,
    "audio" to R.string.telegram_audio,
    "photos" to R.string.telegram_photos,
)

@Composable
fun TelegramScreen(
    sectionKey: String,
    onSelectSection: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChannel: (Long) -> Unit,
    onOpenMedia: (Long, Int) -> Unit,
    onOpenYouTube: (String) -> Unit,
    viewModel: TelegramViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val chain = rememberPageFocusChain()
    val focus = rememberRowColumnFocus()
    val listState = rememberLazyListState()
    val canonical = sectionKey.takeIf { key -> sections.any { it.first == key } } ?: "recent"
    var lastKey by rememberSaveable(sectionKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(sectionKey) { viewModel.open(sectionKey) }
    // Menu -> content handoff waits for this category, not a stale prior category.
    // An empty/error panel is ready once the initial request has finished.
    com.ambient.tv.ui.navigation.ReportSidebarContentReady(
        state.section == sectionKey && (!state.loading || state.rows.isNotEmpty()),
    )
    Column(
        Modifier.fillMaxSize().handheldSwipeTabs(
            tabs = sections.map { it.first }, selected = canonical, onSelect = onSelectSection,
            enabled = !sectionKey.startsWith("channel:"),
        ),
    ) {
        PageHeader(Modifier.fillMaxWidth().padding(start = screenGutter(), end = screenGutter(), top = pageHeaderTop(12.dp)),
            focusChain = chain,
            actions = {
                TvChip(
                    text = stringResource(R.string.tvh_refresh),
                    selected = false,
                    icon = Icons.Filled.Refresh,
                    onClick = { viewModel.open(sectionKey, refresh = true) },
                )
            },
            title = { SystemPageTitle(MediaSystem.TELEGRAM) },
        )
        PageChipRow(chain, contentPadding = PaddingValues(horizontal = screenGutter()),
            selectedIndex = sections.indexOfFirst { it.first == canonical }.coerceAtLeast(0)) {
            items(sections, key = { it.first }) { (key, label) ->
                TvChip(
                    text = stringResource(label),
                    selected = key == canonical && !sectionKey.startsWith("channel:"),
                    modifier = if (key == canonical) Modifier.focusRequester(chain.selectedChip) else Modifier,
                    onClick = { onSelectSection(key) },
                )
            }
        }
        when {
            !state.available -> BrowseStatusPanel(
                title = stringResource(R.string.telegram_error),
                body = stringResource(R.string.settings_telegram_native_missing),
                kind = BrowseStatusKind.INFO,
                actionLabel = stringResource(R.string.nav_settings),
                onAction = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
            state.loading && state.rows.isEmpty() -> BrowseStatusPanel(
                title = stringResource(R.string.telegram_recent),
                body = stringResource(R.string.settings_telegram_notice),
                kind = BrowseStatusKind.INFO,
                modifier = Modifier.weight(1f),
            )
            state.error != null -> BrowseStatusPanel(
                title = stringResource(R.string.telegram_error),
                body = state.error,
                kind = BrowseStatusKind.ERROR,
                actionLabel = stringResource(R.string.tvh_refresh),
                onAction = { viewModel.open(sectionKey, refresh = true) },
                modifier = Modifier.weight(1f),
            )
            state.rows.isEmpty() -> BrowseStatusPanel(
                title = stringResource(R.string.telegram_no_results),
                kind = BrowseStatusKind.EMPTY,
                modifier = Modifier.weight(1f),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).pageContentUp(chain),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.rows, key = { it.id }) { row ->
                    ContentRow(
                        row = row,
                        columnFocus = focus,
                        restoreRatingKey = lastKey?.takeIf { key -> row.items.any { it.ratingKey == key } }
                            ?: row.items.firstOrNull()?.ratingKey,
                        onItemFocused = { lastKey = it.ratingKey },
                        onItemClick = { item ->
                            if (item.ratingKey.startsWith(TELEGRAM_CHANNEL_ITEM_PREFIX)) {
                                item.ratingKey.removePrefix(TELEGRAM_CHANNEL_ITEM_PREFIX)
                                    .toLongOrNull()?.let(onOpenChannel)
                            } else {
                                val media = viewModel.selected(item.ratingKey)
                                if (media?.kind == TelegramMediaKind.YOUTUBE) {
                                    media.youtubeVideoId?.let(onOpenYouTube)
                                } else if (media?.playableOnTelegram == true) {
                                    telegramMediaIds(item.ratingKey)?.let { (chat, message) ->
                                        onOpenMedia(chat, message)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
