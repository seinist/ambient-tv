package com.ambient.tv.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.data.local.*
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.player.*
import com.ambient.tv.ui.player.backend.*
import kotlinx.coroutines.launch

/** Edits saved Live TV defaults without changing library defaults or the running player. */
@Composable
internal fun LiveTvProfileDialog(
    initial: PlaybackOverviewSnapshot,
    save: (String, ResolutionVideoProfile?) -> Unit,
    onDismiss: () -> Unit,
) {
    var profiles by remember { mutableStateOf(initial.profiles) }
    var resolution by remember { mutableStateOf("width:1280") }
    var section by remember { mutableStateOf("video") }
    val profileKey = "live:$resolution"
    val saved = profiles[profileKey]
    val global = profiles["all"]
    val drafts = remember { mutableMapOf<String, MutableState<ResolutionVideoProfile>>() }
    // The value each resolution opened with: only real edits create or change a Live TV profile.
    val opened = remember { mutableMapOf<String, ResolutionVideoProfile>() }
    var draft by remember(resolution) { drafts.getOrPut(resolution) { mutableStateOf(saved ?: profiles[resolution] ?: ResolutionVideoProfile(
        rendererKey = if (global != null) initial.playbackBackend else initial.liveBackend,
        mpv = initial.mpv, vlc = initial.vlc, media3 = initial.media3, audio = initial.audio,
        mpvOutput = initial.mpvOutput, mpvDecoder = initial.mpvDecoder, vlcOutput = initial.vlcOutput,
        mpvDeinterlaceMode = global?.mpvDeinterlaceMode ?: initial.liveDeinterlaceMode,
        mpvDeinterlaceFilter = global?.mpvDeinterlaceFilter ?: initial.liveMpvDeinterlaceFilter,
        vlcDeinterlaceMode = global?.vlcDeinterlaceMode ?: initial.liveDeinterlaceMode,
        vlcDeinterlaceFilter = global?.vlcDeinterlaceFilter ?: initial.liveVlcDeinterlaceFilter,
    )) } }
    opened.getOrPut(resolution) { draft }
    // Saved on change (debounced), like every other setting; no Save button or "saved" hint.
    LaunchedEffect(resolution, draft) {
        if (draft == opened[resolution] || draft == profiles[profileKey]) return@LaunchedEffect
        kotlinx.coroutines.delay(AUTOSAVE_DEBOUNCE_MS)
        save(resolution, draft)
        profiles = profiles + (profileKey to draft)
    }
    var editorBackend by remember(resolution) { mutableStateOf(draft.rendererKey.takeIf { it != "auto" } ?: "media3") }
    val context = LocalContext.current
    val caps by VideoAccelerationRuntime.capabilities.collectAsState()
    val checking by VideoAccelerationRuntime.checking.collectAsState()
    val scope = rememberCoroutineScope()
    val route = rememberAudioRoute()
    LaunchedEffect(Unit) { VideoAccelerationRuntime.refresh(context) }
    val renderers = listOf("media3" to "Media3", "vlc" to "VLC", "mpv" to "MPV")
    AppDialog(onDismiss = onDismiss, title = stringResource(R.string.live_profiles_title),
        hint = stringResource(R.string.live_profiles_hint), fillHeight = true, maxWidth = 920.dp,
        footer = {
            TvActionButton(text = stringResource(R.string.back), onClick = onDismiss)
        },
    ) {
        ImageChoice(R.string.live_profiles_resolution, resolution,
            (VideoResolutionClass.keys + profiles.keys.filter { it != "all" }
                .map { it.removePrefix("live:") }).distinct().map { it to VideoResolutionClass.label(it) }) { resolution = it }
        ImageHint(stringResource(if (saved == null) R.string.live_profiles_inherited else R.string.live_profiles_active))
        ImageChoice(R.string.live_profiles_section, section, listOf(
            "video" to stringResource(R.string.player_video), "audio" to stringResource(R.string.player_audio),
        )) { section = it }
        ImageChoice(R.string.settings_playback_backend, draft.rendererKey,
            listOf("auto" to stringResource(R.string.player_backend_auto)) + renderers) {
            draft = draft.copy(rendererKey = it)
            if (it != "auto") editorBackend = it
        }
        ImageChoice(R.string.live_profiles_edit_renderer, editorBackend, renderers) { editorBackend = it }
        key(resolution, editorBackend, section) {
            if (section == "audio") {
                val audio = draft.audio ?: initial.audio
                AudioProfileEditor(audio, editorBackend, initial.audioPassthrough, supportsNight = editorBackend != "vlc",
                    selectProfile = { draft = draft.copy(audio = audio.copy(selectedProfile = it)) },
                    change = { draft = draft.copy(audio = audio.copy(profiles = audio.profiles +
                        (audio.effectiveProfile(route.profile) to it))) },
                    trackPreference = { draft = draft.copy(audio = audio.copy(tracks = it)) },
                    route = route, flatLive = true)
            } else {
                RendererVideoSettings(editorBackend,
                    if (editorBackend == "mpv") draft.mpvOutput ?: initial.mpvOutput else draft.vlcOutput ?: initial.vlcOutput,
                    draft.mpvDecoder ?: initial.mpvDecoder, caps, checking, VulkanPlaybackEvidence(),
                    changeOutput = { draft = if (editorBackend == "mpv") draft.copy(mpvOutput = it) else draft.copy(vlcOutput = it) },
                    changeDecoder = { draft = draft.copy(mpvDecoder = it) },
                    checkAgain = { scope.launch { VideoAccelerationRuntime.refresh(context, force = true) } })
                when (editorBackend) {
                    "mpv" -> MpvImageOptionsEditor(draft.mpv ?: initial.mpv, { draft = draft.copy(mpv = it) })
                    "vlc" -> VlcImageOptionsEditor(draft.vlc ?: initial.vlc, { draft = draft.copy(vlc = it) })
                    else -> Media3ImageOptionsEditor(draft.media3 ?: initial.media3) { draft = draft.copy(media3 = it) }
                }
                if (editorBackend != "media3") {
                    val mpv = editorBackend == "mpv"
                    val mode = (if (mpv) draft.mpvDeinterlaceMode else draft.vlcDeinterlaceMode) ?: initial.liveDeinterlaceMode
                    val filter = (if (mpv) draft.mpvDeinterlaceFilter else draft.vlcDeinterlaceFilter) ?: "auto"
                    ImageChoice(R.string.settings_playback_overview_live_deinterlace, mode, listOf(
                        "off" to stringResource(R.string.player_off), "auto" to stringResource(R.string.player_automatic),
                        "on" to stringResource(R.string.settings_playback_overview_yes),
                    )) { draft = if (mpv) draft.copy(mpvDeinterlaceMode = it) else draft.copy(vlcDeinterlaceMode = it) }
                    ImageChoice(R.string.settings_playback_overview_deinterlace_filter, filter,
                        DeinterlaceCatalog.methods(editorBackend).map { it.key to it.label }) {
                        draft = if (mpv) draft.copy(mpvDeinterlaceFilter = it) else draft.copy(vlcDeinterlaceFilter = it)
                    }
                }
            }
        }
        if (saved != null) TvActionButton(text = stringResource(R.string.live_profiles_reset), onClick = {
            save(resolution, null)
            drafts.remove(resolution)
            opened.remove(resolution)
            profiles = profiles - profileKey
        }, modifier = Modifier.fillMaxWidth(), fillWidth = true)
    }
}
