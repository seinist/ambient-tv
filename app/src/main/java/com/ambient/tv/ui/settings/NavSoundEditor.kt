package com.ambient.tv.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.ui.common.OverlayScrollbar
import androidx.compose.ui.Alignment
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.RightPanelValueSlider
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.sound.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NavSoundEditor(
    value: String,
    volume: Float,
    onValueChange: (String) -> Unit,
    soundsEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val player = LocalUiSoundPlayer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bank = remember(value) { NavSoundBank.decode(value) }
    var event by rememberSaveable { mutableStateOf(UiSound.FOCUS) }
    var message by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sequence by remember { mutableStateOf<Job?>(null) }
    var previewOnChange by rememberSaveable { mutableStateOf(true) }
    var pendingPreview by remember { mutableStateOf<Job?>(null) }
    var exportSnapshot by rememberSaveable { mutableStateOf("") }
    fun stop() {
        pendingPreview?.cancel(); pendingPreview = null
        sequence?.cancel(); sequence = null
        player?.stopPreview()
    }
    fun startPreview(kind: UiSound, next: NavSoundDesign): Boolean {
        message = null
        val accepted = player?.preview(kind, next, volume) { started ->
            if (!started) message = R.string.nav_editor_output_failed
        } == true
        if (!accepted) message = R.string.nav_editor_muted
        return accepted
    }
    fun previewChange(kind: UiSound, next: NavSoundDesign) {
        stop()
        if (previewOnChange && next.enabled) {
            pendingPreview = scope.launch {
                delay(200)
                startPreview(kind, next)
            }
        }
    }
    DisposableEffect(player) { onDispose { stop() } }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { stop() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val snapshot = exportSnapshot
            message = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(snapshot.toByteArray(Charsets.UTF_8)) }
                }.fold({ R.string.nav_editor_exported }, { R.string.nav_editor_file_error })
            }
            busy = false
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytesBounded(32768) }
                    NavSoundBank.import(bytes.toString(Charsets.UTF_8))
                }
            }
            result.onSuccess { onValueChange(it.encode()); previewChange(event, it.design(event)); message = R.string.nav_editor_imported }
                .onFailure { message = R.string.nav_editor_file_error }
            busy = false
        }
    }
    var section by rememberSaveable { mutableStateOf("tone") }
    val design = bank.design(event)
    fun update(next: NavSoundDesign) {
        val normalized = next.normalized()
        if (normalized == bank.design(event)) return
        onValueChange(bank.with(event, normalized).encode())
        message = null
        previewChange(event, normalized)
    }
    val presets = listOf(
        "warm" to R.string.nav_editor_warm, "glass" to R.string.nav_editor_glass,
        "deep" to R.string.nav_editor_deep, "minimal" to R.string.nav_editor_minimal,
        "wood" to R.string.nav_editor_wood, "crystal" to R.string.nav_editor_crystal,
        "sonar" to R.string.nav_editor_sonar, "airy" to R.string.nav_editor_airy,
        "tape" to R.string.nav_editor_tape,
    )
    val identityFocus = remember { FocusRequester() }
    val previewFocus = remember { FocusRequester() }
    val sequenceFocus = remember { FocusRequester() }
    val transferFocus = remember { FocusRequester() }
    fun focusFirst(vararg targets: FocusRequester) = targets.any { runCatching { it.requestFocus() }.getOrDefault(false) }
    val preset = presets.firstOrNull { design == NavSoundDesign.preset(it.first, event) }?.first ?: "custom"
    val transferActions: @Composable RowScope.() -> Unit = {
                TvActionButton(stringResource(R.string.nav_editor_import), {
                    stop()
                    runCatching { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                        .onFailure { message = R.string.nav_editor_file_error }
                }, modifier = Modifier.focusRequester(transferFocus), enabled = !busy)
                TvActionButton(stringResource(R.string.nav_editor_export), {
                    stop(); exportSnapshot = bank.encode()
                    runCatching { export.launch("ambient-navigation-sounds.json") }.onFailure { message = R.string.nav_editor_file_error }
                }, enabled = !busy)
    }
    // Suppress ordinary navigation feedback; only the draft preview should be audible.
    CompositionLocalProvider(LocalUiSoundPlayer provides null,
        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        androidx.tv.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val general: @Composable ColumnScope.() -> Unit = {
                SettingsSubheader(stringResource(R.string.settings_section_general_sound_general))
                TvSettingRow(
                    title = stringResource(R.string.settings_nav_sounds),
                    body = stringResource(R.string.settings_nav_sounds_hint),
                    checked = soundsEnabled, onCheckedChange = onEnabledChange,
                )
                RightPanelValueSlider(
                    title = stringResource(R.string.settings_nav_sounds_volume),
                    value = (volume * 100).toInt(), valueRange = 0..100, step = 5,
                    valueLabel = "${(volume * 100).toInt()}%",
                    onValueChange = { onVolumeChange(it / 100f) },
                )
                Text(stringResource(R.string.settings_nav_sounds_draft_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val identity: @Composable ColumnScope.() -> Unit = {
                if (handheld) general()
                SettingsSubheader(stringResource(R.string.settings_section_general_sound_event))
                SettingsSelect(label = stringResource(R.string.nav_editor_event), options = UiSound.entries.map { kind ->
                    SettingsSelectOption(stringResource(soundLabel(kind)), event == kind, { if (event != kind) { event = kind; message = null; previewChange(kind, bank.design(kind)) } })
                })
                SettingsSelect(label = stringResource(R.string.nav_editor_preset), options = (if (preset == "custom") listOf(SettingsSelectOption(stringResource(R.string.nav_editor_custom), true, {})) else emptyList()) +
                    presets.map { (name, label) -> SettingsSelectOption(stringResource(label), preset == name,
                        { update(NavSoundDesign.preset(name, event)) }) })
                TvSettingRow(stringResource(R.string.nav_editor_preview_on_change), checked = previewOnChange,
                    onCheckedChange = { previewOnChange = it; stop() })
                TvSettingRow(stringResource(R.string.nav_editor_enabled), checked = design.enabled,
                    onCheckedChange = { update(design.copy(enabled = it)) })
                SettingsActionRow {
                    TvActionButton(stringResource(R.string.nav_editor_reset), { update(NavSoundDesign.preset("warm", event)) })
                }
            }
            val playPreview = {
                stop()
                startPreview(event, design)
                Unit
            }
            val playSequence = {
                stop()
                sequence = scope.launch {
                    for (kind in listOf(UiSound.FOCUS, UiSound.SELECT, UiSound.BACK)) {
                        val sound = bank.design(kind)
                        if (!sound.enabled) continue
                        if (!startPreview(kind, sound)) break
                        delay((sound.lengthMs + sound.releaseMs + maxOf(if (sound.reverb > 0) sound.tailMs else 0,
                            if (sound.echo > 0) sound.echoMs * 8 else 0) + 300).toLong())
                    }
                }
            }
            val previewControls: @Composable ColumnScope.() -> Unit = {
                SettingsSubheader(stringResource(R.string.settings_section_general_sound_preview))
                message?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SettingsActionRow {
                    TvActionButton(stringResource(R.string.nav_editor_play), playPreview, enabled = design.enabled, selected = true)
                    TvActionButton(stringResource(R.string.nav_editor_sequence), playSequence)
                    TvActionButton(stringResource(R.string.nav_editor_stop), ::stop)
                }
            }
            val controls: @Composable ColumnScope.() -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("tone" to R.string.nav_editor_tone, "space" to R.string.nav_editor_space, "echo" to R.string.nav_editor_echo_section).forEach { (key, label) ->
                        TvActionButton(stringResource(label), selected = section == key, onClick = { section = key })
                    }
                }
                Text(stringResource(when (section) {
                    "space" -> R.string.nav_editor_space_hint
                    "echo" -> R.string.nav_editor_echo_hint
                    else -> R.string.nav_editor_tone_hint
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (section) {
                    "tone" -> {
                        SettingsSelect(listOf("sine" to R.string.nav_editor_sine, "bell" to R.string.nav_editor_bell, "pad" to R.string.nav_editor_pad).map { (voice, label) ->
                            SettingsSelectOption(stringResource(label), design.voice == voice, { update(design.copy(voice = voice)) })
                        })
                        RightPanelValueSlider(stringResource(R.string.nav_editor_pitch), design.pitchHz, 110..880, { update(design.copy(pitchHz = it)) }, step = 10, valueLabel = "${design.pitchHz} Hz")
                        RightPanelValueSlider(stringResource(R.string.nav_editor_length), design.lengthMs, 30..1500, { update(design.copy(lengthMs = it)) }, step = 10, valueLabel = "${design.lengthMs} ms")
                        RightPanelValueSlider(stringResource(R.string.nav_editor_attack), design.attackMs, 5..300, { update(design.copy(attackMs = it)) }, step = 5, valueLabel = "${design.attackMs} ms")
                        RightPanelValueSlider(stringResource(R.string.nav_editor_release), design.releaseMs, 10..1000, { update(design.copy(releaseMs = it)) }, step = 10, valueLabel = "${design.releaseMs} ms")
                        RightPanelValueSlider(stringResource(R.string.nav_editor_warmth), design.warmth, 0..100, { update(design.copy(warmth = it)) }, step = 5)
                        RightPanelValueSlider(stringResource(R.string.nav_editor_detune), design.detune, 0..30, { update(design.copy(detune = it)) }, valueLabel = "${design.detune} ct")
                        RightPanelValueSlider(stringResource(R.string.nav_editor_volume), design.volume, 0..100, { update(design.copy(volume = it)) }, step = 5, valueLabel = "${design.volume} %")
                    }
                    "space" -> {
                        RightPanelValueSlider(stringResource(R.string.nav_editor_reverb), design.reverb, 0..70, { update(design.copy(reverb = it)) }, step = 5, valueLabel = "${design.reverb} %")
                        RightPanelValueSlider(stringResource(R.string.nav_editor_room), design.room, 0..100, { update(design.copy(room = it)) }, step = 5)
                        RightPanelValueSlider(stringResource(R.string.nav_editor_tail), design.tailMs, 100..4000, { update(design.copy(tailMs = it)) }, step = 100, valueLabel = "${design.tailMs} ms")
                    }
                    else -> {
                        RightPanelValueSlider(stringResource(R.string.nav_editor_echo), design.echo, 0..60, { update(design.copy(echo = it)) }, step = 5, valueLabel = "${design.echo} %")
                        RightPanelValueSlider(stringResource(R.string.nav_editor_delay), design.echoMs, 40..600, { update(design.copy(echoMs = it)) }, step = 10, valueLabel = "${design.echoMs} ms")
                        RightPanelValueSlider(stringResource(R.string.nav_editor_feedback), design.feedback, 0..60, { update(design.copy(feedback = it)) }, step = 5, valueLabel = "${design.feedback} %")
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // The footer owns preview and file actions. A short slot cannot hold two columns
                // above that bar, so it stacks into the one scrolling panel.
                val sideBySide = !handheld && maxWidth >= 680.dp && maxHeight >= 220.dp
                val leaveEditor = { focusFirst(previewFocus, sequenceFocus, transferFocus) }
                if (sideBySide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        EditorPanel(Modifier.width(300.dp).fillMaxHeight(), focus = identityFocus,
                            down = leaveEditor) {
                            general()
                            identity()
                        }
                        EditorPanel(Modifier.weight(1f).fillMaxHeight(), content = controls,
                            down = leaveEditor, left = { focusFirst(identityFocus) })
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditorPanel(Modifier.weight(1f).fillMaxWidth(), focus = identityFocus,
                            down = leaveEditor) {
                            if (!handheld) general()
                            identity()
                            Spacer(Modifier.height(12.dp))
                            controls()
                            if (handheld) {
                                SettingsSubheader(stringResource(R.string.settings_section_general_transfer))
                                SettingsActionRow(content = transferActions)
                                NavSoundSync(bank.encode(), volume, soundsEnabled)
                            }
                        }
                        if (handheld) Column(content = previewControls)
                    }
                }
            }
            if (!handheld) {
                message?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsSubheader(stringResource(R.string.settings_section_general_sound_preview))
                        SettingsActionRow {
                            TvActionButton(stringResource(R.string.nav_editor_play), playPreview,
                                Modifier.focusRequester(previewFocus), enabled = design.enabled, selected = true)
                            TvActionButton(stringResource(R.string.nav_editor_sequence), playSequence,
                                Modifier.focusRequester(sequenceFocus))
                            TvActionButton(stringResource(R.string.nav_editor_stop), ::stop)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsSubheader(stringResource(R.string.settings_section_general_transfer))
                        SettingsActionRow(content = transferActions)
                    }
                }
                NavSoundSync(bank.encode(), volume, soundsEnabled)
            }
        }
    }
}

@Composable
private fun EditorPanel(
    modifier: Modifier,
    focus: FocusRequester? = null,
    down: () -> Boolean = { false },
    left: () -> Boolean = { false },
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val scrollbarContentFocus = remember { FocusRequester() }
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .3f), shape)
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), shape).padding(12.dp)) {
        Column(Modifier.focusRequester(scrollbarContentFocus).focusGroup().fillMaxSize().padding(end = if (scroll.maxValue > 0) 12.dp else 0.dp)
            .panelEdges(focus, down, left).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        if (scroll.maxValue > 0) OverlayScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight(), contentFocus = scrollbarContentFocus)
    }
}

/**
 * Keeps D-pad moves inside the panel while a target exists there. At the bottom edge [down] picks
 * the next control outside, at the left edge [left]; Up may leave for the page controls.
 */
private fun Modifier.panelEdges(focus: FocusRequester?, down: () -> Boolean, left: () -> Boolean): Modifier = composed {
    if (com.ambient.tv.ui.mobile.LocalHandheldUi.current) return@composed Modifier
    val focusManager = LocalFocusManager.current
    var inside by remember { mutableStateOf(false) }
    Modifier
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val direction = when (event.key) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                Key.DirectionLeft -> FocusDirection.Left
                else -> return@onKeyEvent false
            }
            inside = true
            val moved = try { focusManager.moveFocus(direction) } finally { inside = false }
            when {
                moved -> true
                direction == FocusDirection.Down -> down()
                direction == FocusDirection.Left -> left()
                else -> focusManager.moveFocus(direction)
            }
        }
        .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
        .focusProperties { onExit = { if (inside) cancelFocusChange() } }
        .focusGroup()
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun soundLabel(kind: UiSound): Int = when (kind) {
    UiSound.FOCUS -> R.string.nav_editor_focus
    UiSound.SELECT -> R.string.nav_editor_select
    UiSound.BACK -> R.string.nav_editor_back
    UiSound.TOGGLE_ON -> R.string.nav_editor_on
    UiSound.TOGGLE_OFF -> R.string.nav_editor_off
    UiSound.VALUE -> R.string.nav_editor_value
    UiSound.SUCCESS -> R.string.nav_editor_success
    UiSound.ERROR -> R.string.nav_editor_error
}
