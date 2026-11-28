package com.ambient.tv.connectivity

import android.content.SharedPreferences

/**
 * Synchronous input key for the host/WOL topology. Read the stored routing values, rather than
 * parsing and normalizing every saved profile and NAS source for each artwork health check.
 * Listener-based invalidation would leave a stale window after edits and backup imports.
 *
 * Raw profile/source strings stay private: this key must never print addresses or account data.
 */
internal class EndpointConfigurationKey private constructor(private val values: List<Any?>) {
    override fun equals(other: Any?): Boolean = other is EndpointConfigurationKey && values == other.values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "EndpointConfigurationKey"

    companion object {
        private object MalformedValue
        private inline fun value(read: () -> Any?): Any? = try { read() } catch (_: ClassCastException) { MalformedValue }

        private val strings = listOf(
            "server_host", "server_mac", "server_profiles_v1",
            "jellyfin_host", "jellyfin_mac", "jellyfin_server_profiles_v1",
            "emby_host", "emby_mac", "emby_server_profiles_v1",
            "tvh_host", "tvh_mac", "file_sources", "romm_server_url", "romm_auth_mode", "moonlight_hosts",
        )

        fun read(preferences: SharedPreferences): EndpointConfigurationKey = EndpointConfigurationKey(buildList {
            strings.forEach { add(value { preferences.getString(it, null) }) }
            add(value { preferences.getBoolean("plex_enabled", true) })
            add(value { preferences.getBoolean("jellyfin_enabled", false) })
            add(value { preferences.getBoolean("emby_enabled", false) })
            add(value { preferences.getBoolean("tvh_enabled", false) })
            add(value { preferences.getBoolean("romm_enabled", false) })
            add(value { preferences.getBoolean("moonlight_enabled", false) })
            add(value { preferences.getInt("server_port", 32400) })
            add(value { preferences.getInt("jellyfin_port", 8096) })
            add(value { preferences.getInt("emby_port", 8096) })
            add(value { preferences.getInt("tvh_port", 9981) })
        })
    }
}
