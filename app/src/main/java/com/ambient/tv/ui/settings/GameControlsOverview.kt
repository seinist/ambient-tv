package com.ambient.tv.ui.settings

import android.view.KeyEvent
import com.ambient.tv.ui.gamepad.retro.RetroInputProfile
import com.ambient.tv.ui.gamepad.retro.RetroInputTarget
import com.ambient.tv.ui.gamepad.retro.RetroPadButton

/**
 * The at-a-glance legend of the game controls editor: every key in both illustrations shows its
 * current mapping, and those that differ from the input kind's defaults are highlighted.
 */
internal data class ArtworkLegend(
    val labels: Map<String, String> = emptyMap(),
    val changed: Set<String> = emptySet(),
)

/** Short names that fit a legend pill; long names fall back to [fullName]. */
internal fun compactKeyName(code: Int, fullName: (Int) -> String): String = when (code) {
    KeyEvent.KEYCODE_DPAD_UP -> "▲"
    KeyEvent.KEYCODE_DPAD_DOWN -> "▼"
    KeyEvent.KEYCODE_DPAD_LEFT -> "◀"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "▶"
    KeyEvent.KEYCODE_DPAD_CENTER -> "OK"
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "↵"
    KeyEvent.KEYCODE_ESCAPE -> "Esc"
    KeyEvent.KEYCODE_DEL -> "⌫"
    KeyEvent.KEYCODE_TAB -> "Tab"
    KeyEvent.KEYCODE_BACK -> "↩"
    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "⇧"
    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "Ctrl"
    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "Alt"
    else -> fullName(code)
}

private fun legendText(names: List<String>): String? = when (names.size) {
    0 -> null
    1 -> names.first()
    else -> "${names.first()} +${names.size - 1}"
}

/** Console side: which key(s) of the connected device press each console button. */
internal fun consoleLegend(
    artwork: InputArtwork,
    profile: RetroInputProfile,
    defaults: RetroInputProfile,
    keyName: (Int) -> String,
): ArtworkLegend {
    val labels = mutableMapOf<String, String>()
    val changed = mutableSetOf<String>()
    artwork.keys.forEach { entry ->
        val target = RetroInputTarget.Button(entry.target ?: return@forEach)
        val codes = profile.keys(target)
        legendText(codes.map(keyName))?.let { labels[entry.id] = it }
        if (codes != defaults.keys(target)) changed += entry.id
    }
    return ArtworkLegend(labels, changed)
}

/** Device side, the reverse: which console button each key of the connected device presses. */
internal fun deviceLegend(
    artwork: InputArtwork,
    profile: RetroInputProfile,
    defaults: RetroInputProfile,
    keyCode: (String) -> Int?,
    buttonName: (RetroPadButton) -> String,
): ArtworkLegend {
    val labels = mutableMapOf<String, String>()
    val changed = mutableSetOf<String>()
    artwork.keys.forEach { entry ->
        val code = keyCode(entry.id) ?: return@forEach
        val button = profile.button(code)
        button?.let { labels[entry.id] = buttonName(it) }
        if (button != defaults.button(code)) changed += entry.id
    }
    return ArtworkLegend(labels, changed)
}

/** The console's own buttons in a stable RetroPad order: d-pad, face, shoulders, start/select. */
internal fun assignAllOrder(artwork: InputArtwork): List<RetroPadButton> {
    val present = artwork.keys.mapNotNull { it.target }.toSet()
    return if (present.isEmpty()) RetroPadButton.entries else RetroPadButton.entries.filter { it in present }
}

/**
 * One step of "assign all": a recorded key moves on; a timeout skips the button. Two timeouts
 * in a row end the sequence, so it never waits through every remaining button unattended.
 * Returns null when the sequence is over.
 */
internal data class AssignAllStep(val button: RetroPadButton, val misses: Int)

internal fun nextAssignAllStep(
    order: List<RetroPadButton>,
    current: RetroPadButton,
    misses: Int,
    timedOut: Boolean,
): AssignAllStep? {
    val nextMisses = if (timedOut) misses + 1 else 0
    if (nextMisses >= ASSIGN_ALL_MAX_MISSES) return null
    val next = order.getOrNull(order.indexOf(current) + 1) ?: return null
    return AssignAllStep(next, nextMisses)
}

internal const val ASSIGN_ALL_MAX_MISSES = 2
