package com.ambient.tv.moonlight

/** Called only within the session input gate. Keep independent input sources from releasing each other. */
internal class MoonlightInputOwnership {
    private val mouseSources = Array(5) { mutableSetOf<Int>() }
    private val contacts = mutableSetOf<Int>()

    /** Returns a transition only if aggregate state actually changed. */
    fun mouse(button: Int, source: Int, down: Boolean): Boolean? {
        if (button !in 1..5 || source !in 0..5) return null
        val owners = mouseSources[button - 1]
        val wasDown = owners.isNotEmpty()
        if (down) owners.add(source) else owners.remove(source)
        return owners.isNotEmpty().takeIf { it != wasDown }
    }

    fun touchId(source: Int, id: Int): Int? =
        if (source in 0..4 && id in 0..31) source * 32 + id else null

    fun acceptsTouch(id: Int, type: Byte): Boolean = when (type.toInt()) {
        1 -> id !in contacts
        2, 3, 4 -> id in contacts
        else -> false
    }
    fun acceptedTouch(id: Int, type: Byte) {
        if (type.toInt() == 1) contacts.add(id)
        else if (type.toInt() == 2 || type.toInt() == 4) contacts.remove(id)
    }

    fun clear() { mouseSources.forEach { it.clear() }; contacts.clear() }
}
