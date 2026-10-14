package com.ambient.tv.ui.server

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.deviceDp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Surface
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.domain.model.ServerProfile
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.transparentSurfaceColors
import kotlinx.coroutines.delay

@Composable
fun ServerSelectScreen(
    onContinue: () -> Unit,
    onSettings: () -> Unit,
    viewModel: ServerSelectViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(state.servers, state.activeServerId) { delay(70); runCatching { initialFocus.requestFocus() } }
    val initialServerId = state.servers.firstOrNull { it.id == state.activeServerId }?.id ?: state.servers.firstOrNull()?.id
    Box(Modifier.fillMaxSize().then(if (com.ambient.tv.ui.common.LocalHomeBackgroundVisible.current) Modifier else Modifier.background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, Color.Black)))), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 820.dp)
                .then(if (LocalHandheldUi.current) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f), RoundedCornerShape(10.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                .padding(deviceDp(36.dp, 16.dp)),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.server_select_title), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.server_select_body), style = MaterialTheme.typography.bodyLarge)
            state.servers.forEach { server ->
                ServerChoice(
                    server = server,
                    active = server.id == state.activeServerId,
                    onClick = { viewModel.activate(server); onContinue() },
                    modifier = if (server.id == initialServerId) Modifier.focusRequester(initialFocus) else Modifier,
                )
            }
            DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(text = stringResource(R.string.server_manage), onClick = onSettings, modifier = if (state.servers.isEmpty()) Modifier.focusRequester(initialFocus) else Modifier)
                state.servers.firstOrNull { it.id == state.activeServerId }?.let {
                    TvActionButton(text = stringResource(R.string.server_use_last), onClick = onContinue, selected = true)
                }
            }
        }
    }
}

@Composable
private fun ServerChoice(server: ServerProfile, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val serverShape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = serverShape),
        modifier = modifier.fillMaxWidth().activateOnTvKey(onClick = onClick).onFocusChanged { focused = it.isFocused },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(
                    when {
                        active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        focused -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                    },
                    serverShape,
                )
                .border(
                    if (active || focused) 1.5.dp else 1.dp,
                    if (active || focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.07f),
                    serverShape,
                )
                .padding(horizontal = 18.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text((if (active) "●  " else "") + server.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("${server.host}:${server.port}" + if (server.macAddress != null) "  ·  ${stringResource(R.string.server_wol_badge)}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
