package com.ambient.tv.ui.tvheadend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvHbbtvApp
import com.ambient.tv.livetv.liveTvHbbtvMenuApps
import com.ambient.tv.livetv.teletext.HbbtvApplication
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.PageFocusChain
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.player.teletext.HbbtvHost
import com.ambient.tv.ui.player.teletext.HbbtvUi

internal data class TvheadendHbbtvEntry(val channel: LiveTvChannel, val app: LiveTvHbbtvApp) {
    val key: String get() = channel.uuid + ":" + app.url
}

/** The list comes from stored TVHeadend AIT metadata; never tune every channel to build it. */
internal fun tvheadendHbbtvEntries(channels: List<LiveTvChannel>): List<TvheadendHbbtvEntry> =
    channels.flatMap { channel -> liveTvHbbtvMenuApps(channel).map { TvheadendHbbtvEntry(channel, it) } }
        .distinctBy { it.key }
        .sortedWith(compareBy<TvheadendHbbtvEntry> { it.channel.number ?: Int.MAX_VALUE }
            .thenBy { it.channel.name }.thenBy { it.app.name ?: it.app.url })

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvheadendHbbtvBrowse(
    channels: List<LiveTvChannel>,
    firstFocus: FocusRequester,
    selectedKey: String?,
    focusChain: PageFocusChain,
    onFocused: (String) -> Unit,
    onWatchChannel: (String) -> Unit,
) {
    val context = LocalContext.current
    val apps = remember(channels) { tvheadendHbbtvEntries(channels) }
    var launched by remember { mutableStateOf<TvheadendHbbtvEntry?>(null) }
    DisposableEffect(Unit) {
        onDispose { HbbtvUi.close() }
    }
    Column(Modifier.fillMaxSize().pageContentUp(focusChain).focusGroup().padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.tvh_hbbtv_browse_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (apps.isEmpty()) {
            com.ambient.tv.ui.common.BrowseStatusPanel(
                title = stringResource(R.string.tvh_hbbtv_browse_empty),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(apps, key = { _, entry -> entry.key }) { index, entry ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.app.name?.takeIf(String::isNotBlank) ?: entry.channel.name,
                            style = MaterialTheme.typography.titleMedium)
                        Text(entry.channel.name, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvChip(stringResource(R.string.player_hbbtv_open),
                                icon = Icons.Filled.ConnectedTv,
                                modifier = (if (entry.key == selectedKey || (selectedKey == null && index == 0)) Modifier.focusRequester(firstFocus) else Modifier)
                                    .onFocusChanged { if (it.isFocused) onFocused(entry.key) },
                                onClick = {
                                    launched = entry
                                    HbbtvUi.request(context, HbbtvApplication(entry.app.url, entry.app.name, 2))
                                })
                            TvChip(stringResource(R.string.tvh_hbbtv_watch_channel),
                                icon = Icons.Filled.Tv,
                                onClick = { HbbtvUi.close(); onWatchChannel(entry.channel.uuid) })
                        }
                    }
                }
            }
        }
    }
    launched?.let { entry ->
        HbbtvHost(channelName = entry.channel.name, channelKey = "hbbtv:" + entry.key)
    }
}
