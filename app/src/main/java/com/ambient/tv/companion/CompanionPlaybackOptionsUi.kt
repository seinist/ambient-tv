package com.ambient.tv.companion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.TvActionButton

@Composable
internal fun CompanionPlaybackOptions(manager: CompanionManager, frame: CompanionFrame) {
    com.ambient.tv.ui.mobile.DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if ("previous" in frame.capabilities) TvActionButton(stringResource(R.string.companion_previous), { manager.playbackCommand("previous", frame) })
        if ("next" in frame.capabilities) TvActionButton(stringResource(R.string.companion_next), { manager.playbackCommand("next", frame) })
    }
    if ("volume" in frame.capabilities && frame.volume != null && frame.volumeMax > 0) {
        Text(stringResource(R.string.companion_tv_volume, frame.volume, frame.volumeMax))
        var audibleVolume by remember(frame.receiverId) { mutableIntStateOf(frame.volume.takeIf { it > 0 } ?: (frame.volumeMax / 2).coerceAtLeast(1)) }
        LaunchedEffect(frame.volume) { if (frame.volume > 0) audibleVolume = frame.volume }
        TvActionButton(stringResource(if (frame.volume == 0) R.string.companion_unmute else R.string.companion_mute), {
            manager.playbackCommand("volume", frame, if (frame.volume == 0) audibleVolume.coerceAtMost(frame.volumeMax).toLong() else 0L)
        })
        var value by remember(frame.receiverId, frame.media, frame.playbackSessionId) { mutableFloatStateOf(frame.volume.toFloat()) }
        var dragging by remember(frame.receiverId, frame.media, frame.playbackSessionId) { mutableStateOf(false) }
        LaunchedEffect(frame.volume, frame.volumeMax) { if (!dragging) value = frame.volume.toFloat().coerceIn(0f, frame.volumeMax.toFloat()) }
        Slider(value, { dragging = true; value = it }, valueRange = 0f..frame.volumeMax.toFloat(),
            onValueChangeFinished = { manager.playbackCommand("volume", frame, value.toLong()); dragging = false })
    }
    frame.options.takeIf { "select" in frame.capabilities }.orEmpty().forEach { group ->
        val label = stringResource(when (group.kind) { "audio" -> R.string.player_audio; "subtitle" -> R.string.player_subtitles; else -> R.string.player_quality })
        val options = group.options.map { option -> option.key to when (option.key) {
            "off" -> stringResource(R.string.player_subtitles_off)
            "auto" -> stringResource(R.string.companion_auto)
            else -> option.label
        } }
        com.ambient.tv.ui.common.PlayerDropUpSelector(label,
            value = options.firstOrNull { it.first == group.options.firstOrNull { it.selected }?.key }?.second ?: "–",
            options = options, selectedKey = group.options.firstOrNull { it.selected }?.key.orEmpty(),
            onSelected = { manager.playbackCommand("select", frame, optionGroup = group.kind, optionKey = it) })
    }
    if (frame.shuffle != null && "shuffle" in frame.capabilities || frame.repeatMode != null && "repeat" in frame.capabilities) com.ambient.tv.ui.mobile.DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (frame.shuffle != null && "shuffle" in frame.capabilities) TvActionButton(stringResource(R.string.companion_shuffle), { manager.playbackCommand("shuffle", frame, if (frame.shuffle) 0 else 1) }, selected = frame.shuffle)
        if ("repeat" in frame.capabilities) TvActionButton(stringResource(when (frame.repeatMode) { 1 -> R.string.companion_repeat_all; 2 -> R.string.companion_repeat_one; else -> R.string.companion_repeat_off }), { manager.playbackCommand("repeat", frame, ((frame.repeatMode ?: 0) + 1L) % 3) })
    }
}

@Composable
internal fun CompanionQueueControls(manager: CompanionManager, frame: CompanionFrame) {
    if (frame.capabilities.none { it.startsWith("queue_") }) return
    Text(stringResource(R.string.companion_queue, frame.queueTotal), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.companion_queue_hint), style = MaterialTheme.typography.bodySmall)
    com.ambient.tv.ui.mobile.DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if ("queue_add" in frame.capabilities) TvActionButton(stringResource(R.string.companion_queue_add), manager::armQueueSelection)
        if (frame.queueTotal > 0 && "queue_clear" in frame.capabilities) TvActionButton(stringResource(R.string.companion_queue_clear), { manager.queueCommand("queue_clear", observed = frame) })
    }
    frame.queue.forEachIndexed { index, item ->
        key(item.token) {
            Text(item.title.ifBlank { "${item.kind.substringBefore(':')} · ${frame.queueOffset + index + 1}" })
            com.ambient.tv.ui.mobile.DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if ("queue_play" in frame.capabilities) TvActionButton(stringResource(R.string.player_play), { manager.queueCommand("queue_play", item.token, frame) })
                if ("queue_move" in frame.capabilities) {
                    if (frame.queueOffset + index > 0) TvActionButton("↑", { manager.queueCommand("queue_move", "${item.token}|-1", frame) })
                    if (frame.queueOffset + index < frame.queueTotal - 1) TvActionButton("↓", { manager.queueCommand("queue_move", "${item.token}|1", frame) })
                }
                if ("queue_remove" in frame.capabilities) TvActionButton(stringResource(R.string.companion_queue_remove), { manager.queueCommand("queue_remove", item.token, frame) })
            }
        }
    }
    com.ambient.tv.ui.mobile.DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if ("queue_page" in frame.capabilities && frame.queueOffset > 0) TvActionButton(stringResource(R.string.companion_previous), { manager.queueCommand("queue_page", (frame.queueOffset - 8).coerceAtLeast(0).toString(), frame) })
        if ("queue_page" in frame.capabilities && frame.queueOffset + frame.queue.size < frame.queueTotal) TvActionButton(stringResource(R.string.companion_next), { manager.queueCommand("queue_page", (frame.queueOffset + frame.queue.size.coerceAtLeast(1)).toString(), frame) })
    }
}
