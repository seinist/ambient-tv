package com.ambient.tv.data.jellyfin

object JellyfinBackend {
    const val PREFIX = "j:"

    fun id(raw: String): String = if (raw.startsWith(PREFIX)) raw else "$PREFIX$raw"
    fun raw(id: String): String = id.removePrefix(PREFIX)
    fun owns(id: String): Boolean = id.startsWith(PREFIX)
}

