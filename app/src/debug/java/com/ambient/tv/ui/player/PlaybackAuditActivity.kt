package com.ambient.tv.ui.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.ambient.tv.ui.player.backend.*
import com.ambient.tv.ui.theme.AmbientTvTheme
import androidx.tv.material3.Text

/** Debug-only device fixture. Uses the production engines, surfaces, session and service. */
class PlaybackAuditActivity : ComponentActivity() {
    var backend by mutableStateOf("media3")
    var showPlayer by mutableStateOf(true)
    var renderSurface by mutableStateOf(true)
    var showOutputPanel by mutableStateOf(false)
    var showScalingPanel by mutableStateOf(false)
    private var uri = ""
    private var startPositionMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        instance = this
        // Explicit test overrides affect only this debug/audit installation.
        val preferences = videoImagePreferences(this)
        intent.getStringExtra("output")?.let {
            preferences.mpvVideoOutputModeKey = it
            preferences.vlcVideoOutputModeKey = it
        }
        intent.getStringExtra("decoder")?.let { preferences.mpvVideoDecoderKey = it }
        backend = savedInstanceState?.getString("backend") ?: intent.getStringExtra("backend") ?: "media3"
        uri = savedInstanceState?.getString("uri") ?: intent.getStringExtra("uri").orEmpty()
        startPositionMs = savedInstanceState?.getLong("startPositionMs") ?: intent.getLongExtra("startPositionMs", 30_000)
        showPlayer = savedInstanceState?.getBoolean("showPlayer") ?: true
        showScalingPanel = intent.getBooleanExtra("scalingPanel", false)
        renderSurface = savedInstanceState?.getBoolean("renderSurface") ?: !intent.getBooleanExtra("headless", false)
        setContent {
            // Optional lab warm-up for HDMI sinks that renegotiate audio when
            // this Activity becomes visible. Do not resume system-paused playback.
            val startDelayMs = intent.getLongExtra("startDelayMs", 0L).coerceAtLeast(0L)
            var playbackReady by remember { mutableStateOf(startDelayMs == 0L) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(startDelayMs)
                playbackReady = true
            }
            AmbientTvTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (showPlayer && playbackReady) CompositionLocalProvider(LocalPlaybackMetadata provides
                        PlaybackMetadata("audit/clip", "Background playback audit", backend, "audit", "clip",
                            hasVideo = !intent.getBooleanExtra("audioOnly", false),
                            handheldHdrStandard = intent.getStringExtra("hdr")?.let { value -> HdrStandard.entries.firstOrNull { it.name == value } })) {
                        when (backend) {
                            "vlc" -> rememberVlcPlaybackController(this@PlaybackAuditActivity, uri, startPositionMs, emptyMap())
                                ?.let { if (renderSurface) VlcVideoSurface(it, Modifier.fillMaxSize()) }
                            "mpv" -> rememberMpvPlaybackController(this@PlaybackAuditActivity, uri, startPositionMs, emptyMap(),
                                videoOutputMode = preferences.mpvVideoOutputModeKey)
                                ?.let { if (renderSurface) MpvVideoSurface(it, Modifier.fillMaxSize()) }
                            else -> {
                                val metadata = LocalPlaybackMetadata.current
                                val player = remember(uri) { PlaybackEngineFactory.create(
                                    this@PlaybackAuditActivity, uri, startPositionMs, metadata = metadata) }
                                if (intent.getBooleanExtra("unsupportedVideoGuard", false))
                                    Media3VideoTrackGuard(player) { backend = "vlc" }
                                if (renderSurface) AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } },
                                    update = { it.player = player }, onRelease = { it.player = null },
                                    modifier = Modifier.fillMaxSize())
                            }
                        }
                        if (showOutputPanel) com.ambient.tv.ui.common.UnifiedPlayerSidePanel(
                            title = "Video output audit", onBack = { showOutputPanel = false },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        ) { PlayerVideoOutputControls(backend) }
                        if (showScalingPanel) {
                            val navigation = remember { com.ambient.tv.ui.common.PlayerPanelNavigation().apply {
                                videoState.section = "scaling"
                            } }
                            CompositionLocalProvider(com.ambient.tv.ui.common.LocalPlayerPanelNavigation provides navigation) {
                                com.ambient.tv.ui.common.UnifiedPlayerSidePanel(
                                    title = "Scaling audit", onBack = { showScalingPanel = false },
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                ) {
                                    PlayerVideoSettingsGroups(sectionView = true, isLive = false,
                                        rendererKey = backend, onRendererSelected = { value, _ -> backend = value },
                                        showRenderer = true, onDeinterlaceChanged = null)
                                }
                            }
                        }
                    } else Text("Browsing while playback continues", Modifier.align(Alignment.Center))
                    if (!showOutputPanel && !showScalingPanel) BackgroundPlaybackBar(
                        onOpen = { showPlayer = true },
                        onPlayNext = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("backend", backend)
        outState.putString("uri", uri)
        outState.putLong("startPositionMs", startPositionMs)
        outState.putBoolean("showPlayer", showPlayer)
        outState.putBoolean("renderSurface", renderSurface)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    companion object { @Volatile var instance: PlaybackAuditActivity? = null }
}
