package com.ambient.tv.ui.games

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.games.GameCheat
import com.ambient.tv.games.PreparedGame
import com.ambient.tv.games.RaAchievement
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Achievement unlocked" over the running game. */
@Composable
internal fun GameAchievementBanner(achievement: RaAchievement?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<RaAchievement?>(null) }
    if (achievement != null) shown = achievement
    AnimatedVisibility(
        visible = achievement != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val item = shown ?: return@AnimatedVisibility
        Row(
            Modifier.background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = Color(0xFFFFC94D), modifier = Modifier.size(32.dp))
            Column {
                Text(stringResource(R.string.game_achievement_unlocked, item.points), color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall)
                Text(item.title, color = Color.White, style = MaterialTheme.typography.titleSmall)
                if (item.description.isNotBlank()) Text(item.description, color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}

/** A short note over the running game (waiting for the other player). */
@Composable
internal fun GameOverlayNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** The device type each player's port uses (pad, analog pad, mouse …), as the core offers them. */
@Composable
internal fun GameDeviceTypesPanel(
    controllers: Array<Array<RetroController>>,
    current: (Int) -> Int?,
    enabled: Boolean,
    onChoose: (port: Int, type: Int) -> Unit,
) {
    if (!enabled) return
    val ports = controllers.withIndex().filter { (_, types) -> types.count { it.id >= 0 } > 1 }
    if (ports.isEmpty()) return
    GamePanelHeading(stringResource(R.string.game_device_types))
    ports.take(4).forEach { (port, types) ->
        val choices = types.filter { it.id >= 0 }
        val selected = current(port)?.let { id -> choices.firstOrNull { it.id == id } } ?: choices.first()
        com.ambient.tv.ui.common.PlayerDropUpSelector(
            label = stringResource(R.string.game_controls_port_player, port + 1),
            value = selected.description ?: selected.id.toString(),
            options = choices.map { it.id.toString() to (it.description ?: it.id.toString()) },
            selectedKey = selected.id.toString(),
            dropUp = false,
            onSelected = { chosen -> chosen.toIntOrNull()?.let { onChoose(port, it) } },
        )
    }
}

@Composable
internal fun GameAchievementsPanel(achievements: GameAchievements) {
    when (val state = achievements.state) {
        GameAchievementState.Off -> GamePanelText(stringResource(R.string.game_achievements_off))
        GameAchievementState.Connecting -> GamePanelText(stringResource(R.string.game_achievements_connecting))
        GameAchievementState.Unknown -> GamePanelText(stringResource(R.string.game_achievements_unknown))
        is GameAchievementState.Failed -> GamePanelText(stringResource(R.string.game_achievements_failed, state.message))
        is GameAchievementState.Active -> {
            val list = state.game.achievements
            val points = list.filter { it.id in achievements.unlocked }.sumOf { it.points }
            GamePanelText(stringResource(
                if (state.hardcore) R.string.game_achievements_summary_hardcore else R.string.game_achievements_summary,
                achievements.unlocked.size, list.size, points, list.sumOf { it.points },
            ))
            list.sortedBy { it.id !in achievements.unlocked }.forEach { item ->
                val done = item.id in achievements.unlocked
                GamePanelChoice(
                    text = item.title,
                    subtitle = listOf(stringResource(R.string.game_achievement_points, item.points), item.description)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    selected = false,
                    icon = if (done) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                    onClick = {},
                    focusKey = "game-achievement:${item.id}",
                )
            }
        }
    }
}

@Composable
internal fun GameCheatsPanel(
    cheats: List<GameCheat>,
    loading: Boolean,
    deleting: Boolean,
    draft: Pair<String, String>?,
    blocked: Boolean,
    onToggle: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onDeleting: (Boolean) -> Unit,
    onDraft: (Pair<String, String>?) -> Unit,
    onAdd: (description: String, code: String) -> Unit,
    onDatabase: () -> Unit,
) {
    if (draft != null) {
        GamePanelText(stringResource(R.string.game_cheats_add_hint))
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvTextField(draft.first, { onDraft(it to draft.second) }, stringResource(R.string.game_cheats_description))
            TvTextField(draft.second, { onDraft(draft.first to it) }, stringResource(R.string.game_cheats_code))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!blocked && draft.second.isNotBlank()) {
                    TvActionButton(stringResource(R.string.game_cheats_save), { onAdd(draft.first, draft.second) })
                }
                TvActionButton(stringResource(R.string.game_cheats_cancel), { onDraft(null) })
            }
        }
        return
    }
    GamePanelText(stringResource(R.string.game_cheats_hint))
    if (loading) GamePanelText(stringResource(R.string.game_cheats_database_loading))
    cheats.forEachIndexed { index, cheat ->
        GamePanelChoice(
            text = cheat.description,
            subtitle = if (deleting) stringResource(R.string.game_cheats_delete_one) else cheat.code,
            selected = !deleting && cheat.enabled,
            icon = if (deleting) Icons.Filled.Delete else null,
            enabled = deleting || !blocked,
            focusKey = "game-cheat:$index:${cheat.code}",
            onClick = { if (deleting) onDelete(index) else onToggle(index) },
        )
    }
    GamePanelChoice(
        text = stringResource(R.string.game_cheats_database),
        subtitle = stringResource(if (loading) R.string.game_cheats_database_loading else R.string.game_cheats_database_hint),
        selected = false,
        icon = Icons.Filled.CloudDownload,
        enabled = !loading && !blocked,
        onClick = onDatabase,
    )
    GamePanelChoice(
        text = stringResource(R.string.game_cheats_add),
        selected = false,
        icon = Icons.Filled.Add,
        enabled = !blocked,
        onClick = { onDraft("" to "") },
    )
    if (cheats.isNotEmpty()) GamePanelChoice(
        text = stringResource(if (deleting) R.string.game_cheats_delete_done else R.string.game_cheats_delete),
        selected = deleting,
        icon = Icons.Filled.Delete,
        onClick = { onDeleting(!deleting) },
    )
}

/** Host, find or leave a two-player game with another Ambient TV in the same network. */
@Composable
internal fun GameNetplayPanel(
    netplay: GameNetplay,
    view: RetroViewPort,
    game: PreparedGame,
    coreStamp: String,
    vulkan: Boolean,
    options: Map<String, String>,
    systemDirectory: java.io.File,
) {
    val identity by produceState<NetplayIdentity?>(null, game.file, coreStamp, vulkan, options, systemDirectory) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val compatibleCore = GameNetplay.configurationStamp(
                    coreStamp, vulkan, options,
                    game.system.bios.map { java.io.File(systemDirectory, it.file) },
                )
                GameNetplay.identity(game.file, compatibleCore, game.title)
            }.getOrNull()
        }
    }
    val idle = netplay.state is NetplayState.Idle || netplay.state is NetplayState.Failed
    LaunchedEffect(identity, idle) {
        val id = identity
        if (id != null && idle) netplay.discover(id) else netplay.stopDiscovery()
    }
    DisposableEffect(netplay) { onDispose { netplay.stopDiscovery() } }
    if (netplay.synchronizing) {
        GamePanelText(stringResource(R.string.game_netplay_synchronizing))
        GamePanelChoice(stringResource(R.string.game_netplay_cancel), selected = false, onClick = netplay::stop)
    } else when (val state = netplay.state) {
        is NetplayState.Approval -> {
            GamePanelText(stringResource(R.string.game_netplay_approval, state.peer))
            GamePanelChoice(stringResource(R.string.game_netplay_accept), selected = false,
                onClick = netplay::approvePeer)
            GamePanelChoice(stringResource(R.string.game_netplay_reject), selected = false,
                onClick = netplay::rejectPeer)
        }
        is NetplayState.Hosting -> {
            GamePanelText(stringResource(R.string.game_netplay_hosting, state.name))
            GamePanelChoice(stringResource(R.string.game_netplay_cancel), selected = false, onClick = netplay::stop)
        }
        NetplayState.Joining -> {
            GamePanelText(stringResource(R.string.game_netplay_joining))
            GamePanelChoice(stringResource(R.string.game_netplay_cancel), selected = false, onClick = netplay::stop)
        }
        is NetplayState.Connected -> {
            GamePanelText(stringResource(R.string.game_netplay_connected, state.peer, state.localPlayer))
            GamePanelChoice(stringResource(R.string.game_netplay_leave), selected = false, onClick = netplay::stop)
        }
        else -> {
            (state as? NetplayState.Failed)?.let { GamePanelText(stringResource(it.reason.messageRes())) }
            GamePanelText(stringResource(R.string.game_netplay_hint))
            val id = identity
            GamePanelChoice(
                text = stringResource(R.string.game_netplay_host),
                subtitle = stringResource(R.string.game_netplay_host_hint, netplay.deviceName),
                selected = false,
                enabled = id != null,
                onClick = { id?.let { netplay.host(view, it) } },
            )
            if (netplay.peers.isEmpty()) GamePanelText(stringResource(R.string.game_netplay_searching))
            netplay.peers.forEach { peer ->
                GamePanelChoice(
                    text = peer.name,
                    subtitle = stringResource(R.string.game_netplay_join),
                    selected = false,
                    enabled = id != null,
                    focusKey = "game-netplay-peer:${peer.host}:${peer.port}",
                    onClick = { id?.let { netplay.join(view, peer, it) } },
                )
            }
        }
    }
}

private fun NetplayFailure.messageRes(): Int = when (this) {
    NetplayFailure.NETWORK -> R.string.game_netplay_failed_network
    NetplayFailure.OTHER_GAME -> R.string.game_netplay_failed_game
    NetplayFailure.OTHER_CORE -> R.string.game_netplay_failed_core
    NetplayFailure.CLOSED -> R.string.game_netplay_closed
    NetplayFailure.STATE -> R.string.game_netplay_failed_state
    NetplayFailure.REJECTED -> R.string.game_netplay_failed_rejected
}
