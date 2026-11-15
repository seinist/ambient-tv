package com.ambient.tv.companion

import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.data.local.TitleVideoSettings

internal data class CompanionVideoProfiles(
    val titles: Map<String, TitleVideoSettings>,
    val scopes: Map<String, TitleVideoSettings>,
)

/** Account merging keeps the receiver's IDs; remembered video settings must use those IDs too. */
internal fun remapCompanionVideoProfiles(
    source: SettingsBackup,
    mappedIds: Map<String, Map<String, String>>,
    target: SettingsBackup? = null,
): CompanionVideoProfiles {
    val accountFields = mapOf("plex" to "servers", "jellyfin" to "jellyfinServers", "emby" to "embyServers")
    data class Binding(val prefix: String, val account: String, val serverId: String, val idEnd: Int)
    fun binding(key: String, scoped: Boolean): Binding? {
        val kind = key.substringBefore(':')
        val provider = if (scoped) key.substringAfter(':', "").substringBefore(':') else kind
        val account = accountFields[provider] ?: return null
        if (scoped && kind !in setOf("season", "series", "collection") || !scoped && provider != "plex") return null
        val prefix = if (scoped) "$kind:$provider:" else "plex:"
        val lengthEnd = key.indexOf(':', prefix.length).takeIf { it >= 0 } ?: return null
        val length = key.substring(prefix.length, lengthEnd).toIntOrNull()?.takeIf { it > 0 } ?: return null
        val idStart = lengthEnd + 1
        // Subtract before adding: malformed lengths must not overflow or match a partial identity.
        if (length >= key.length - idStart) return null
        val idEnd = idStart + length
        if (key[idEnd] != ':') return null
        return Binding(prefix, account, key.substring(idStart, idEnd), idEnd)
    }
    fun remapped(
        values: Map<String, TitleVideoSettings>,
        previous: Map<String, TitleVideoSettings>,
        scoped: Boolean,
    ): Map<String, TitleVideoSettings> {
        val result = linkedMapOf<String, TitleVideoSettings>()
        // A partial account selection must not erase profiles belonging to unselected TV accounts.
        for ((key, value) in previous) {
            val owner = binding(key, scoped) ?: continue
            if (owner.serverId !in mappedIds[owner.account].orEmpty().values) result[key] = value
        }
        for ((key, value) in values) {
            val owner = binding(key, scoped)
            val id = owner?.let { mappedIds[it.account]?.get(it.serverId)?.takeIf(String::isNotBlank) }
            // Source profiles without a transferred account have no verified receiving identity.
            if (target != null && owner != null && id == null) continue
            val mapped = if (id != null) "${owner.prefix}${id.length}:$id${key.substring(owner.idEnd)}" else key
            // Two sender aliases can resolve to one account. Keep its newest deliberate choice.
            if (result[mapped]?.let { it.savedAt > value.savedAt } != true) result[mapped] = value
        }
        return result
    }
    return CompanionVideoProfiles(
        titles = remapped(source.titleVideoSettings, target?.titleVideoSettings.orEmpty(), scoped = false),
        scopes = remapped(source.scopedVideoSettings, target?.scopedVideoSettings.orEmpty(), scoped = true),
    )
}
