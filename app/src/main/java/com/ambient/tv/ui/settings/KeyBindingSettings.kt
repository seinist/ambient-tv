package com.ambient.tv.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.common.SettingsActionRow
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.common.SettingsNavigationRow
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.keyboard.KEY_BINDING_LONG_PRESS_MS
import com.ambient.tv.ui.keyboard.KeyAction
import com.ambient.tv.ui.keyboard.KeyBinding
import com.ambient.tv.ui.keyboard.KeyBindingScope
import com.ambient.tv.ui.keyboard.KeyBindings
import com.ambient.tv.ui.keyboard.isBindableKey
import com.ambient.tv.ui.keyboard.keyBindingLabel

/** Settings → App & data → Keys & remote: one page for app-wide and player keys. */
internal fun LazyListScope.keyBindingItems() {
    item(key = "key-bindings") { KeyBindingSettings() }
}

@Composable
internal fun KeyBindingSettings(scopeFilter: KeyBindingScope? = null) {
    val context = LocalContext.current
    var listening by remember { mutableStateOf<KeyAction?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val bindings = KeyBindings.bindings

    fun finish(action: KeyAction, binding: KeyBinding?) {
        listening = null
        if (binding == null) {
            KeyBindings.clear(action)
            message = null
            return
        }
        val replaced = KeyBindings.bind(action, binding)
        message = replaced?.let {
            context.getString(R.string.key_binding_replaced, keyBindingLabel(context, binding),
                context.getString(it.titleRes), context.getString(action.titleRes))
        }
    }

    // While a row listens, every key reaches it first (see KeyBindings.capture).
    val active = listening
    if (active != null) DisposableEffect(active) {
        var downCode = KeyEvent.KEYCODE_UNKNOWN
        var decided = false
        KeyBindings.capture = capture@{ event ->
            val code = event.keyCode
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (event.action == KeyEvent.ACTION_UP) listening = null
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // Only a press that started while listening clears (not the one that opened it).
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) downCode = code
                    if (event.action == KeyEvent.ACTION_UP && downCode == code) finish(active, null)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    listening = null
                    false
                }
                else -> {
                    if (!isBindableKey(code)) return@capture false
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (event.repeatCount == 0) { downCode = code; decided = false }
                            else if (downCode == code && !decided &&
                                event.eventTime - event.downTime >= KEY_BINDING_LONG_PRESS_MS) {
                                decided = true
                                finish(active, KeyBinding(code, long = true))
                            }
                        }
                        KeyEvent.ACTION_UP -> if (downCode == code && !decided) {
                            decided = true
                            finish(active, KeyBinding(code, long = event.eventTime - event.downTime >= KEY_BINDING_LONG_PRESS_MS))
                        }
                    }
                    true
                }
            }
        }
        onDispose { KeyBindings.capture = null }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.compactGap)) {
        SettingsHint(stringResource(R.string.key_bindings_explanation))
        message?.let { SettingsHint(it) }
        KeyBindingScope.entries.filter { scopeFilter == null || it == scopeFilter }.forEach { scope ->
            SettingsSubheader(stringResource(
                if (scope == KeyBindingScope.GLOBAL) R.string.key_bindings_scope_global else R.string.key_bindings_scope_player,
            ))
            KeyAction.entries.filter { it.scope == scope }.forEach { action ->
                val binding = bindings[action]
                SettingsNavigationRow(
                    title = stringResource(action.titleRes),
                    summary = when {
                        listening == action -> stringResource(R.string.key_binding_listening)
                        binding != null -> keyBindingLabel(context, binding)
                        else -> stringResource(R.string.key_binding_none)
                    },
                    icon = if (listening == action) Icons.Filled.Keyboard else null,
                    modifier = Modifier.onFocusChanged { if (!it.isFocused && listening == action) listening = null },
                    onClick = {
                        message = null
                        listening = if (listening == action) null else action
                    },
                )
            }
        }
        if (bindings.isNotEmpty()) SettingsActionRow {
            TvActionButton(stringResource(R.string.key_bindings_reset), onClick = {
                listening = null
                message = null
                KeyBindings.clearAll()
            })
        }
    }
}
