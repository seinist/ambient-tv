package com.ambient.tv.ui.keyboard

import android.content.Context
import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import com.ambient.tv.R

/** Where a binding applies. Player bindings win over global ones while a player is in front. */
enum class KeyBindingScope { GLOBAL, PLAYER }

/**
 * Actions the user can put on a remote or keyboard key. Keys are stored by [key]; never
 * rename one. Each action is defined once and performed by whatever the current screen
 * registered for it (see [KeyActionHandlers]), so a key does the same on every page.
 */
enum class KeyAction(val key: String, val scope: KeyBindingScope, @StringRes val titleRes: Int) {
    REFRESH("refresh", KeyBindingScope.GLOBAL, R.string.action_reload),
    VIEW_OPTIONS("view_options", KeyBindingScope.GLOBAL, R.string.action_view),
    SEARCH("search", KeyBindingScope.GLOBAL, R.string.action_search),
    START_PAGE("start_page", KeyBindingScope.GLOBAL, R.string.key_action_start_page),
    SIDEBAR("sidebar", KeyBindingScope.GLOBAL, R.string.key_action_sidebar),
    OPEN_PLAYER("open_player", KeyBindingScope.GLOBAL, R.string.key_action_open_player),
    LIVE_TV("live_tv", KeyBindingScope.GLOBAL, R.string.key_action_live_tv),
    WEATHER("weather", KeyBindingScope.GLOBAL, R.string.weather_title),
    SETTINGS("settings", KeyBindingScope.GLOBAL, R.string.nav_settings),
    SCREENSAVER("screensaver", KeyBindingScope.GLOBAL, R.string.key_action_screensaver),

    PLAY_PAUSE("play_pause", KeyBindingScope.PLAYER, R.string.key_action_play_pause),
    SHOW_OSD("show_osd", KeyBindingScope.PLAYER, R.string.key_action_show_osd),
    SEEK_BACK("seek_back", KeyBindingScope.PLAYER, R.string.key_action_seek_back),
    SEEK_FORWARD("seek_forward", KeyBindingScope.PLAYER, R.string.key_action_seek_forward),
    PREVIOUS_ITEM("previous_item", KeyBindingScope.PLAYER, R.string.key_action_previous_item),
    NEXT_ITEM("next_item", KeyBindingScope.PLAYER, R.string.key_action_next_item),
    CHAPTERS("chapters", KeyBindingScope.PLAYER, R.string.player_chapters),
    SUBTITLES("subtitles", KeyBindingScope.PLAYER, R.string.key_action_subtitles),
    AUDIO_TRACKS("audio_tracks", KeyBindingScope.PLAYER, R.string.key_action_audio_tracks),
    PLAYBACK_SETTINGS("playback_settings", KeyBindingScope.PLAYER, R.string.player_playback_settings),
    DETAILS("details", KeyBindingScope.PLAYER, R.string.player_details),
    ;

    companion object {
        fun fromKey(key: String): KeyAction? = entries.firstOrNull { it.key == key }
    }
}

/** A key, pressed briefly or held for [KEY_BINDING_LONG_PRESS_MS]. */
data class KeyBinding(val keyCode: Int, val long: Boolean) {
    fun encode(): String = "$keyCode:${if (long) 1 else 0}"

    companion object {
        fun decode(value: String): KeyBinding? {
            val code = value.substringBefore(':').toIntOrNull() ?: return null
            if (!isBindableKey(code)) return null
            return KeyBinding(code, value.substringAfter(':', "0") == "1")
        }
    }
}

internal const val KEY_BINDING_LONG_PRESS_MS = 600L

/**
 * Keys that keep their meaning: navigation, confirm and back (so nobody can lock themselves
 * out), and the keys Android itself owns (home, volume, power, assistant).
 */
private val RESERVED_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP_LEFT,
    KeyEvent.KEYCODE_DPAD_UP_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN_LEFT, KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
    KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP,
    KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_SOFT_SLEEP, KeyEvent.KEYCODE_ASSIST,
    KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_SETTINGS,
    KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_ALL_APPS,
    KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_ALT_LEFT,
    KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
)

private const val MAX_BINDABLE_KEY_CODE = 1_000

fun isBindableKey(keyCode: Int): Boolean =
    // No framework call here: backups decode bindings outside Android too (constants inline).
    // Controller buttons have their own page (ui.gamepad), so a remote binding never takes one.
    keyCode > KeyEvent.KEYCODE_UNKNOWN && keyCode < MAX_BINDABLE_KEY_CODE && keyCode !in RESERVED_KEYS &&
        !com.ambient.tv.ui.gamepad.isGamepadButtonKeyCode(keyCode)

/** The user's bindings: at most one key per action, and one action per key, press and scope. */
object KeyBindings {
    private val mutable = mutableStateOf<Map<KeyAction, KeyBinding>>(emptyMap())
    val bindings: Map<KeyAction, KeyBinding> get() = mutable.value
    private val deviceMutable = mutableStateOf<Map<String, Map<KeyAction, KeyBinding>>>(emptyMap())
    val deviceBindings: Map<String, Map<KeyAction, KeyBinding>> get() = deviceMutable.value
    private val disabledMutable = mutableStateOf<Map<String, Set<KeyAction>>>(emptyMap())
    val deviceDisabled: Map<String, Set<KeyAction>> get() = disabledMutable.value
    fun bindingsFor(kind: String): Map<KeyAction, KeyBinding> = deviceBindings[kind].orEmpty()
    fun effectiveBindingsFor(kind: String): Map<KeyAction, KeyBinding> =
        bindings.filterKeys { it !in deviceDisabled[kind].orEmpty() } + bindingsFor(kind)
    private var persist: (Map<String, String>) -> Unit = {}

    /**
     * While Settings records a key it receives every key first (before bindings, media keys and
     * the focused control); false lets the key take its normal way.
     */
    var capture: ((KeyEvent) -> Boolean)? = null

    /** Set by the activity: whether a fullscreen player is in front. */
    @Volatile var playerForeground = false

    /** Seek distance of [KeyAction.SEEK_BACK]/[KeyAction.SEEK_FORWARD]. */
    var seekStepMs: () -> Long = { 10_000L }

    fun attach(stored: Map<String, String>, save: (Map<String, String>) -> Unit) {
        persist = save
        mutable.value = decode(stored)
        deviceMutable.value = decodeDevice(stored)
        disabledMutable.value = decodeDisabled(stored)
    }

    fun reload(stored: Map<String, String>) {
        mutable.value = decode(stored)
        deviceMutable.value = decodeDevice(stored)
        disabledMutable.value = decodeDisabled(stored)
    }

    fun encoded(): Map<String, String> = encodeAll()

    /** Specific remote/keyboard mappings do not erase legacy global mappings. */
    fun bindFor(kind: String, action: KeyAction, binding: KeyBinding): KeyAction? {
        require(kind == "remote" || kind == "keyboard")
        val old = bindingsFor(kind)
        val replaced = old.entries.firstOrNull { (other, existing) ->
            other != action && other.scope == action.scope && existing == binding
        }?.key
        val updated = old - listOfNotNull(replaced) + (action to binding)
        deviceMutable.value = deviceBindings + (kind to updated)
        disabledMutable.value = deviceDisabled + (kind to (deviceDisabled[kind].orEmpty() - action))
        persist(encodeAll())
        return replaced
    }

    fun clearFor(kind: String, action: KeyAction) {
        val current = bindingsFor(kind) - action
        deviceMutable.value = if (current.isEmpty()) deviceBindings - kind else deviceBindings + (kind to current)
        disabledMutable.value = deviceDisabled + (kind to (deviceDisabled[kind].orEmpty() + action))
        persist(encodeAll())
    }

    fun inheritFor(kind: String, action: KeyAction) {
        deviceMutable.value = deviceBindings + (kind to (bindingsFor(kind) - action))
        disabledMutable.value = deviceDisabled + (kind to (deviceDisabled[kind].orEmpty() - action))
        persist(encodeAll())
    }

    /** Binds [action] and frees the same key/press within its scope; returns the action replaced. */
    fun bind(action: KeyAction, binding: KeyBinding): KeyAction? {
        val replaced = bindings.entries.firstOrNull { (other, existing) ->
            other != action && other.scope == action.scope && existing == binding
        }?.key
        update(bindings - listOfNotNull(replaced) + (action to binding))
        return replaced
    }

    fun clear(action: KeyAction) = update(bindings - action)

    fun clearAll() = update(emptyMap())

    private fun update(value: Map<KeyAction, KeyBinding>) {
        mutable.value = value
        persist(encodeAll())
    }

    private fun encodeAll(): Map<String, String> = buildMap {
        putAll(encode(bindings))
        deviceBindings.forEach { (kind, actions) ->
            actions.forEach { (action, binding) -> put("device.$kind.${action.key}", binding.encode()) }
        }
        deviceDisabled.forEach { (kind, actions) ->
            actions.forEach { action -> put("device.$kind.${action.key}", "none") }
        }
    }

    /** Device-specific assignments have priority; other actions retain legacy fallbacks. */
    fun actionFor(
        keyCode: Int, long: Boolean, player: Boolean = playerForeground,
        deviceId: Int = -1,
    ): KeyAction? {
        val kind = com.ambient.tv.ui.gamepad.retro.retroInputDevice(deviceId).kind.key
        val own = bindingsFor(kind)
        return resolveKeyAction(own, keyCode, long, player)
            ?: resolveKeyAction(bindings.filterKeys {
                it !in own.keys && it !in deviceDisabled[kind].orEmpty()
            }, keyCode, long, player)
    }

    internal fun decode(stored: Map<String, String>): Map<KeyAction, KeyBinding> =
        stored.mapNotNull { (action, binding) ->
            val resolved = KeyAction.fromKey(action) ?: return@mapNotNull null
            KeyBinding.decode(binding)?.let { resolved to it }
        }.toMap()

    private fun decodeDisabled(stored: Map<String, String>): Map<String, Set<KeyAction>> =
        listOf("remote", "keyboard").mapNotNull { kind ->
            val actions = stored.filter { (key, value) ->
                key.startsWith("device.$kind.") && value == "none"
            }.keys.mapNotNull { KeyAction.fromKey(it.removePrefix("device.$kind.")) }.toSet()
            kind.takeIf { actions.isNotEmpty() }?.let { it to actions }
        }.toMap()

    private fun decodeDevice(stored: Map<String, String>): Map<String, Map<KeyAction, KeyBinding>> =
        listOf("remote", "keyboard").mapNotNull { kind ->
            val actions = stored.filterKeys { it.startsWith("device.$kind.") }.mapNotNull { (key, value) ->
                val action = KeyAction.fromKey(key.removePrefix("device.$kind.")) ?: return@mapNotNull null
                KeyBinding.decode(value)?.let { action to it }
            }.toMap()
            kind.takeIf { actions.isNotEmpty() }?.let { it to actions }
        }.toMap()

    internal fun encode(value: Map<KeyAction, KeyBinding>): Map<String, String> =
        value.entries.associate { (action, binding) -> action.key to binding.encode() }
}

internal fun resolveKeyAction(
    bindings: Map<KeyAction, KeyBinding>,
    keyCode: Int,
    long: Boolean,
    player: Boolean,
): KeyAction? {
    val binding = KeyBinding(keyCode, long)
    fun inScope(scope: KeyBindingScope) = bindings.entries.firstOrNull { it.key.scope == scope && it.value == binding }?.key
    return (if (player) inScope(KeyBindingScope.PLAYER) else null) ?: inScope(KeyBindingScope.GLOBAL)
}

/**
 * Who performs an action right now. Screens register while they are composed; the most recent
 * registration that accepts wins, then the app-wide fallback. Main thread only.
 */
object KeyActionHandlers {
    private class Entry(val action: KeyAction, val handler: () -> Boolean)
    private val entries = mutableListOf<Entry>()
    private val fallbacks = mutableMapOf<KeyAction, () -> Boolean>()

    fun register(action: KeyAction, handler: () -> Boolean): Any = Entry(action, handler).also { entries += it }

    fun unregister(token: Any) { entries.remove(token) }

    fun fallback(action: KeyAction, handler: () -> Boolean) { fallbacks[action] = handler }

    /** True when something performed (or deliberately ignored) [action]. */
    fun perform(action: KeyAction): Boolean =
        entries.asReversed().filter { it.action == action }.any { it.handler() } ||
            fallbacks[action]?.invoke() == true
}

/** Lets the calling screen perform [action] while it is composed and [enabled]. */
@Composable
fun RegisterKeyAction(action: KeyAction, enabled: Boolean = true, handler: () -> Boolean) {
    val latest by rememberUpdatedState(handler)
    val latestEnabled by rememberUpdatedState(enabled)
    DisposableEffect(action) {
        val token = KeyActionHandlers.register(action) { latestEnabled && latest() }
        onDispose { KeyActionHandlers.unregister(token) }
    }
}

/**
 * Turns bound key presses into actions before the focused control sees them. A key bound only
 * for a long press still works normally when pressed briefly: its press is replayed.
 */
class KeyBindingDispatcher(
    private val playerForeground: () -> Boolean = { KeyBindings.playerForeground },
) {
    private class Pending(val keyCode: Int, val down: KeyEvent) {
        var longFired = false
    }
    private var pending: Pending? = null
    private val consumedShort = mutableSetOf<Int>()

    /** A mode transition must not finish an old long press in the new screen's bindings. */
    fun cancelPending() {
        pending?.let { consumedShort += it.keyCode }
        pending = null
    }

    /** Null when the event is not a bound key: the caller dispatches it as usual. */
    fun handle(event: KeyEvent, perform: (KeyAction) -> Unit, replay: (KeyEvent) -> Unit): Boolean? {
        KeyBindings.capture?.let { sink -> return if (sink(event)) true else null }
        if (KeyBindings.bindings.isEmpty() && KeyBindings.deviceBindings.isEmpty()) return null
        val code = event.keyCode
        if (!isBindableKey(code)) return null
        val short = KeyBindings.actionFor(code, long = false, player = playerForeground(), deviceId = event.deviceId)
        val long = KeyBindings.actionFor(code, long = true, player = playerForeground(), deviceId = event.deviceId)
        if (short == null && long == null) {
            if (event.action == KeyEvent.ACTION_UP && consumedShort.remove(code)) return true
            return null
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (long == null) {
                    if (event.repeatCount == 0) {
                        consumedShort += code
                        perform(checkNotNull(short))
                    }
                    return true
                }
                if (event.repeatCount == 0) {
                    pending = Pending(code, KeyEvent(event))
                    return true
                }
                val held = pending?.takeIf { it.keyCode == code } ?: return true
                if (!held.longFired && event.eventTime - event.downTime >= KEY_BINDING_LONG_PRESS_MS) {
                    held.longFired = true
                    perform(long)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                consumedShort.remove(code)
                val held = pending?.takeIf { it.keyCode == code }
                if (held != null) pending = null
                if (long == null || held == null || held.longFired) return true
                // Remotes that send no key repeat: decide by how long the key was held.
                when {
                    event.eventTime - event.downTime >= KEY_BINDING_LONG_PRESS_MS -> perform(long)
                    short != null -> perform(short)
                    else -> {
                        replay(held.down)
                        replay(event)
                    }
                }
                return true
            }
            else -> return true
        }
    }
}

/** A readable name for a key, e.g. "Rot", "Kanal +", "F5". */
fun keyLabel(context: Context, keyCode: Int): String = when (keyCode) {
    KeyEvent.KEYCODE_PROG_RED -> context.getString(R.string.key_name_red)
    KeyEvent.KEYCODE_PROG_GREEN -> context.getString(R.string.key_name_green)
    KeyEvent.KEYCODE_PROG_YELLOW -> context.getString(R.string.key_name_yellow)
    KeyEvent.KEYCODE_PROG_BLUE -> context.getString(R.string.key_name_blue)
    KeyEvent.KEYCODE_CHANNEL_UP -> context.getString(R.string.key_name_channel_up)
    KeyEvent.KEYCODE_CHANNEL_DOWN -> context.getString(R.string.key_name_channel_down)
    KeyEvent.KEYCODE_MENU -> context.getString(R.string.key_name_menu)
    KeyEvent.KEYCODE_INFO -> "Info"
    KeyEvent.KEYCODE_GUIDE -> "Guide"
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/Pause"
    KeyEvent.KEYCODE_MEDIA_PLAY -> "Play"
    KeyEvent.KEYCODE_MEDIA_PAUSE -> "Pause"
    KeyEvent.KEYCODE_MEDIA_STOP -> "Stop"
    KeyEvent.KEYCODE_MEDIA_NEXT -> "⏭"
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "⏮"
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "⏩"
    KeyEvent.KEYCODE_MEDIA_REWIND -> "⏪"
    KeyEvent.KEYCODE_SPACE -> context.getString(R.string.key_name_space)
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
        context.getString(R.string.key_name_digit, keyCode - KeyEvent.KEYCODE_0)
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
        context.getString(R.string.key_name_digit, keyCode - KeyEvent.KEYCODE_NUMPAD_0)
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
    else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").split('_')
        .joinToString(" ") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }
}

fun keyBindingLabel(context: Context, binding: KeyBinding): String {
    val key = keyLabel(context, binding.keyCode)
    return if (binding.long) context.getString(R.string.key_binding_long, key) else key
}
