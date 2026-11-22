package com.ambient.tv.games

import com.ambient.tv.ui.gamepad.retro.RetroPadButton

/** Bounded, owned labels from RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS; never a core pointer. */
object GameInputDescriptions {
    const val MAX_LABELS = 4 * 16
    const val MAX_LABEL_LENGTH = 96
    private val whitespace = Regex("[\\p{Cc}\\s]+")

    /** JNI rows are port:device:index:id:description. A description may itself contain colons. */
    fun fromNative(rows: Array<String>): Map<String, String> = buildMap {
        for (row in rows.take(256)) {
            if (row.length > 320) continue
            val parts = row.split(':', limit = 5)
            if (parts.size != 5) continue
            val port = parts[0].toIntOrNull() ?: continue
            val device = parts[1].toIntOrNull()?.and(0xff) ?: continue
            val index = parts[2].toIntOrNull() ?: continue
            val button = parts[3].toIntOrNull() ?: continue
            if (port !in 0..3 || button !in 0..15) continue
            if (!((device == 1 && index == 0) || (device == 5 && index == 2))) continue
            val description = label(parts[4])
            if (description.isNotEmpty()) putIfAbsent("$port:$button", description)
        }
    }

    /** Also bounds labels received from a paired TV before presenting them on the phone. */
    fun normalized(labels: Map<String, String>): Map<String, String> = buildMap {
        for ((key, value) in labels.entries.take(MAX_LABELS)) {
            val parts = key.split(':', limit = 2)
            if (parts.size != 2) continue
            val port = parts[0].toIntOrNull() ?: continue
            val button = parts[1].toIntOrNull() ?: continue
            if (port !in 0..3 || button !in 0..15) continue
            val description = label(value)
            if (description.isNotEmpty()) put("$port:$button", description)
        }
    }

    fun forPort(labels: Map<String, String>, port: Int): Map<RetroPadButton, String> = buildMap {
        if (port !in 0..3) return@buildMap
        for (button in RetroPadButton.entries) labels["$port:${button.id}"]?.let { put(button, it) }
    }

    private fun label(value: String): String =
        value.take(256).replace(whitespace, " ").trim().take(MAX_LABEL_LENGTH)
}
