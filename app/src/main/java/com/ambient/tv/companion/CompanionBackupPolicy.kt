package com.ambient.tv.companion

import com.ambient.tv.data.local.SettingsBackup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Explicit allowlists: new backup fields never silently become remote/device settings. */
internal object CompanionBackupPolicy {
    const val MAX_BYTES = 512 * 1024
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val navSounds = setOf("navSoundsEnabled", "navSoundsVolume", "navSoundBank")
    private val portable = navSounds + setOf(
        "uiLanguageKey", "metadataLanguageKey", "audioLanguageKey", "preferredAudioLanguage",
        "preferredSubtitleLanguage", "subtitlesEnabled", "forcedSubtitlesEnabled", "subtitleModeKey",
        "subtitleLanguageKey", "subtitleFallbackLanguageKey", "fullSubtitlesOnForeignAudio", "forcedSubtitlesInUiLanguage", "mediaSelectionBehaviorKey",
        "showLibraryRowsOnHome", "showContinueWatchingHub", "showRecentHomeHubs", "showCollectionHomeHubs",
        "accentColorKey", "customAccentHex", "customPlaybackAccentHex", "dualAccentEnabled", "themeModeKey", "homeHeroStyleKey", "introAnimationKey", "notificationDisplayDurationKey",
        "trailersEnabled", "trailerLanguage", "youtubeSponsorBlockEnabled", "youtubeFeedKeys", "youtubeStartFeedKey",
        "youtubeAutoplayNextEnabled", "youtubeMediaSelectionBehaviorKey", "introSkipMode", "creditsSkipMode",
        "nextUpEnabled", "nextUpLeadSeconds", "nextUpCreditsDelaySeconds", "nextUpAutoplayEpisodes", "nextUpAutoplayCollections", "nextUpOverlayEnabled",
        "nextUpStillWatchingEpisodes", "sleepAutoStartKey", "sleepWarningMinutes", "sleepDailyStopMinute", "bigSeekJumpSeconds", "pauseRewindSeconds", "pauseRewindMinPauseSeconds", "resumeRewindSeconds", "sidebarSystemOrder", "sidebarDividersAfter", "sidebarEntryOrder", "sidebarEntryDividers", "systemNames",
        "spotifySubmenuKeys", "radioSubmenuKeys", "telegramSubmenuKeys", "tvheadendSubmenuKeys", "sidebarSettingsShortcutHidden",
        "homeHeroIntensityPercent", "homeHeroSpreadKey", "homeBackgroundEverywhere", "focusArtworkBackdrop", "chooseServerOnStartup", "themeMusicVolume", "plexAutoRefreshIntervalSeconds",
        "plexThemeMusicEnabled", "jellyfinThemeMusicEnabled", "embyThemeMusicEnabled", "tvheadendShowOnHome",
        "playerTechBadgesVisible", "osdHiddenButtons", "visibleBackgroundVideoPlayback", "backgroundVideoDimPercent",
        "audioVisualizerModeKey", "projectmPresetIntervalSeconds", "projectmTransitionSeconds", "projectmPresetCategoryKey",
        "screensaverEnabled", "screensaverTimeoutMinutes", "screensaverModeKey", "screensaverShowClock", "screensaverShowDate",
        "screensaverUse24HourClock", "clock", "subtitleAppearance", "nightSchedule", "screensaverUseArtwork", "screensaverArtworkIntervalSeconds", "screensaverDimPercent",
        "photoIncludeSubfolders", "photoSortKey",
        "photoSlideshowIntervalSeconds", "photoTransitionKey", "photoKenBurns", "photoShuffle", "photoShowInfo",
        "liveTvReminderLeadMinutes", "liveDirectRecordEnabled", "playerOsdSeconds", "playerBackMinimizes", "networkBufferKey",
        "radioEnabled", "radioFavorites",
    )
    /** Fields older backups do not carry: their defaults must not overwrite the receiver's values. */
    private val sinceSchema58 = setOf("liveTvReminderLeadMinutes", "liveTimeshiftMb")
    private val sinceSchema66 = setOf("allowHighRefreshDoubling", "gpuRenderResolutionKey", "mpvSdrCurveKey", "svpExplicitChoice", "youtubeVideoPreset")
    private val sinceSchema69 = setOf("titleVideoSettings", "scopedVideoSettings")
    private val sinceSchema74 = setOf("pauseRewindSeconds", "pauseRewindMinPauseSeconds", "resumeRewindSeconds")
    private val sinceSchema75 = setOf("radioEnabled", "radioFavorites")
    private val profileKeys = setOf("titleVideoSettings", "scopedVideoSettings")
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val compact = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }
    private fun profilesOmittedIn(source: SettingsBackup) = source.titleVideoSettings.isEmpty() && source.scopedVideoSettings.isEmpty()
    private val sinceSchema62 = setOf("sleepAutoStartKey", "sleepWarningMinutes")
    private val hardware = setOf(
        "autoSelectDisplayVersion", "displayRefreshAdjustmentKey", "displayModeWhitelist", "preferredDisplayModeKey",
        "allowDoubleRefreshRate", "preferDoubleRefreshRate", "allowThreeTwoPulldown", "displaySwitchPauseMs", "syncPlaybackToDisplay", "uiScale", "overscanPercent", "uiHdrModeKey", "sdrToHdrEnabled", "sdrToHdrPeakNits", "sdrToHdrReferenceWhiteNits",
        "allowHighRefreshDoubling", "gpuRenderResolutionKey", "mpvSdrCurveKey", "svpExplicitChoice", "mpvImageExplicitChoice",
        "performanceModeKey", "uiPerformanceModeKey", "playbackPerformanceModeKey", "frameDropTestSeconds", "highQualityAssetsEnabled", "playbackBackendKey", "tvheadendPlaybackBackendKey",
        "mpvVideoOutputModeKey", "mpvVideoDecoderKey", "mpvHdrGpuEnabled", "vlcVideoOutputModeKey", "mediaAudioPassthroughEnabled",
        "libraryGridColumns", "libraryListRows", "displayChangeNotificationsEnabled",
        "projectmQualityKey", "projectmResolutionKey", "projectmHdr4kAllowed", "screensaverQualityKey", "resolutionVideoProfiles",
        "titleVideoSettings", "scopedVideoSettings",
        "audioSettings", "mpvImageOptions", "vlcImageOptions", "media3ImageOptions", "mpvScalingFilterKey",
        "mpvDebandEnabled", "vlcScalingFilterKey", "tvheadendDeinterlaceModeKey", "tvheadendDeinterlaceFilterKey",
        "tvheadendMpvDeinterlaceFilterKey", "youtubeMaxVideoHeight", "youtubeVideoPreset", "youtubeNetworkEngine", "youtubeBufferKey", "liveTimeshiftMb", "liveTimeshiftEnabled",
    )
    private val accounts = setOf("schemaVersion", "exportedAtEpochMs", "servers", "jellyfinServers", "embyServers",
        "fileSources", "dlnaServers", "smbPasswords", "sshKeys", "plexEnabled", "jellyfinEnabled", "embyEnabled", "filesEnabled",
        "tvheadendEnabled", "tvheadendHost", "tvheadendPort", "tvheadendUseHttps", "tvheadendUsername", "tvheadendPassword",
        "tvheadendStreamProfile", "tvheadendMacAddress", "tvheadendAutoWakeEnabled", "tmdbApiKey",
        "activeServerId", "activeJellyfinServerId", "activeEmbyServerId", "youtubeEnabled", "spotifyEnabled",
        "smartTubeAccounts", "spotifyAccount", "spotifyClientId", "wireGuardProfile", "wireGuardPrivateKey", "wireGuardPresharedKey",
        "iptvSources", "openSubtitlesApiKey", "openSubtitlesUsername", "openSubtitlesPassword")
    /** Every backup field the companion knows about; a new field must be added to one of the sets. */
    internal val classifiedFields: Set<String> get() = portable + hardware + accounts + deviceOnly
    /**
     * Fields that are not synced through the companion today (unchanged behaviour, now explicit):
     * hidden sections/home rows, WireGuard toggles, Spotify device data,
     * search history and hardware probes. Moving one into a synced set is a product decision.
     */
    private val deviceOnly = setOf(
        "telegramEnabled", "spotifyDeviceName", "spotifyBitrateKbps", "spotifyVisualizerModeKey", "plexHiddenSectionKeys",
        "jellyfinHiddenSectionKeys", "embyHiddenSectionKeys", "localNetworkOnly", "wolRetryCount", "svpAvailable",
        "plexAudioPassthroughEnabled", "tvheadendAudioPassthroughEnabled", "screensaverPhotoAlbumKey",
        "screensaverPhotoAlbumTitle", "homeRows", "systemHomeHiddenRows", "libraryPreferences", "recentSearches",
        "wireGuardEnabled", "wireGuardPlexEnabled", "wireGuardJellyfinEnabled", "wireGuardEmbyEnabled",
        "wireGuardTvheadendEnabled",
    )
    private fun normalizedHost(host: String) = host.trim().lowercase().trimEnd('.')
    private fun obj(value: SettingsBackup) = json.encodeToJsonElement(value).jsonObject
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.list(key: String) = this[key]?.jsonArray.orEmpty().map { it.jsonObject }
    private fun JsonObject.optional(key: String) = this[key]?.takeUnless { it == JsonNull }
    private fun JsonObject.signedIn() = text("refreshToken").isNotEmpty() || text("accessToken").isNotEmpty()
    private fun identity(p: JsonObject, type: String): String {
        val fields = when (type) {
            "fileSources" -> listOf("kind", "host", "port", "share", "rootPath", "username", "domain", "guest", "nfsUid", "nfsGid")
            "dlnaServers" -> listOf("udn")
            else -> listOf("host", "port", "useHttps", "basePath", "username", "userId")
        }
        return fields.joinToString("\u0000") { key -> p.text(key).let { if (key == "host") normalizedHost(it) else it } }
    }

    fun prepareWithInfo(source: SettingsBackup, credentials: Boolean, mode: String = "settings"): Prepared {
        require(mode in setOf("accounts", "settings", "preferences", "nav_sounds"))
        if (mode == "nav_sounds") {
            require(!credentials)
            com.ambient.tv.ui.sound.NavSoundBank.import(source.navSoundBank.ifBlank { com.ambient.tv.ui.sound.NavSoundBank().encode() })
            require(source.navSoundsVolume.isFinite() && source.navSoundsVolume in 0f..1f)
            return Prepared(JsonObject(obj(source).filterKeys { it in navSounds || it in setOf("schemaVersion", "exportedAtEpochMs") })
                .toString().also { require(it.toByteArray().size <= MAX_BYTES) }, false)
        }
        // Preferences never contain account fields, even with credentials requested by an old caller.
        if (mode == "preferences") return fit(source, obj(source).filterKeys {
            it in portable || it in hardware || it in setOf("schemaVersion", "exportedAtEpochMs")
        }.toMutableMap())
        val allowed = accounts + if (mode == "settings") portable + hardware else emptySet()
        val s = obj(source).filterKeys { it in allowed }.toMutableMap()
        if (!credentials) {
            for (field in listOf("servers", "jellyfinServers", "embyServers")) s[field] = JsonArray(s.getValue(field).jsonArray.map {
                JsonObject(it.jsonObject.toMutableMap().apply { put("token", JsonNull); if (field != "servers") put("password", JsonNull) })
            })
            s["smbPasswords"] = JsonObject(emptyMap()); s["sshKeys"] = JsonObject(emptyMap()); s["tvheadendPassword"] = JsonNull; s["tmdbApiKey"] = JsonNull
            s["smartTubeAccounts"]?.takeUnless { it == JsonNull }?.let { accounts ->
                s["smartTubeAccounts"] = JsonArray(accounts.jsonArray.map { JsonObject(it.jsonObject + ("credential" to JsonNull)) })
            }
            s["spotifyAccount"]?.takeUnless { it == JsonNull }?.let {
                s["spotifyAccount"] = JsonObject(it.jsonObject + mapOf("accessToken" to JsonNull, "refreshToken" to JsonNull))
            }
            s.remove("wireGuardPrivateKey"); s.remove("wireGuardPresharedKey")
            // Playlist URLs carry the provider account; the OpenSubtitles key is the user's own.
            for (key in listOf("iptvSources", "openSubtitlesApiKey", "openSubtitlesUsername", "openSubtitlesPassword")) s[key] = JsonNull
        }
        // Per-source VPN routing and local paths are never portable, even when credentials are selected.
        s["fileSources"] = JsonArray(s.getValue("fileSources").jsonArray.filter { it.jsonObject.text("kind") != "local" }.map {
            JsonObject(it.jsonObject - "wireGuard")
        })
        return fit(source, s)
    }

    /** Companion payload plus whether title/scope profiles had to stay on this TV to fit [MAX_BYTES]. */
    data class Prepared(val json: String, val profilesOmitted: Boolean)

    fun prepare(source: SettingsBackup, credentials: Boolean, mode: String = "settings"): String = prepareWithInfo(source, credentials, mode).json

    /**
     * Title and season/series/collection profiles travel compactly (no nulls, no default values). If the
     * payload still exceeds [MAX_BYTES], they are left out instead of failing the transfer; the receiver
     * keeps its own (see [merge]) and the sender shows a hint.
     */
    private fun fit(source: SettingsBackup, fields: MutableMap<String, JsonElement>): Prepared {
        if ("titleVideoSettings" in fields) fields["titleVideoSettings"] = compact.encodeToJsonElement(source.titleVideoSettings)
        if ("scopedVideoSettings" in fields) fields["scopedVideoSettings"] = compact.encodeToJsonElement(source.scopedVideoSettings)
        val full = JsonObject(fields).toString()
        if (full.toByteArray().size <= MAX_BYTES) return Prepared(full, false)
        require(profileKeys.any { it in fields }) { "Companion payload too large" }
        return Prepared(JsonObject(fields - profileKeys).toString().also { require(it.toByteArray().size <= MAX_BYTES) }, true)
    }

    fun merge(source: SettingsBackup, target: SettingsBackup, mode: String, overwrite: Boolean, deviceSettings: Boolean): String {
        require(mode in setOf("accounts", "settings", "preferences", "nav_sounds"))
        val incoming = obj(source); val current = obj(target); val result = current.toMutableMap()
        if (mode == "nav_sounds") {
            require(overwrite)
            prepare(source, false, mode)
            navSounds.forEach { result[it] = incoming.getValue(it) }
            return JsonObject(result).toString()
        }
        if (mode in setOf("settings", "preferences")) {
            require(overwrite) // Settings replacement is only available after explicit TV confirmation.
            (portable + if (deviceSettings) hardware else emptySet()).filter { source.schemaVersion >= 50 || it !in navSounds }.filter { source.schemaVersion >= 58 || it !in sinceSchema58 }.filter { source.schemaVersion >= 59 || it !in setOf("liveTimeshiftEnabled", "liveDirectRecordEnabled") }.filter { source.schemaVersion >= 60 || it != "playerOsdSeconds" }.filter { source.schemaVersion >= 61 || it != "displaySwitchPauseMs" }.filter { source.schemaVersion >= 62 || it !in sinceSchema62 }.filter { source.schemaVersion >= 63 || it != "sleepDailyStopMinute" }.filter { source.schemaVersion >= 64 || it != "mpvHdrGpuEnabled" }.filter { source.schemaVersion >= 65 || it != "sdrToHdrReferenceWhiteNits" }.filter { source.schemaVersion >= 66 || it !in sinceSchema66 }.filter { source.schemaVersion >= 67 || it != "syncPlaybackToDisplay" }.filter { source.schemaVersion >= 68 || it != "mpvImageExplicitChoice" }.filter { source.schemaVersion >= 69 || it !in sinceSchema69 }.filter { source.schemaVersion >= 71 || it != "preferDoubleRefreshRate" }.filter { source.schemaVersion >= 73 || it !in setOf("playerBackMinimizes", "networkBufferKey") }.filter { source.schemaVersion >= 74 || it !in sinceSchema74 }.filter { source.schemaVersion >= 75 || it !in sinceSchema75 }
                // A sender from before the clock page has no clock: the receiver keeps its own.
                .filter { it != "clock" || source.clock != null }
                .filter { it != "subtitleAppearance" || source.subtitleAppearance != null }
                .filter { it != "nightSchedule" || source.nightSchedule != null }
                // Profiles left out by the sender (too large) arrive empty: the TV keeps its own.
                .filter { it !in profileKeys || !profilesOmittedIn(source) }.forEach { key -> incoming[key]?.let { result[key] = it } }
        }
        if (mode == "preferences") return JsonObject(result).toString()
        val smb = current.getValue("smbPasswords").jsonObject.toMutableMap()
        val ssh = (current["sshKeys"] as? JsonObject).orEmpty().toMutableMap()
        // Sender ID -> ID on the receiver, so the sender's active server can be resolved after the merge.
        val mappedIds = mutableMapOf<String, MutableMap<String, String>>()
        for (field in listOf("servers", "jellyfinServers", "embyServers", "fileSources", "dlnaServers")) {
            val nested = field in setOf("servers", "jellyfinServers", "embyServers")
            val values = current.list(field).toMutableList()
            val limit = if (field == "dlnaServers") 30 else 50
            for (entry in incoming.list(field)) {
                if (nested && entry["profile"] !is JsonObject) continue
                val profile = if (nested) entry.getValue("profile").jsonObject else entry
                if (field == "fileSources" && profile.text("kind") == "local") continue
                val index = values.indexOfFirst { existing -> identity(if (nested) existing.getValue("profile").jsonObject else existing, field) == identity(profile, field) }
                val previous = values.getOrNull(index)
                val oldProfile = previous?.let { if (nested) it.getValue("profile").jsonObject else it }
                val sourceId = profile.text("id")
                if (previous != null && !overwrite) { mappedIds.getOrPut(field) { mutableMapOf() }[sourceId] = oldProfile!!.text("id"); continue }
                // A single malformed entry is skipped instead of aborting the whole merge.
                if (sourceId.isBlank() || sourceId.length > 256) continue
                if (index < 0 && values.size >= limit) continue
                val id = oldProfile?.text("id") ?: sourceId.takeUnless { candidate -> values.any { (if (nested) it.getValue("profile").jsonObject else it).text("id") == candidate } }
                    ?: "remote-${fingerprint(identity(profile, field).toByteArray()).take(24)}"
                val newProfile = JsonObject(profile.toMutableMap().apply {
                    put("id", JsonPrimitive(id))
                    // Routing belongs to the receiving device, including credential/hardware imports.
                    if (field == "fileSources") put("wireGuard", oldProfile?.get("wireGuard") ?: JsonPrimitive(false))
                })
                val merged = if (nested) JsonObject(entry.toMutableMap().apply {
                    put("profile", newProfile)
                    for (credential in listOf("token", "password")) {
                        if (entry[credential] == JsonNull || entry[credential] == null) previous?.get(credential)?.let { put(credential, it) }
                    }
                }) else newProfile
                if (index < 0) values += merged else values[index] = merged
                mappedIds.getOrPut(field) { mutableMapOf() }[sourceId] = id
                if (field == "fileSources") incoming.getValue("smbPasswords").jsonObject[sourceId]?.let { smb[id] = it }
                if (field == "fileSources") (incoming["sshKeys"] as? JsonObject)?.get(sourceId)?.let { ssh[id] = it }
            }
            result[field] = JsonArray(values)
        }
        result["smbPasswords"] = JsonObject(smb)
        result["sshKeys"] = JsonObject(ssh)
        for ((field, enabled) in listOf("servers" to "plexEnabled", "jellyfinServers" to "jellyfinEnabled", "embyServers" to "embyEnabled", "fileSources" to "filesEnabled")) {
            if (result.getValue(field).jsonArray.size > current.list(field).size) result[enabled] = JsonPrimitive(true)
            // A system the sender uses with transferred accounts is switched on here too (never off).
            if (incoming[enabled]?.jsonPrimitive?.booleanOrNull == true && incoming.list(field).isNotEmpty()) result[enabled] = JsonPrimitive(true)
        }
        // The receiver keeps a valid active selection; without one it adopts the sender's (fresh setup).
        for ((field, active) in listOf("servers" to "activeServerId", "jellyfinServers" to "activeJellyfinServerId", "embyServers" to "activeEmbyServerId")) {
            val ids = result.getValue(field).jsonArray.map { it.jsonObject.getValue("profile").jsonObject.text("id") }
            if (current.text(active) in ids) continue
            mappedIds[field]?.get(incoming.text(active))?.let { result[active] = JsonPrimitive(it) }
        }
        if (incoming["youtubeEnabled"]?.jsonPrimitive?.booleanOrNull == true) result["youtubeEnabled"] = JsonPrimitive(true)
        val sourceTvh = incoming.text("tvheadendHost")
        if (sourceTvh.isNotBlank() && (current.text("tvheadendHost").isBlank() || overwrite)) {
            val sameHost = normalizedHost(incoming.text("tvheadendHost")) == normalizedHost(current.text("tvheadendHost"))
            if (!sameHost) result["wireGuardTvheadendEnabled"] = JsonPrimitive(false)
            val same = sameHost && listOf("tvheadendPort", "tvheadendUseHttps", "tvheadendUsername").all { incoming[it] == current[it] }
            for (key in listOf("tvheadendEnabled", "tvheadendHost", "tvheadendPort", "tvheadendUseHttps", "tvheadendUsername", "tvheadendStreamProfile", "tvheadendMacAddress", "tvheadendAutoWakeEnabled")) result[key] = incoming.getValue(key)
            result["tvheadendPassword"] = incoming["tvheadendPassword"]?.takeUnless { it == JsonNull }
                ?: if (same) current.getValue("tvheadendPassword") else JsonNull
        }
        if (incoming["tmdbApiKey"] != JsonNull && (overwrite || current["tmdbApiKey"] == JsonNull)) result["tmdbApiKey"] = incoming.getValue("tmdbApiKey")
        // OpenSubtitles: key and account travel together.
        if (incoming.optional("openSubtitlesApiKey") != null && (overwrite || current.optional("openSubtitlesApiKey") == null)) {
            for (key in listOf("openSubtitlesApiKey", "openSubtitlesUsername", "openSubtitlesPassword")) result[key] = incoming[key] ?: JsonNull
        }
        incoming.optional("iptvSources")?.let { sources ->
            // Same playlist (id or address) is replaced only with overwrite; new ones are added up to the limit.
            val values = current.optional("iptvSources")?.jsonArray.orEmpty().map { it.jsonObject }.toMutableList()
            for (entry in sources.jsonArray.map { it.jsonObject }) {
                if (entry.text("id").isBlank() || entry.text("playlistUrl").isBlank()) continue
                val index = values.indexOfFirst { it.text("id") == entry.text("id") || it.text("playlistUrl") == entry.text("playlistUrl") }
                if (index >= 0 && !overwrite) continue
                if (index < 0 && values.size >= com.ambient.tv.data.iptv.IptvStore.MAX_SOURCES) continue
                if (index < 0) values += entry else values[index] = JsonObject(entry + ("id" to values[index].getValue("id")))
            }
            result["iptvSources"] = JsonArray(values)
        }
        incoming.optional("smartTubeAccounts")?.let { accounts ->
            val values = current.optional("smartTubeAccounts")?.jsonArray.orEmpty().map { it.jsonObject }.toMutableList()
            for (entry in accounts.jsonArray.map { it.jsonObject }) {
                val index = values.indexOfFirst { it.text("id") == entry.text("id") }
                // Without a credential an entry can only confirm an account the receiver already has.
                if (entry.text("credential").isEmpty() || index >= 0 && !overwrite) continue
                if (index < 0 && values.size >= 50) continue
                if (index < 0) values += entry else values[index] = entry
            }
            result["smartTubeAccounts"] = JsonArray(values)
        }
        val spotify = incoming.optional("spotifyAccount")?.jsonObject
        val receiverSignedIn = current.optional("spotifyAccount")?.jsonObject?.signedIn() == true
        val spotifyClient = incoming.text("spotifyClientId")
        if (spotify?.signedIn() == true && (overwrite || !receiverSignedIn)) {
            result["spotifyAccount"] = spotify; result["spotifyEnabled"] = JsonPrimitive(true)
            if (spotifyClient.isNotBlank()) result["spotifyClientId"] = JsonPrimitive(spotifyClient)
        } else if (spotifyClient.isNotBlank() && !receiverSignedIn && (overwrite || current.text("spotifyClientId").isBlank())) {
            // A client change clears the receiver's Spotify sign-in, so only an unused receiver adopts it.
            result["spotifyClientId"] = JsonPrimitive(spotifyClient)
        }
        incoming.optional("wireGuardProfile")?.let { profile ->
            if (overwrite || current.optional("wireGuardProfile") == null) {
                // Tunnel and per-service routing switches stay as the receiver set them.
                val changed = profile != current.optional("wireGuardProfile")
                result["wireGuardProfile"] = profile
                // Keys follow the profile: a new peer never inherits the receiver's old keys.
                for (key in listOf("wireGuardPrivateKey", "wireGuardPresharedKey")) {
                    val value = incoming.optional(key)
                    if (value != null) result[key] = value else if (changed) result[key] = JsonNull
                }
            }
        }
        return JsonObject(result).toString()
    }
}
