package com.ambient.tv.ui.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.ambient.tv.ui.common.AppBackground
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.player.backend.VideoAccelerationRuntime
import com.ambient.tv.ui.player.backend.VulkanPlaybackEvidence
import com.ambient.tv.ui.settings.RendererVideoSettings
import com.ambient.tv.ui.theme.AmbientTvTheme
import kotlinx.coroutines.launch

/** Real probes and production controls; edits stay local, never touch saved settings. */
@Composable
internal fun RendererSettingsCatalog() {
    val context = LocalContext.current
    val caps by VideoAccelerationRuntime.capabilities.collectAsState()
    val checking by VideoAccelerationRuntime.checking.collectAsState()
    val evidence by VideoAccelerationRuntime.playbackEvidence.collectAsState()
    var backend by remember { mutableStateOf("auto") }
    val outputs = remember { mutableStateMapOf("mpv" to "auto", "vlc" to "auto") }
    var decoder by remember { mutableStateOf("auto") }
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
    LaunchedEffect(backend) { if (backend in setOf("mpv", "vlc")) VideoAccelerationRuntime.refresh(context) }
    AmbientTvTheme {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.fillMaxSize()) {
            AppBackground(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Renderer settings · Debug", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("auto", "media3", "vlc", "mpv").forEach { value ->
                        TvChip(value, backend == value, onClick = { backend = value },
                            modifier = if (value == "auto") Modifier.focusRequester(first) else Modifier)
                    }
                }
                key(backend) {
                    RendererVideoSettings(backend, outputs[backend] ?: "auto", decoder, caps, checking,
                        evidence[backend] ?: VulkanPlaybackEvidence(),
                        changeOutput = { outputs[backend] = it }, changeDecoder = { decoder = it },
                        checkAgain = { scope.launch { VideoAccelerationRuntime.refresh(context, force = true) } })
                }
            }
        }
        }
    }
}
