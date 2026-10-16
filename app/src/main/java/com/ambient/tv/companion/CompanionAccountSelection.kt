package com.ambient.tv.companion

import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.data.local.SpotifyAccountBackup

internal data class CompanionAccountChoice(val key: String, val label: String)

internal fun syncAccountChoices(source: SettingsBackup): List<CompanionAccountChoice> = buildList {
    source.servers.forEach { add(CompanionAccountChoice("plex:${it.profile.id}", "Plex · ${it.profile.name}")) }
    source.jellyfinServers.forEach { add(CompanionAccountChoice("jellyfin:${it.profile.id}", "Jellyfin · ${it.profile.name}")) }
    source.embyServers.forEach { add(CompanionAccountChoice("emby:${it.profile.id}", "Emby · ${it.profile.name}")) }
    source.fileSources.filter { it.kind != "local" }.forEach { add(CompanionAccountChoice("files:${it.id}", "${it.kind.uppercase()} · ${it.name}")) }
    source.dlnaServers.forEach { add(CompanionAccountChoice("dlna:${it.id}", "DLNA · ${it.name}")) }
    source.iptvSources.orEmpty().forEach { add(CompanionAccountChoice("iptv:${it.id}", "IPTV · ${it.name}")) }
    if (!source.openSubtitlesApiKey.isNullOrBlank()) add(CompanionAccountChoice("opensubtitles", "OpenSubtitles"))
    if (source.tvheadendHost.isNotBlank()) add(CompanionAccountChoice("tvheadend", "TVHeadend · ${source.tvheadendHost}"))
    source.smartTubeAccounts.orEmpty().forEach { add(CompanionAccountChoice("smarttube:${it.id}", "SmartTube · ${it.name}")) }
    if (source.spotifyAccount.signedIn() || source.spotifyClientId.isNotBlank())
        add(CompanionAccountChoice("spotify", "Spotify · ${source.spotifyAccount?.name.orEmpty().ifBlank { source.spotifyDeviceName }}"))
    source.wireGuardProfile?.takeIf { it.endpointHost.isNotBlank() }?.let { add(CompanionAccountChoice(WIREGUARD_CHOICE, "WireGuard · ${it.endpointHost}")) }
    if (!source.tmdbApiKey.isNullOrBlank()) add(CompanionAccountChoice("tmdb", "TMDB"))
}

/** A WireGuard identity used on two devices at once makes the peer flap between them, so it is opt-in. */
internal const val WIREGUARD_CHOICE = "wireguard"
internal fun defaultSyncAccounts(choices: List<CompanionAccountChoice>) = choices.map { it.key }.toSet() - WIREGUARD_CHOICE

internal fun SpotifyAccountBackup?.signedIn() = this != null && (refreshToken != null || accessToken != null)

/** Provider-qualified IDs keep coincident IDs on different providers independent. */
internal fun selectSyncAccounts(source: SettingsBackup, selected: Set<String>): SettingsBackup = source.copy(
    iptvSources = source.iptvSources?.filter { "iptv:${it.id}" in selected },
    openSubtitlesApiKey = source.openSubtitlesApiKey.takeIf { "opensubtitles" in selected },
    openSubtitlesUsername = source.openSubtitlesUsername.takeIf { "opensubtitles" in selected },
    openSubtitlesPassword = source.openSubtitlesPassword.takeIf { "opensubtitles" in selected },
    servers = source.servers.filter { "plex:${it.profile.id}" in selected },
    jellyfinServers = source.jellyfinServers.filter { "jellyfin:${it.profile.id}" in selected },
    embyServers = source.embyServers.filter { "emby:${it.profile.id}" in selected },
    fileSources = source.fileSources.filter { it.kind != "local" && "files:${it.id}" in selected },
    dlnaServers = source.dlnaServers.filter { "dlna:${it.id}" in selected },
    smbPasswords = source.smbPasswords.filterKeys { id -> source.fileSources.any { it.id == id && it.usesPassword && "files:$id" in selected } },
    sshKeys = source.sshKeys.filterKeys { id -> source.fileSources.any { it.id == id && it.kind == com.ambient.tv.data.files.FileKinds.SFTP && "files:$id" in selected } },
    tvheadendEnabled = "tvheadend" in selected && source.tvheadendEnabled,
    tvheadendHost = source.tvheadendHost.takeIf { "tvheadend" in selected }.orEmpty(),
    tvheadendUsername = source.tvheadendUsername.takeIf { "tvheadend" in selected }.orEmpty(),
    tvheadendPassword = source.tvheadendPassword.takeIf { "tvheadend" in selected },
    tvheadendMacAddress = source.tvheadendMacAddress.takeIf { "tvheadend" in selected },
    tmdbApiKey = source.tmdbApiKey.takeIf { "tmdb" in selected },
    smartTubeAccounts = source.smartTubeAccounts?.filter { "smarttube:${it.id}" in selected },
    spotifyAccount = source.spotifyAccount.takeIf { "spotify" in selected },
    spotifyClientId = source.spotifyClientId.takeIf { "spotify" in selected }.orEmpty(),
    wireGuardProfile = source.wireGuardProfile.takeIf { WIREGUARD_CHOICE in selected },
    wireGuardPrivateKey = source.wireGuardPrivateKey.takeIf { WIREGUARD_CHOICE in selected },
    wireGuardPresharedKey = source.wireGuardPresharedKey.takeIf { WIREGUARD_CHOICE in selected },
)
