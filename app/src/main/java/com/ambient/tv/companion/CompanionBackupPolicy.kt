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
        "showLibraryRowsOnHome", "showContinueWatchingHub", "showRecentHomeHubs", "showCollectionHomeHubs", "homeRows", "systemHomeHiddenRows",
        "accentColorKey", "accentStyleKey", "accentMotionKey", "customAccentHex", "customPlaybackAccentHex", "dualAccentEnabled", "themeModeKey", "homeHeroStyleKey", "introAnimationKey", "notificationDisplayDurationKey",
        "trailersEnabled", "trailerLanguage", "youtubeSponsorBlockEnabled", "youtubeSponsorBlockModes", "youtubeSponsorBlockShowSegments", "youtubeAutoCaptions", "youtubeHistoryPaused", "youtubeHideShorts", "youtubeHideLiveInSubscriptions", "youtubeDeArrow", "youtubeFeedKeys", "youtubeStartFeedKey",
        "youtubeAutoplayNextEnabled", "youtubeSubtitlesEnabled", "youtubeMediaSelectionBehaviorKey", "telegramMediaSelectionBehaviorKey", "introSkipMode", "creditsSkipMode",
        "nextUpEnabled", "nextUpLeadSeconds", "nextUpCreditsDelaySeconds", "nextUpAutoplayEpisodes", "nextUpAutoplayCollections", "nextUpOverlayEnabled",
        "nextUpStillWatchingEpisodes", "sleepAutoStartKey", "sleepWarningMinutes", "sleepDailyStopMinute", "bigSeekJumpSeconds", "pauseRewindSeconds", "pauseRewindMinPauseSeconds", "resumeRewindSeconds", "watchedThresholdPercent", "resumeIgnoreEndPercent", "sidebarSystemOrder", "sidebarDividersAfter", "sidebarEntryOrder", "sidebarEntryDividers", "systemNames",
        "spotifySubmenuKeys", "radioSubmenuKeys", "telegramSubmenuKeys", "tvheadendSubmenuKeys", "sidebarSettingsShortcutHidden",
        "homeHeroIntensityPercent", "homeHeroSpreadKey", "homeBackgroundEverywhere", "focusArtworkBackdrop", "chooseServerOnStartup", "themeMusicVolume", "plexAutoRefreshIntervalSeconds",
        "plexThemeMusicEnabled", "jellyfinThemeMusicEnabled", "embyThemeMusicEnabled", "tvheadendShowOnHome",
        "playerTechBadgesVisible", "playerOsdModeIconVisible", "osdHiddenButtons", "visibleBackgroundVideoPlayback", "backgroundVideoDimPercent",
        "audioVisualizerModeKey", "spotifyBitrateKbps", "spotifyVisualizerModeKey", "projectmPresetIntervalSeconds", "projectmTransitionSeconds", "projectmPresetCategoryKey",
        "screensaverEnabled", "screensaverTimeoutMinutes", "screensaverModeKey", "screensaverShowClock", "screensaverShowDate",
        "screensaverUse24HourClock", "clock", "subtitleAppearance", "nightSchedule", "screensaverUseArtwork", "screensaverArtworkIntervalSeconds", "screensaverDimPercent",
        "photoIncludeSubfolders", "photoSortKey",
        "photoSlideshowIntervalSeconds", "photoTransitionKey", "photoKenBurns", "photoShuffle", "photoShowInfo",
        "liveTvReminderLeadMinutes", "liveDirectRecordEnabled", "playerOsdSeconds", "playerBackMinimizes", "networkBufferKey",
        "radioEnabled", "radioFavorites", "weather", "wakeOnOpenSystems",
    )
    /** Fields older backups do not carry: their defaults must not overwrite the receiver's values. */
    private val sinceSchema58 = setOf("liveTvReminderLeadMinutes", "liveTimeshiftMb")
    private val sinceSchema66 = setOf("allowHighRefreshDoubling", "gpuRenderResolutionKey", "mpvSdrCurveKey", "svpExplicitChoice", "youtubeVideoPreset")
    private val sinceSchema69 = setOf("titleVideoSettings", "scopedVideoSettings")
    private val sinceSchema74 = setOf("pauseRewindSeconds", "pauseRewindMinPauseSeconds", "resumeRewindSeconds")
    private val sinceSchema75 = setOf("radioEnabled", "radioFavorites")
    // Older companion senders omitted these existing backup fields entirely. Their decoded
    // defaults do not mean that the user asked to replace the receiving device's choices.
    private val sinceSchema80 = setOf("spotifyBitrateKbps", "spotifyVisualizerModeKey", "systemHomeHiddenRows")
    private val profileKeys = setOf("titleVideoSettings", "scopedVideoSettings")
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val compact = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }
    private fun profilesOmittedIn(source: SettingsBackup) = source.titleVideoSettings.isEmpty() && source.scopedVideoSettings.isEmpty()
    private fun profilePresent(source: SettingsBackup, key: String, presentFields: Set<String>?) =
        presentFields?.contains(key) ?: !profilesOmittedIn(source)
    private val sinceSchema62 = setOf("sleepAutoStartKey", "sleepWarningMinutes")
    private val hardware = setOf(
        "autoSelectDisplayVersion", "displayRefreshAdjustmentKey", "displayModeWhitelist", "preferredDisplayModeKey",
        "allowDoubleRefreshRate", "preferDoubleRefreshRate", "allowThreeTwoPulldown", "displaySwitchPauseMs", "syncPlaybackToDisplay", "uiScale", "uiRenderResolutionKey", "overscanPercent", "uiHdrModeKey", "sdrToHdrEnabled", "sdrToHdrPeakNits", "sdrToHdrReferenceWhiteNits",
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
        "iptvSources", "openSubtitlesApiKey", "openSubtitlesUsername", "openSubtitlesPassword",
        "monogramApiCredentials")
    /** Every backup field the companion knows about; a new field must be added to one of the sets. */
    internal val classifiedFields: Set<String> get() = portable + hardware + accounts + deviceOnly + "extras"
    /** Nested additions need the same explicit sharing decision as top-level settings. */
    internal val classifiedExtraFields = mapOf(
        "portable" to setOf(
            "allContinueSystems", "filterBarShown", "launcherIntegrationEnabled", "settingsLevelKey",
            "autoPictureInPicture", "projectmShowPresetName", "projectmPresetShuffle", "gaplessMusicEnabled",
            "teletextSubtitleChoice", "lyricsVisible", "lyricsOnlineEnabled", "frameDropOverlayHistory",
            "nextUpMinimalEnabled",
            "liveTvReminderAutoSwitch", "autoUpdateCheck", "projectmFavorites", "keyBindings", "gamepadSettings", "retroInputSettings", "gameSettings", "gameLibraryOptions", "gameLibraryOverrides", "gameLibraryFolders",
            "viewTransitionStyleKey", "mousePointerHideSeconds",
            "gameMetadataOptions", "moonlightStreamProfile", "moonlightLibraryView",
            "moonlightGridColumns", "moonlightListRows", "moonlightSortKey", "moonlightSortDescending",
        ),
        "device" to setOf("imageCacheMb", "cardImages1080"),
        "connections" to setOf("tvheadendHtspPort", "rommEnabled", "rommServerUrl", "rommUsername",
            "moonlightEnabled", "moonlightHosts", "moonlightHostProfiles"),
        "credentials" to setOf("gameMetadata"),
    )
    /**
     * Classified extras the companion does not transfer: RomM pairs every device on its own (QR
     * or code), so its address travels only in a file backup.
     */
    private val companionLocalExtraFields = setOf("rommEnabled", "rommServerUrl", "rommUsername",
        // Server addresses can be transferred by explicit file backup, but a phone's local
        // network host inventory must not silently replace the TV's paired identities.
        "moonlightEnabled", "moonlightHosts", "moonlightHostProfiles")

    /**
     * Fields that are not synced through the companion today (unchanged behaviour, now explicit):
     * source-specific hidden sections/library choices, WireGuard toggles, Spotify device identity,
     * search history and hardware probes. Moving one into a synced set is a product decision.
     */
    private val deviceOnly = setOf(
        "telegramEnabled", "spotifyDeviceName", "plexHiddenSectionKeys",
        "jellyfinHiddenSectionKeys", "embyHiddenSectionKeys", "localNetworkOnly", "wolRetryCount", "svpAvailable",
        "plexAudioPassthroughEnabled", "tvheadendAudioPassthroughEnabled", "screensaverPhotoAlbumKey",
        "screensaverPhotoAlbumTitle", "libraryPreferences", "recentSearches",
        "wireGuardEnabled", "wireGuardPlexEnabled", "wireGuardJellyfinEnabled", "wireGuardEmbyEnabled",
        "wireGuardTvheadendEnabled",
    )
    private fun normalizedHost(host: String) = host.trim().lowercase().trimEnd('.')
    private fun obj(value: SettingsBackup): JsonObject {
        val fields = json.encodeToJsonElement(value).jsonObject.toMutableMap()
        // The merged document carries the receiver's current schema, so migrations which
        // depend on the sender's schema must happen before filtering or merging its fields.
        if (value.schemaVersion < 44) fields["mediaAudioPassthroughEnabled"] = JsonPrimitive(
            value.mediaAudioPassthroughEnabled || value.plexAudioPassthroughEnabled || value.tvheadendAudioPassthroughEnabled,
        ) // Idempotent: a filtered old-schema payload already carries the migrated global value.
        if (value.schemaVersion < 70) value.audioSettings?.let { audio ->
            fields["audioSettings"] = json.encodeToJsonElement(audio.copy(
                profiles = audio.profiles.mapValues { (_, options) -> options.copy(forceChannels = 0) },
            ))
        }
        return JsonObject(fields)
    }

    /** Preserve wire presence across decoding: an omitted profile is not an explicitly empty one. */
    internal fun presentFields(raw: String): Set<String> = json.parseToJsonElement(raw).jsonObject.keys

    private fun preparedExtras(source: JsonObject, mode: String, credentials: Boolean = false): JsonElement {
        val extras = source["extras"] as? JsonObject ?: return JsonNull
        val groups = buildSet {
            if (mode in setOf("settings", "preferences")) addAll(listOf("portable", "device"))
            if (mode in setOf("settings", "accounts") && source.text("tvheadendHost").isNotBlank()) add("connections")
            if (credentials && mode in setOf("settings", "accounts")) add("credentials")
        }
        return JsonObject(extras.filterKeys { it in groups }.mapValues { (group, value) ->
            if (value is JsonObject) JsonObject(value.filterKeys {
                it in classifiedExtraFields.getValue(group) && it !in companionLocalExtraFields &&
                    !(mode == "preferences" && group == "portable" && it in setOf("gameLibraryOverrides", "gameLibraryFolders"))
            }) else JsonNull
        })
    }

    /** Missing/null fields in an optional group preserve the receiver, including partial objects. */
    private fun mergeExtras(incoming: JsonObject, result: MutableMap<String, JsonElement>, groups: Set<String>) {
        val source = incoming["extras"] as? JsonObject ?: return
        val extras = (result["extras"] as? JsonObject).orEmpty().toMutableMap()
        for (group in groups) {
            val values = (source[group] as? JsonObject)?.filter { (key, value) ->
                key in classifiedExtraFields.getValue(group) && key !in companionLocalExtraFields && value != JsonNull
            } ?: continue
            if (values.isEmpty()) continue
            extras[group] = JsonObject((extras[group] as? JsonObject).orEmpty() + values)
        }
        if (extras.isNotEmpty()) result["extras"] = JsonObject(extras)
    }
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

    /**
     * [sessions]: login sessions (Plex/Jellyfin/Emby tokens, SmartTube accounts, Spotify tokens),
     * so the receiver is signed in. [credentials]: passwords and keys. Both are off by default
     * and chosen separately; a Telegram user session is never part of either.
     */
    fun prepareWithInfo(source: SettingsBackup, credentials: Boolean, mode: String = "settings",
        sessions: Boolean = credentials, presentFields: Set<String>? = null): Prepared {
        require(mode in setOf("accounts", "settings", "preferences", "nav_sounds"))
        if (mode == "nav_sounds") {
            require(!credentials && !sessions)
            com.ambient.tv.ui.sound.NavSoundBank.import(source.navSoundBank.ifBlank { com.ambient.tv.ui.sound.NavSoundBank().encode() })
            require(source.navSoundsVolume.isFinite() && source.navSoundsVolume in 0f..1f)
            return Prepared(JsonObject(obj(source).filterKeys { it in navSounds || it in setOf("schemaVersion", "exportedAtEpochMs") })
                .toString().also { require(it.toByteArray().size <= MAX_BYTES) }, false)
        }
        // Preferences never contain account fields, even with credentials requested by an old caller.
        val incoming = obj(source)
        if (mode == "preferences") return fit(source, incoming.filterKeys {
            it in portable || it in hardware && it !in profileKeys || it in setOf("schemaVersion", "exportedAtEpochMs")
        }.toMutableMap().apply { put("extras", preparedExtras(incoming, mode)) }, presentFields)
        val allowed = accounts + if (mode == "settings") portable + hardware else emptySet()
        val s = incoming.filterKeys { it in allowed }.toMutableMap()
        s["extras"] = preparedExtras(incoming, mode, credentials)
        if (!sessions) {
            for (field in listOf("servers", "jellyfinServers", "embyServers")) s[field] = JsonArray(s.getValue(field).jsonArray.map {
                JsonObject(it.jsonObject.toMutableMap().apply { put("token", JsonNull) })
            })
            s["smartTubeAccounts"]?.takeUnless { it == JsonNull }?.let { accounts ->
                s["smartTubeAccounts"] = JsonArray(accounts.jsonArray.map { JsonObject(it.jsonObject + ("credential" to JsonNull)) })
            }
            s["spotifyAccount"]?.takeUnless { it == JsonNull }?.let {
                s["spotifyAccount"] = JsonObject(it.jsonObject + mapOf("accessToken" to JsonNull, "refreshToken" to JsonNull))
            }
        }
        if (!credentials) {
            for (field in listOf("jellyfinServers", "embyServers")) s[field] = JsonArray(s.getValue(field).jsonArray.map {
                JsonObject(it.jsonObject.toMutableMap().apply { put("password", JsonNull) })
            })
            s["smbPasswords"] = JsonObject(emptyMap()); s["sshKeys"] = JsonObject(emptyMap()); s["tvheadendPassword"] = JsonNull; s["tmdbApiKey"] = JsonNull
            // Like Telegram's native session, its API application keys never travel in a
            // companion transfer unless the user explicitly selected credentials.
            s["monogramApiCredentials"] = JsonNull
            s.remove("wireGuardPrivateKey"); s.remove("wireGuardPresharedKey")
            // Playlist URLs carry the provider account; the OpenSubtitles key is the user's own.
            for (key in listOf("iptvSources", "openSubtitlesApiKey", "openSubtitlesUsername", "openSubtitlesPassword")) s[key] = JsonNull
        }
        // Per-source VPN routing and local paths are never portable, even when credentials are selected.
        s["fileSources"] = JsonArray(s.getValue("fileSources").jsonArray.filter { it.jsonObject.text("kind") != "local" }.map {
            JsonObject(it.jsonObject - "wireGuard")
        })
        // SAF document IDs/permissions are device-specific. Only include game folders
        // whose NAS source actually travels with this Companion payload.
        val portableSourceIds = s.getValue("fileSources").jsonArray.map { it.jsonObject.text("id") }.toSet()
        // SAF document IDs/permissions are device-specific: both folder links and per-ROM
        // state travel only when the network FileSource also travels with this payload.
        (s["extras"] as? JsonObject)?.let { extras ->
            (extras["portable"] as? JsonObject)?.let { portable ->
                val folders = (portable["gameLibraryFolders"] as? JsonArray).orEmpty()
                    .map { it.jsonObject }
                    .filter { it.text("sourceId") in portableSourceIds }
                    .take(200)
                val games = (portable["gameLibraryOverrides"] as? JsonArray).orEmpty()
                    .map { it.jsonObject }
                    .filter { it.text("sourceId") in portableSourceIds }
                    .takeLast(1000)
                s["extras"] = JsonObject(extras + ("portable" to JsonObject(portable + mapOf(
                    "gameLibraryFolders" to JsonArray(folders),
                    "gameLibraryOverrides" to JsonArray(games),
                ))))
            }
        }
        return fit(source, s, presentFields)
    }

    /** Companion payload plus whether title/scope profiles had to stay on this TV to fit [MAX_BYTES]. */
    data class Prepared(val json: String, val profilesOmitted: Boolean)

    fun prepare(source: SettingsBackup, credentials: Boolean, mode: String = "settings", sessions: Boolean = credentials,
        presentFields: Set<String>? = null): String = prepareWithInfo(source, credentials, mode, sessions, presentFields).json

    /**
     * Title and season/series/collection profiles travel compactly (no nulls, no default values). If the
     * payload still exceeds [MAX_BYTES], they are left out instead of failing the transfer; the receiver
     * keeps its own (see [merge]) and the sender shows a hint.
     */
    private fun fit(source: SettingsBackup, fields: MutableMap<String, JsonElement>, presentFields: Set<String>?): Prepared {
        // Accent styles predate a dedicated schema bump. An older file's missing style must
        // remain missing through both sanitization passes instead of acquiring today's default.
        if (presentFields != null) {
            (profileKeys + "accentStyleKey" + sinceSchema80 + "wakeOnOpenSystems").filterNot { it in presentFields }.forEach(fields::remove)
        } else if (source.schemaVersion < 80) {
            // These fields already existed in file backups. An old decoded model alone cannot
            // tell an explicit file value from a default filled in for an older Companion sender.
            sinceSchema80.forEach(fields::remove)
        }
        if (source.schemaVersion < 82) fields.remove("wakeOnOpenSystems")
        if ("titleVideoSettings" in fields) fields["titleVideoSettings"] = compact.encodeToJsonElement(source.titleVideoSettings)
        if ("scopedVideoSettings" in fields) fields["scopedVideoSettings"] = compact.encodeToJsonElement(source.scopedVideoSettings)
        val full = JsonObject(fields).toString()
        if (full.toByteArray().size <= MAX_BYTES) return Prepared(full, false)
        require(profileKeys.any { it in fields }) { "Companion payload too large" }
        return Prepared(JsonObject(fields - profileKeys).toString().also { require(it.toByteArray().size <= MAX_BYTES) }, true)
    }

    fun merge(source: SettingsBackup, target: SettingsBackup, mode: String, overwrite: Boolean, deviceSettings: Boolean,
        presentFields: Set<String>? = null): String {
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
                .filter { source.schemaVersion >= 77 || it != "uiRenderResolutionKey" }
                .filter { source.schemaVersion >= 78 || it != "accentMotionKey" }
                .filter { it != "accentStyleKey" || presentFields == null || it in presentFields }
                .filter { source.schemaVersion >= 72 || it != "introAnimationKey" }
                .filter { it !in sinceSchema80 || (presentFields?.contains(it) ?: (source.schemaVersion >= 80)) }
                .filter { it != "wakeOnOpenSystems" || (presentFields?.contains(it) ?: (source.schemaVersion >= 82)) }
                // A settings-only transfer has no account identities to map local profile keys.
                .filter { mode != "preferences" || it !in profileKeys }
                // A sender from before the clock page has no clock: the receiver keeps its own.
                .filter { it != "clock" || source.clock != null }
                .filter { it != "weather" || source.weather != null }
                .filter { it != "subtitleAppearance" || source.subtitleAppearance != null }
                .filter { it != "nightSchedule" || source.nightSchedule != null }
                .filter { it != "playerOsdModeIconVisible" || source.playerOsdModeIconVisible != null }
                .filter { it != "homeRows" || source.homeRows != null }
                .filter { it != "watchedThresholdPercent" || source.watchedThresholdPercent != null }
                .filter { it != "resumeIgnoreEndPercent" || source.resumeIgnoreEndPercent != null }
                // Profiles left out by the sender (too large) arrive empty: the TV keeps its own.
                .filter { it !in profileKeys || profilePresent(source, it, presentFields) }
                .forEach { key -> incoming[key]?.let { result[key] = it } }
            if (source.schemaVersion >= 80)
                mergeExtras(incoming, result, setOf("portable") + if (deviceSettings) setOf("device") else emptySet())
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
                val id = oldProfile?.text("id") ?: run {
                    val used = values.map { (if (nested) it.getValue("profile").jsonObject else it).text("id") }.toSet()
                    if (sourceId !in used) sourceId else {
                        val base = "remote-${fingerprint(identity(profile, field).toByteArray()).take(24)}"
                        var candidate = base
                        var suffix = 1
                        // A previous imported endpoint can have been edited locally; its generated
                        // identity still belongs to it and must not be reused by another account.
                        while (candidate in used) candidate = "$base-${suffix++}"
                        candidate
                    }
                }
                val newProfile = JsonObject(profile.toMutableMap().apply {
                    put("id", JsonPrimitive(id))
                    // Routing belongs to the receiving device, including credential/hardware imports.
                    if (field == "fileSources") put("wireGuard", oldProfile?.get("wireGuard") ?: JsonPrimitive(false))
                    if (field == "fileSources") {
                        // Merging a Games-only share must never hide a receiver's existing
                        // video/Files share. Existing visible sources remain visible.
                        val existingVisible = oldProfile?.get("showInFiles")?.jsonPrimitive?.booleanOrNull ?: (oldProfile != null)
                        val incomingVisible = profile["showInFiles"]?.jsonPrimitive?.booleanOrNull ?: true
                        put("showInFiles", JsonPrimitive(existingVisible || incomingVisible))
                    }
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
        // Remap ROM folder references after the source identities have been reconciled.
        // Both full backups and Companion store source-linked Games fields in Extras,
        // keeping SettingsBackup's generated JVM signatures within the 255-slot limit.
        val currentPortable = ((current["extras"] as? JsonObject)?.get("portable") as? JsonObject)
        val incomingPortable = ((incoming["extras"] as? JsonObject)?.get("portable") as? JsonObject)
        val gameFolders = (currentPortable?.get("gameLibraryFolders") as? JsonArray).orEmpty()
            .map { it.jsonObject }.toMutableList()
        val validSources = (result["fileSources"] as? JsonArray).orEmpty()
            .map { it.jsonObject.text("id") }.toSet()
        val sourceIds = mappedIds["fileSources"].orEmpty()
        if (mode == "settings") {
            for (folder in (incomingPortable?.get("gameLibraryFolders") as? JsonArray).orEmpty()
                .map { it.jsonObject }) {
                val mappedSource = sourceIds[folder.text("sourceId")] ?: continue
                if (mappedSource !in validSources) continue
                val path = folder.text("path")
                val system = folder.text("systemId")
                if (path.length > 4096 || folder.text("id").isBlank() || system.isBlank()) continue
                val existingIndex = gameFolders.indexOfFirst {
                    it.text("sourceId") == mappedSource &&
                        it.text("path") == path && it.text("systemId") == system
                }
                if (existingIndex < 0 && gameFolders.size >= 200) continue
                val newFolder = JsonObject(folder + ("sourceId" to JsonPrimitive(mappedSource)))
                if (existingIndex >= 0) {
                    if (overwrite) gameFolders[existingIndex] = JsonObject(newFolder +
                        ("id" to JsonPrimitive(gameFolders[existingIndex].text("id"))))
                } else {
                    val usedIds = gameFolders.map { it.text("id") }.toSet()
                    val id = if (folder.text("id") !in usedIds) folder.text("id")
                        else "remote-" + fingerprint((mappedSource + path + system).toByteArray()).take(24)
                    gameFolders += JsonObject(newFolder + ("id" to JsonPrimitive(id)))
                }
            }
            val extras = (result["extras"] as? JsonObject).orEmpty()
            val portable = (extras["portable"] as? JsonObject).orEmpty()
            result["extras"] = JsonObject(extras + ("portable" to JsonObject(
                portable + ("gameLibraryFolders" to JsonArray(gameFolders))
            )))
        }
        // Move source-linked metadata through the same sender -> receiver FileSource map.
        // A TV's local game metadata survives an unrelated sender and local SAF paths never sync.
        if (mode == "settings") {
            val receiverExtras = current["extras"] as? JsonObject
            val receiverPortable = receiverExtras?.get("portable") as? JsonObject
            val incomingExtras = incoming["extras"] as? JsonObject
            val incomingPortable = incomingExtras?.get("portable") as? JsonObject
            val existing = (receiverPortable?.get("gameLibraryOverrides") as? JsonArray).orEmpty()
                .map { it.jsonObject }.toMutableList()
            val imported = (incomingPortable?.get("gameLibraryOverrides") as? JsonArray).orEmpty()
            for (item in imported.map { it.jsonObject }) {
                val sourceId = sourceIds[item.text("sourceId")] ?: continue
                if (sourceId !in validSources) continue
                val path = item.text("path")
                if (path.length > 4096 || path.isBlank()) continue
                val mapped = JsonObject(item + ("sourceId" to JsonPrimitive(sourceId)))
                val currentIndex = existing.indexOfFirst {
                    it.text("sourceId") == sourceId && it.text("path") == path
                }
                if (currentIndex >= 0) {
                    if (overwrite) existing[currentIndex] = mapped
                } else if (existing.size < 5000) {
                    existing += mapped
                }
            }
            val resultExtras = (result["extras"] as? JsonObject).orEmpty()
            val resultPortable = (resultExtras["portable"] as? JsonObject).orEmpty()
            result["extras"] = JsonObject(resultExtras + ("portable" to JsonObject(
                resultPortable + ("gameLibraryOverrides" to JsonArray(existing.takeLast(5000)))
            )))
        }
        if (mode == "settings" && deviceSettings && source.schemaVersion >= 69) {
            val profiles = remapCompanionVideoProfiles(source, mappedIds, target)
            if (profilePresent(source, "titleVideoSettings", presentFields))
                result["titleVideoSettings"] = json.encodeToJsonElement(profiles.titles)
            if (profilePresent(source, "scopedVideoSettings", presentFields))
                result["scopedVideoSettings"] = json.encodeToJsonElement(profiles.scopes)
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
            if (source.schemaVersion >= 80) mergeExtras(incoming, result, setOf("connections"))
        }
        if (incoming["tmdbApiKey"] != JsonNull && (overwrite || current["tmdbApiKey"] == JsonNull)) result["tmdbApiKey"] = incoming.getValue("tmdbApiKey")
        // Adopt a Monogram API application identity only on a fresh receiver. Never change
        // an existing ID/hash behind a live MTProto session, even with account overwrite.
        if (incoming.optional("monogramApiCredentials") != null &&
            current.optional("monogramApiCredentials") == null) {
            result["monogramApiCredentials"] = incoming.getValue("monogramApiCredentials")
        }
        // Optional game provider identities are independent. Missing fields preserve the
        // receiver; each ScreenScraper pair travels atomically, including its password.
        val incomingGameKeys = source.extras?.credentials?.gameMetadata
        if (incomingGameKeys != null) {
            val existing = target.extras?.credentials?.gameMetadata
                ?: com.ambient.tv.games.metadata.GameMetadataCredentials()
            val copyDev = incomingGameKeys.screenScraperDevId != null && (overwrite || existing.screenScraperDevId == null)
            val copyUser = incomingGameKeys.screenScraperUser != null && (overwrite || existing.screenScraperUser == null)
            val copyGamesDb = incomingGameKeys.theGamesDbApiKey != null && (overwrite || existing.theGamesDbApiKey == null)
            val merged = existing.copy(
                screenScraperDevId = if (copyDev) incomingGameKeys.screenScraperDevId else existing.screenScraperDevId,
                screenScraperDevPassword = if (copyDev) incomingGameKeys.screenScraperDevPassword else existing.screenScraperDevPassword,
                screenScraperUser = if (copyUser) incomingGameKeys.screenScraperUser else existing.screenScraperUser,
                screenScraperPassword = if (copyUser) incomingGameKeys.screenScraperPassword else existing.screenScraperPassword,
                theGamesDbApiKey = if (copyGamesDb) incomingGameKeys.theGamesDbApiKey else existing.theGamesDbApiKey,
            ).validated()
            val extras = (result["extras"] as? JsonObject).orEmpty()
            val credentialGroup = (extras["credentials"] as? JsonObject).orEmpty()
            result["extras"] = JsonObject(extras + ("credentials" to JsonObject(
                credentialGroup + ("gameMetadata" to json.encodeToJsonElement(merged))
            )))
        }
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
