package com.ambient.tv.data.local

import com.ambient.tv.domain.model.ServerProfile
import com.ambient.tv.domain.model.JellyfinServerProfile
import com.ambient.tv.domain.model.EmbyServerProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.ambient.tv.vpn.WireGuardProfile
import com.ambient.tv.ui.theme.normalizedAccentKey

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class SettingsBackup(
    val schemaVersion: Int = 67,
    val trailersEnabled: Boolean = true,
    val trailerLanguage: String = "auto",
    val tmdbApiKey: String? = null,
    val exportedAtEpochMs: Long,
    val activeServerId: String? = null,
    val filesEnabled: Boolean = false,
    val fileSources: List<com.ambient.tv.data.files.FileSource> = emptyList(),
    val dlnaServers: List<com.ambient.tv.data.dlna.DlnaServer> = emptyList(),
    val smbPasswords: Map<String, String> = emptyMap(),
    /** SFTP private keys by source id; only in exports that include credentials. */
    val sshKeys: Map<String, String> = emptyMap(),
    val plexEnabled: Boolean = true,
    val youtubeEnabled: Boolean = false,
    val spotifyEnabled: Boolean = false,
    val smartTubeAccounts: List<SmartTubeAccountBackup>? = null,
    val spotifyAccount: SpotifyAccountBackup? = null,
    val spotifyClientId: String = "",
    val spotifyDeviceName: String = com.ambient.tv.branding.AppBrand.name,
    val spotifyBitrateKbps: Int = 320,
    val spotifyVisualizerModeKey: String = "cover",
    val servers: List<ServerBackup> = emptyList(),
    val activeJellyfinServerId: String? = null,
    val jellyfinEnabled: Boolean = false,
    val jellyfinServers: List<JellyfinServerBackup> = emptyList(),
    val jellyfinHiddenSectionKeys: Set<String> = emptySet(),
    val activeEmbyServerId: String? = null,
    val embyEnabled: Boolean = false,
    val embyServers: List<EmbyServerBackup> = emptyList(),
    val embyHiddenSectionKeys: Set<String> = emptySet(),
    val uiLanguageKey: String = LanguagePreferences.AUTO,
    val metadataLanguageKey: String = LanguagePreferences.AUTO,
    val audioLanguageKey: String = LanguagePreferences.AUTO,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val subtitlesEnabled: Boolean = false,
    val forcedSubtitlesEnabled: Boolean = false,
    val subtitleModeKey: String? = null,
    val subtitleLanguageKey: String? = null,
    val subtitleFallbackLanguageKey: String = "none",
    val fullSubtitlesOnForeignAudio: Boolean = false,
    val forcedSubtitlesInUiLanguage: Boolean = false,
    val chooseServerOnStartup: Boolean = true,
    val localNetworkOnly: Boolean = true,
    val plexThemeMusicEnabled: Boolean = false,
    val jellyfinThemeMusicEnabled: Boolean = false,
    val embyThemeMusicEnabled: Boolean = false,
    val themeMusicVolume: Float = AppPreferences.DEFAULT_THEME_MUSIC_VOLUME,
    val autoSelectDisplayVersion: Boolean = true,
    val displayChangeNotificationsEnabled: Boolean = false,
    val svpAvailable: Boolean = false,
    val displayRefreshAdjustmentKey: String = AppPreferences.DISPLAY_REFRESH_START_STOP,
    val displayModeWhitelist: Set<String> = emptySet(),
    val preferredDisplayModeKey: String = "system",
    val allowDoubleRefreshRate: Boolean = true,
    val allowThreeTwoPulldown: Boolean = true,
    val displaySwitchPauseMs: Int = 0,
    val syncPlaybackToDisplay: Boolean = false,
    val mediaSelectionBehaviorKey: String = MediaSelectionPreferences.DETAILS,
    val showLibraryRowsOnHome: Boolean = false,
    @SerialName("showContinueWatchingHub") val showContinueWatchingRow: Boolean = true,
    @SerialName("showRecentHomeHubs") val showRecentHomeRows: Boolean = true,
    @SerialName("showCollectionHomeHubs") val showCollectionHomeRows: Boolean = true,
    val plexAutoRefreshIntervalSeconds: Int = 60,
    val libraryGridColumns: Int = 8,
    val libraryListRows: Int = 7,
    val highQualityAssetsEnabled: Boolean = false,
    val uiScale: Float = 1f,
    val overscanPercent: Int = 0,
    val accentColorKey: String = "gold",
    val customAccentHex: String = "#E5A00D",
    val customPlaybackAccentHex: String? = null,
    val dualAccentEnabled: Boolean = false,
    val themeModeKey: String = "dark",
    val uiHdrModeKey: String = "auto",
    val sdrToHdrEnabled: Boolean = false,
    val sdrToHdrPeakNits: Int = 0,
    val performanceModeKey: String = "auto",
    val uiPerformanceModeKey: String? = null,
    val playbackPerformanceModeKey: String? = null,
    val frameDropTestSeconds: Int = 0,
    val homeBackgroundEverywhere: Boolean = false,
    val focusArtworkBackdrop: Boolean = true,
    val homeHeroStyleKey: String = HomeHeroStyle.DEFAULT,
    val homeHeroIntensityPercent: Int = HomeHeroStyle.DEFAULT_INTENSITY,
    val homeHeroSpreadKey: String = HomeHeroStyle.DEFAULT_SPREAD,
    val notificationDisplayDurationKey: String = "normal",
    val navSoundsEnabled: Boolean = false,
    val navSoundsVolume: Float = 0.3f,
    val navSoundBank: String = "",
    val playbackBackendKey: String = "auto",
    val playerTechBadgesVisible: Boolean = true,
    val tvheadendPlaybackBackendKey: String? = null,
    val mpvVideoOutputModeKey: String = "direct",
    val mpvVideoDecoderKey: String = "auto",
    val vlcVideoOutputModeKey: String = "auto",
    val visibleBackgroundVideoPlayback: Boolean = false,
    val backgroundVideoDimPercent: Int = 75,
    val mediaAudioPassthroughEnabled: Boolean = false,
    /** Legacy schema ≤43; import ORs into [mediaAudioPassthroughEnabled]. Not re-exported. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val plexAudioPassthroughEnabled: Boolean = false,
    /** Legacy schema ≤43; import ORs into [mediaAudioPassthroughEnabled]. Not re-exported. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tvheadendAudioPassthroughEnabled: Boolean = false,
    val audioVisualizerModeKey: String = "projectm",
    val projectmPresetIntervalSeconds: Int = 60,
    val projectmTransitionSeconds: Int = 2,
    val projectmQualityKey: String = "balanced",
    val projectmResolutionKey: String = "auto",
    val projectmHdr4kAllowed: Boolean = false,
    val projectmPresetCategoryKey: String = "all",
    val bigSeekJumpSeconds: Int = 60,
    val youtubeSponsorBlockEnabled: Boolean = true,
    val youtubeFeedKeys: List<String> = AppPreferences.DEFAULT_YOUTUBE_FEEDS,
    val youtubeMaxVideoHeight: Int = 0,
    val youtubeVideoPreset: String = "auto",
    val screensaverEnabled: Boolean = true,
    val screensaverTimeoutMinutes: Int = 10,
    val screensaverModeKey: String = "ambient",
    val screensaverShowClock: Boolean = true,
    val screensaverShowDate: Boolean = true,
    val screensaverUse24HourClock: Boolean = true,
    val screensaverUseArtwork: Boolean = true,
    val screensaverArtworkIntervalSeconds: Int = 30,
    val screensaverDimPercent: Int = 70,
    val screensaverQualityKey: String = "balanced",
    // Photo viewer / slideshow and the photo screensaver source (defaults match AppPreferences).
    val photoSlideshowIntervalSeconds: Int = 6,
    val photoTransitionKey: String = "fade",
    val photoKenBurns: Boolean = false,
    val photoShuffle: Boolean = false,
    val photoIncludeSubfolders: Boolean = false,
    val photoSortKey: String = "date_desc",
    val photoShowInfo: Boolean = false,
    val screensaverPhotoAlbumKey: String = "",
    val screensaverPhotoAlbumTitle: String = "",
    val tvheadendEnabled: Boolean = false,
    val tvheadendHost: String = "",
    val tvheadendPort: Int = 9981,
    val tvheadendUseHttps: Boolean = false,
    val tvheadendUsername: String = "",
    val tvheadendPassword: String? = null,
    val tvheadendStreamProfile: String = "",
    val tvheadendMacAddress: String? = null,
    val tvheadendAutoWakeEnabled: Boolean = true,
    val tvheadendShowOnHome: Boolean = true,
    val tvheadendDeinterlaceModeKey: String = "off",
    val tvheadendDeinterlaceFilterKey: String = "auto",
    val tvheadendMpvDeinterlaceFilterKey: String? = null,
    val sidebarSystemOrder: List<String> = listOf("plex", "jellyfin", "emby", "youtube", "spotify", "tvheadend"),
    val systemNames: Map<String, String> = emptyMap(),
    /** Older backups have no dividers; the default keeps them loading unchanged. */
    val sidebarDividersAfter: List<String> = emptyList(),
    val sidebarEntryOrder: Map<String, List<String>> = emptyMap(),
    val sidebarEntryDividers: Map<String, List<String>> = emptyMap(),
    val spotifySubmenuKeys: List<String> = AppPreferences.DEFAULT_SPOTIFY_SUBMENU_KEYS,
    val tvheadendSubmenuKeys: List<String> = AppPreferences.DEFAULT_TVH_SUBMENU_KEYS,
    val plexHiddenSectionKeys: Set<String> = emptySet(),
    val sidebarSettingsShortcutHidden: Set<String> = emptySet(),
    val audioSettings: AudioSettings? = null,
    val mpvImageOptions: MpvImageOptions? = null,
    val vlcImageOptions: VlcImageOptions? = null,
    val media3ImageOptions: Media3ImageOptions? = null,
    val resolutionVideoProfiles: Map<String, ResolutionVideoProfile> = emptyMap(),
    /** Per-title picture/audio choices since schema 69. */
    val titleVideoSettings: Map<String, TitleVideoSettings> = emptyMap(),
    /** Season/series/collection choices since schema 69. */
    val scopedVideoSettings: Map<String, TitleVideoSettings> = emptyMap(),
    val mpvScalingFilterKey: String = "lanczos",
    val mpvDebandEnabled: Boolean = true,
    val vlcScalingFilterKey: String = "bilinear",
    val youtubeAutoplayNextEnabled: Boolean = false,
    val youtubeMediaSelectionBehaviorKey: String = MediaSelectionPreferences.PLAY,
    val introSkipMode: String = "button",
    val creditsSkipMode: String = "button",
    val nextUpEnabled: Boolean = true,
    val nextUpLeadSeconds: Int = 30,
    val nextUpCreditsDelaySeconds: Int = 0,
    val nextUpAutoplayEpisodes: Boolean = false,
    val nextUpAutoplayCollections: Boolean = false,
    val nextUpOverlayEnabled: Boolean = true,
    val nextUpStillWatchingEpisodes: Int = 3,
    val sleepAutoStartKey: String = AppPreferences.SLEEP_AUTO_START_OFF,
    val sleepWarningMinutes: Int = AppPreferences.SLEEP_WARNING_DEFAULT,
    val sleepDailyStopMinute: Int = AppPreferences.SLEEP_DAILY_STOP_OFF,
    /** Since schema 64. */
    val mpvHdrGpuEnabled: Boolean = false,
    /** SDR→HDR reference white in nits (0 = mpv/renderer default 203). Since schema 65. */
    val sdrToHdrReferenceWhiteNits: Int = 0,
    /** 100/120 Hz doubling opt-in, GPU render resolution, MPV SDR curve, explicit SVP choice. Since schema 66. */
    val allowHighRefreshDoubling: Boolean = false,
    val gpuRenderResolutionKey: String = AppPreferences.GPU_RENDER_AUTO,
    val mpvSdrCurveKey: String = AppPreferences.MPV_SDR_CURVE_AUTO,
    val svpExplicitChoice: Boolean = false,
    /** Since schema 68: MPV scaler/deband picked deliberately, so cheap defaults do not replace it. */
    val mpvImageExplicitChoice: Boolean = false,
    /** Since schema 71: double rate preferred over the native one. */
    val preferDoubleRefreshRate: Boolean = false,
    /** Since schema 72. */
    val introAnimationKey: String = IntroAnimation.DEFAULT,
    val wolRetryCount: Int = com.ambient.tv.wol.WOL_RETRY_COUNT_DEFAULT,
    val homeRows: List<HomeRowDefinition>? = null,
    val systemHomeHiddenRows: Set<String> = AppPreferences.DEFAULT_SYSTEM_HOME_HIDDEN_ROWS,
    val libraryPreferences: Map<String, String> = emptyMap(),
    val recentSearches: List<String> = emptyList(),
    val wireGuardEnabled: Boolean = false,
    val wireGuardPlexEnabled: Boolean = false,
    val wireGuardJellyfinEnabled: Boolean = false,
    val wireGuardEmbyEnabled: Boolean = false,
    val wireGuardTvheadendEnabled: Boolean = false,
    val wireGuardProfile: WireGuardProfile? = null,
    val wireGuardPrivateKey: String? = null,
    val wireGuardPresharedKey: String? = null,
    /**
     * IPTV playlists; only in exports with credentials, because providers put the account into the
     * address. null: not part of this backup, the receiver keeps its own playlists.
     */
    val iptvSources: List<com.ambient.tv.data.iptv.IptvSource>? = null,
    /** OpenSubtitles.com key and account; only in exports with credentials (null: keep the receiver's). */
    val openSubtitlesApiKey: String? = null,
    val openSubtitlesUsername: String? = null,
    val openSubtitlesPassword: String? = null,
    val liveTimeshiftMb: Int = 1024,
    val liveTimeshiftEnabled: Boolean = false,
    val liveDirectRecordEnabled: Boolean = false,
    val liveTvReminderLeadMinutes: Int = 2,
    val playerOsdSeconds: Int = 5,
)

@Serializable
data class ServerBackup(
    val profile: ServerProfile,
    val token: String? = null,
)

@Serializable
data class JellyfinServerBackup(
    val profile: JellyfinServerProfile,
    val token: String? = null,
    val password: String? = null,
)

@Serializable
data class EmbyServerBackup(
    val profile: EmbyServerProfile,
    val token: String? = null,
    val password: String? = null,
)

@Singleton
class SettingsBackupManager @Inject constructor(
    private val preferences: AppPreferences,
    private val tokenStore: TokenStore,
    private val dlnaStore: com.ambient.tv.data.dlna.DlnaStore?,
    private val smartTube: com.ambient.tv.youtube.SmartTubeEngine? = null,
    /** Live TV reminders keep their lead time in their own preferences file (read by the alarm receiver). */
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context? = null,
) {
    /** Kept for pure preference tests and small tools that do not install Hilt. */
    constructor(preferences: AppPreferences, tokenStore: TokenStore) : this(preferences, tokenStore, null)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun exportJson(includeCredentials: Boolean = false): String {
        val profiles = preferences.serverProfiles
        val (fileSources, smbPasswords, sshKeys) = synchronized(preferences.fileSourceLock) {
            val sources = preferences.fileSources
            val passwords = if (includeCredentials) sources.filter { it.usesPassword }.mapNotNull { source ->
                tokenStore.smbPasswordForSource(source.id)?.let { source.id to it }
            }.toMap() else emptyMap()
            val keys = if (includeCredentials) sources.filter { it.kind == com.ambient.tv.data.files.FileKinds.SFTP }.mapNotNull { source ->
                tokenStore.sshKeyForSource(source.id)?.let { source.id to it }
            }.toMap() else emptyMap()
            Triple(sources, passwords, keys)
        }
        val backup = SettingsBackup(
            trailersEnabled = preferences.trailersEnabled,
            trailerLanguage = preferences.trailerLanguage,
            tmdbApiKey = if (includeCredentials) tokenStore.tmdbApiKey else null,
            schemaVersion = CURRENT_SCHEMA_VERSION,
            exportedAtEpochMs = System.currentTimeMillis(),
            activeServerId = preferences.activeServerId,
            filesEnabled = preferences.filesEnabled,
            fileSources = fileSources,
            dlnaServers = dlnaStore?.servers().orEmpty(),
            smbPasswords = smbPasswords,
            sshKeys = sshKeys,
            plexEnabled = preferences.plexEnabled,
            youtubeEnabled = preferences.youtubeEnabled,
            smartTubeAccounts = smartTube?.exportAccounts(includeCredentials),
            spotifyAccount = SpotifyAccountBackup(
                clientId = tokenStore.spotifyWebClientId.orEmpty(),
                name = tokenStore.spotifyAccountName.orEmpty(),
                accessToken = if (includeCredentials) tokenStore.spotifyWebAccessToken else null,
                refreshToken = if (includeCredentials) tokenStore.spotifyWebRefreshToken else null,
                expiresAtMs = tokenStore.spotifyWebAccessTokenExpiresAtMs,
                scopes = tokenStore.spotifyWebScopes.orEmpty(),
            ),
            spotifyEnabled = preferences.spotifyEnabled,
            spotifyClientId = preferences.spotifyClientId,
            spotifyDeviceName = preferences.spotifyDeviceName,
            spotifyBitrateKbps = preferences.spotifyBitrateKbps,
            spotifyVisualizerModeKey = preferences.spotifyVisualizerModeKey,
            servers = profiles.map { profile ->
                ServerBackup(profile = profile, token = if (includeCredentials) tokenStore.tokenForServer(profile.id) else null)
            },
            activeJellyfinServerId = preferences.activeJellyfinServerId,
            jellyfinEnabled = preferences.jellyfinEnabled,
            jellyfinServers = preferences.jellyfinServerProfiles.map { profile ->
                JellyfinServerBackup(
                    profile = profile,
                    token = if (includeCredentials) tokenStore.jellyfinTokenForServer(profile.id) else null,
                    password = if (includeCredentials) tokenStore.jellyfinPasswordForServer(profile.id) else null,
                )
            },
            jellyfinHiddenSectionKeys = preferences.jellyfinHiddenSectionKeys,
            activeEmbyServerId = preferences.activeEmbyServerId,
            embyEnabled = preferences.embyEnabled,
            embyServers = preferences.embyServerProfiles.map { profile ->
                EmbyServerBackup(
                    profile = profile,
                    token = if (includeCredentials) tokenStore.embyTokenForServer(profile.id) else null,
                    password = if (includeCredentials) tokenStore.embyPasswordForServer(profile.id) else null,
                )
            },
            embyHiddenSectionKeys = preferences.embyHiddenSectionKeys,
            uiLanguageKey = preferences.uiLanguageKey,
            metadataLanguageKey = preferences.metadataLanguageKey,
            audioLanguageKey = preferences.audioLanguageKey,
            preferredAudioLanguage = preferences.preferredAudioLanguage,
            preferredSubtitleLanguage = preferences.preferredSubtitleLanguage,
            subtitlesEnabled = preferences.subtitlesEnabled,
            forcedSubtitlesEnabled = preferences.forcedSubtitlesEnabled,
            subtitleModeKey = preferences.subtitleModeKey,
            subtitleLanguageKey = preferences.subtitleLanguageKey,
            subtitleFallbackLanguageKey = preferences.subtitleFallbackLanguageKey,
            fullSubtitlesOnForeignAudio = preferences.fullSubtitlesOnForeignAudio,
            forcedSubtitlesInUiLanguage = preferences.forcedSubtitlesInUiLanguage,
            chooseServerOnStartup = preferences.chooseServerOnStartup,
            localNetworkOnly = preferences.localNetworkOnly,
            plexThemeMusicEnabled = preferences.plexThemeMusicEnabled,
            jellyfinThemeMusicEnabled = preferences.jellyfinThemeMusicEnabled,
            embyThemeMusicEnabled = preferences.embyThemeMusicEnabled,
            themeMusicVolume = preferences.themeMusicVolume,
            autoSelectDisplayVersion = preferences.autoSelectDisplayVersion,
            displayChangeNotificationsEnabled = preferences.displayChangeNotificationsEnabled,
            svpAvailable = preferences.svpAvailable,
            displayRefreshAdjustmentKey = preferences.displayRefreshAdjustmentKey,
            displayModeWhitelist = preferences.displayModeWhitelist,
            preferredDisplayModeKey = preferences.preferredDisplayModeKey,
            allowDoubleRefreshRate = preferences.allowDoubleRefreshRate,
            allowThreeTwoPulldown = preferences.allowThreeTwoPulldown,
            displaySwitchPauseMs = preferences.displaySwitchPauseMs,
            syncPlaybackToDisplay = preferences.syncPlaybackToDisplay,
            mediaSelectionBehaviorKey = preferences.mediaSelectionBehaviorKey,
            showLibraryRowsOnHome = preferences.showLibraryRowsOnHome,
            showContinueWatchingRow = preferences.showContinueWatchingRow,
            showRecentHomeRows = preferences.showRecentHomeRows,
            showCollectionHomeRows = preferences.showCollectionHomeRows,
            plexAutoRefreshIntervalSeconds = preferences.plexAutoRefreshIntervalSeconds,
            libraryGridColumns = preferences.libraryGridColumns,
            libraryListRows = preferences.libraryListRows,
            highQualityAssetsEnabled = preferences.highQualityAssetsEnabled,
            uiScale = preferences.uiScale,
            overscanPercent = preferences.overscanPercent,
            accentColorKey = preferences.accentColorKey,
            customAccentHex = preferences.customAccentHex,
            customPlaybackAccentHex = preferences.customPlaybackAccentHex,
            dualAccentEnabled = preferences.dualAccentEnabled,
            themeModeKey = preferences.themeModeKey,
            uiHdrModeKey = preferences.uiHdrModeKey,
            sdrToHdrEnabled = preferences.sdrToHdrEnabled,
        sdrToHdrPeakNits = preferences.sdrToHdrPeakNits,
            performanceModeKey = preferences.performanceModeKey,
            uiPerformanceModeKey = preferences.uiPerformanceModeKey,
            playbackPerformanceModeKey = preferences.playbackPerformanceModeKey,
            frameDropTestSeconds = preferences.frameDropTestSeconds,
            homeBackgroundEverywhere = preferences.homeBackgroundEverywhere,
            focusArtworkBackdrop = preferences.focusArtworkBackdrop,
            homeHeroStyleKey = preferences.homeHeroStyleKey,
            homeHeroIntensityPercent = preferences.homeHeroIntensityPercent,
            homeHeroSpreadKey = preferences.homeHeroSpreadKey,
            notificationDisplayDurationKey = preferences.notificationDisplayDurationKey,
            navSoundsEnabled = preferences.navSoundsEnabled,
            navSoundsVolume = preferences.navSoundsVolume,
            navSoundBank = preferences.navSoundBank,
            playbackBackendKey = preferences.playbackBackendKey,
            playerTechBadgesVisible = preferences.playerTechBadgesVisible,
            tvheadendPlaybackBackendKey = preferences.tvheadendPlaybackBackendKey,
            mpvVideoOutputModeKey = preferences.mpvVideoOutputModeKey,
            mpvVideoDecoderKey = preferences.mpvVideoDecoderKey,
            vlcVideoOutputModeKey = preferences.vlcVideoOutputModeKey,
            visibleBackgroundVideoPlayback = preferences.visibleBackgroundVideoPlayback,
            backgroundVideoDimPercent = preferences.backgroundVideoDimPercent,
            mediaAudioPassthroughEnabled = preferences.mediaAudioPassthroughEnabled,
            audioVisualizerModeKey = preferences.audioVisualizerModeKey,
            projectmPresetIntervalSeconds = preferences.projectmPresetIntervalSeconds,
            projectmTransitionSeconds = preferences.projectmTransitionSeconds,
            projectmQualityKey = preferences.projectmQualityKey,
            projectmResolutionKey = preferences.projectmResolutionKey,
            projectmHdr4kAllowed = preferences.projectmHdr4kAllowed,
            projectmPresetCategoryKey = preferences.projectmPresetCategoryKey,
            bigSeekJumpSeconds = preferences.bigSeekJumpSeconds,
            youtubeSponsorBlockEnabled = preferences.youtubeSponsorBlockEnabled,
            youtubeFeedKeys = preferences.youtubeFeedKeys,
            youtubeMaxVideoHeight = preferences.youtubeMaxVideoHeight,
            youtubeVideoPreset = preferences.youtubeVideoPreset,
            screensaverEnabled = preferences.screensaverEnabled,
            screensaverTimeoutMinutes = preferences.screensaverTimeoutMinutes,
            screensaverModeKey = preferences.screensaverModeKey,
            screensaverShowClock = preferences.screensaverShowClock,
            screensaverShowDate = preferences.screensaverShowDate,
            screensaverUse24HourClock = preferences.screensaverUse24HourClock,
            screensaverUseArtwork = preferences.screensaverUseArtwork,
            screensaverArtworkIntervalSeconds = preferences.screensaverArtworkIntervalSeconds,
            screensaverDimPercent = preferences.screensaverDimPercent,
            screensaverQualityKey = preferences.screensaverQualityKey,
            photoSlideshowIntervalSeconds = preferences.photoSlideshowIntervalSeconds,
            photoTransitionKey = preferences.photoTransitionKey,
            photoKenBurns = preferences.photoKenBurns,
            photoShuffle = preferences.photoShuffle,
            photoIncludeSubfolders = preferences.photoIncludeSubfolders,
            photoSortKey = preferences.photoSortKey,
            photoShowInfo = preferences.photoShowInfo,
            screensaverPhotoAlbumKey = preferences.screensaverPhotoAlbumKey,
            screensaverPhotoAlbumTitle = preferences.screensaverPhotoAlbumTitle,
            tvheadendEnabled = preferences.tvheadendEnabled,
            tvheadendHost = preferences.tvheadendHost,
            tvheadendPort = preferences.tvheadendPort,
            tvheadendUseHttps = preferences.tvheadendUseHttps,
            tvheadendUsername = preferences.tvheadendUsername,
            tvheadendPassword = if (includeCredentials) tokenStore.tvheadendPassword else null,
            tvheadendStreamProfile = preferences.tvheadendStreamProfile,
            tvheadendMacAddress = preferences.tvheadendMacAddress,
            tvheadendAutoWakeEnabled = preferences.tvheadendAutoWakeEnabled,
            tvheadendShowOnHome = preferences.tvheadendShowOnHome,
            tvheadendDeinterlaceModeKey = preferences.tvheadendDeinterlaceModeKey,
            tvheadendDeinterlaceFilterKey = preferences.tvheadendDeinterlaceFilterKey,
            tvheadendMpvDeinterlaceFilterKey = preferences.tvheadendMpvDeinterlaceFilterKey,
            sidebarSystemOrder = preferences.sidebarSystemOrder,
            systemNames = preferences.systemNames,
            sidebarDividersAfter = preferences.sidebarDividersAfter.sorted(),
            sidebarEntryOrder = preferences.sidebarEntryOrder,
            sidebarEntryDividers = preferences.sidebarEntryDividers,
            spotifySubmenuKeys = preferences.spotifySubmenuKeys,
            tvheadendSubmenuKeys = preferences.tvheadendSubmenuKeys,
            plexHiddenSectionKeys = preferences.plexHiddenSectionKeys,
            sidebarSettingsShortcutHidden = preferences.sidebarSettingsShortcutHidden,
            audioSettings = preferences.audioSettings,
            mpvImageOptions = preferences.mpvImageOptions,
            vlcImageOptions = preferences.vlcImageOptions,
            media3ImageOptions = preferences.media3ImageOptions,
            resolutionVideoProfiles = preferences.resolutionVideoProfiles,
            titleVideoSettings = preferences.titleVideoSettings,
            scopedVideoSettings = preferences.scopedVideoSettings,
            mpvScalingFilterKey = preferences.mpvScalingFilterKey,
            mpvDebandEnabled = preferences.mpvDebandEnabled,
            vlcScalingFilterKey = preferences.vlcScalingFilterKey,
            youtubeAutoplayNextEnabled = preferences.youtubeAutoplayNextEnabled,
            youtubeMediaSelectionBehaviorKey = preferences.youtubeMediaSelectionBehaviorKey,
            introSkipMode = preferences.introSkipMode,
            creditsSkipMode = preferences.creditsSkipMode,
            nextUpEnabled = preferences.nextUpEnabled,
            nextUpLeadSeconds = preferences.nextUpLeadSeconds,
            nextUpCreditsDelaySeconds = preferences.nextUpCreditsDelaySeconds,
            nextUpAutoplayEpisodes = preferences.nextUpAutoplayEpisodes,
            nextUpAutoplayCollections = preferences.nextUpAutoplayCollections,
            nextUpOverlayEnabled = preferences.nextUpOverlayEnabled,
            nextUpStillWatchingEpisodes = preferences.nextUpStillWatchingEpisodes,
            sleepAutoStartKey = preferences.sleepAutoStartKey,
            sleepWarningMinutes = preferences.sleepWarningMinutes,
            sleepDailyStopMinute = preferences.sleepDailyStopMinute,
            mpvHdrGpuEnabled = preferences.mpvHdrGpuEnabled,
            sdrToHdrReferenceWhiteNits = preferences.sdrToHdrReferenceWhiteNits,
            allowHighRefreshDoubling = preferences.allowHighRefreshDoubling,
            gpuRenderResolutionKey = preferences.gpuRenderResolutionKey,
            mpvSdrCurveKey = preferences.mpvSdrCurveKey,
            svpExplicitChoice = preferences.svpExplicitChoice,
            mpvImageExplicitChoice = preferences.mpvImageExplicitChoice,
            preferDoubleRefreshRate = preferences.preferDoubleRefreshRate,
            introAnimationKey = preferences.introAnimationKey,
            wolRetryCount = preferences.wolRetryCount,
            homeRows = preferences.homeRows,
            systemHomeHiddenRows = preferences.systemHomeHiddenRows,
            libraryPreferences = preferences.portableLibraryPreferences(),
            recentSearches = preferences.recentSearches(),
            wireGuardEnabled = preferences.wireGuardEnabled,
            wireGuardPlexEnabled = preferences.wireGuardPlexEnabled,
            wireGuardJellyfinEnabled = preferences.wireGuardJellyfinEnabled,
            wireGuardEmbyEnabled = preferences.wireGuardEmbyEnabled,
            wireGuardTvheadendEnabled = preferences.wireGuardTvheadendEnabled,
            wireGuardProfile = preferences.wireGuardProfile(),
            wireGuardPrivateKey = if (includeCredentials) tokenStore.wireGuardPrivateKey else null,
            wireGuardPresharedKey = if (includeCredentials) tokenStore.wireGuardPresharedKey else null,
            iptvSources = if (includeCredentials) com.ambient.tv.data.iptv.IptvStore(tokenStore).sources() else null,
            openSubtitlesApiKey = if (includeCredentials) tokenStore.openSubtitlesApiKey else null,
            openSubtitlesUsername = if (includeCredentials) tokenStore.openSubtitlesUsername else null,
            openSubtitlesPassword = if (includeCredentials) tokenStore.openSubtitlesPassword else null,
            liveTimeshiftMb = preferences.liveTimeshiftMb,
            liveTimeshiftEnabled = preferences.liveTimeshiftEnabled,
            liveDirectRecordEnabled = preferences.liveDirectRecordEnabled,
            liveTvReminderLeadMinutes = context?.let { com.ambient.tv.livetv.LiveTvReminderScheduler.leadMinutes(it) } ?: 2,
            playerOsdSeconds = preferences.playerOsdSeconds,
        )
        return json.encodeToString(backup)
    }

    /** Parse and validate without writing preferences or credentials. */
    fun previewJson(raw: String): SettingsBackup {
        val backup = json.decodeFromString<SettingsBackup>(raw)
        require(backup.schemaVersion in 1..CURRENT_SCHEMA_VERSION) {
            "Unsupported settings backup schema ${backup.schemaVersion}"
        }
        validateAccountBackup(backup)
        backup.smartTubeAccounts?.let { smartTube?.validateAccounts(it) }
        backup.fileSources.filter { it.kind in com.ambient.tv.data.files.FileKinds.ALL }.forEach { it.validated() }
        backup.dlnaServers.forEach { it.validated() }
        backup.iptvSources?.let { sources ->
            require(sources.size <= com.ambient.tv.data.iptv.IptvStore.MAX_SOURCES && sources.distinctBy { it.id }.size == sources.size)
            sources.forEach { it.validated() }
        }
        return backup
    }

    /**
     * Imports [raw]. Credentials are only replaced when the backup carries them; a backup exported
     * without credentials keeps the local logins of the same servers. If applying fails midway,
     * the previous state (including credentials) is restored from a snapshot taken up front.
     */
    fun importJson(raw: String): SettingsBackup {
        val backup = previewJson(raw)
        val snapshot = runCatching { exportJson(includeCredentials = true) }.getOrNull()
        try {
            applyBackup(backup)
        } catch (error: Throwable) {
            if (snapshot != null) {
                runCatching { applyBackup(previewJson(snapshot)) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
        return backup
    }

    private fun applyBackup(backup: SettingsBackup) {
        // A source kind from a newer app version is skipped instead of failing the whole import;
        // a malformed source of a known kind still rejects the backup.
        val fileSources = backup.fileSources.filter { it.kind in com.ambient.tv.data.files.FileKinds.ALL }
            .map { it.validated() }.distinctBy { it.id }.take(50)
        val dlnaServers = backup.dlnaServers.map { it.validated() }.distinctBy { it.udn }.take(30)
        synchronized(preferences.fileSourceLock) {
            val previous = preferences.fileSources.associateBy { it.id }
            fun sameAccount(source: com.ambient.tv.data.files.FileSource) = previous[source.id]?.let { old ->
                old.kind == source.kind && old.host.equals(source.host, ignoreCase = true) &&
                    old.port == source.port && old.share == source.share && old.username == source.username
            } == true
            // Keep a local password/key only for the very same share and account when the backup has none.
            val keptIds = fileSources.filter { source ->
                source.usesPassword && backup.smbPasswords[source.id] == null && sameAccount(source)
            }.map { it.id }.toSet()
            val keptKeyIds = fileSources.filter { source ->
                source.kind == com.ambient.tv.data.files.FileKinds.SFTP && backup.sshKeys[source.id] == null && sameAccount(source)
            }.map { it.id }.toSet()
            previous.keys.filterNot { it in keptIds }.forEach { tokenStore.setSmbPassword(it, null) }
            previous.keys.filterNot { it in keptKeyIds }.forEach { tokenStore.setSshKey(it, null) }
            preferences.fileSources = fileSources
            preferences.filesEnabled = backup.filesEnabled
            fileSources.filter { it.usesPassword }.forEach { source ->
                backup.smbPasswords[source.id]?.let { tokenStore.setSmbPassword(source.id, it) }
            }
            fileSources.filter { it.kind == com.ambient.tv.data.files.FileKinds.SFTP }.forEach { source ->
                backup.sshKeys[source.id]?.let { tokenStore.setSshKey(source.id, it) }
            }
        }
        dlnaStore?.replace(dlnaServers)
        val profiles = backup.servers.map { it.profile }.distinctBy { it.id }
        val previousPlex = preferences.serverProfiles.associateBy { it.id }
        preferences.replaceServerProfiles(profiles, backup.activeServerId)
        // Keep a local Plex token only for the same server at the same address.
        tokenStore.retainOnlyServerTokens(profiles.filter { profile ->
            previousPlex[profile.id]?.let { old ->
                old.host.equals(profile.host, ignoreCase = true) && old.port == profile.port &&
                    old.useHttps == profile.useHttps && old.machineIdentifier == profile.machineIdentifier
            } == true
        }.map { it.id }.toSet())
        backup.servers.forEach { server -> server.token?.let { tokenStore.setTokenForServer(server.profile.id, it) } }
        val previousJellyfin = preferences.jellyfinServerProfiles.associateBy { it.id }
        val keptJellyfin = backup.jellyfinServers.filter { server ->
            server.token.isNullOrBlank() && previousJellyfin[server.profile.id]?.let { old ->
                old.host.equals(server.profile.host, ignoreCase = true) && old.port == server.profile.port &&
                    old.useHttps == server.profile.useHttps && old.basePath == server.profile.basePath && old.username == server.profile.username
            } == true && !tokenStore.jellyfinTokenForServer(server.profile.id).isNullOrBlank()
        }.associate { it.profile.id to previousJellyfin.getValue(it.profile.id) }
        val jellyfinProfiles = backup.jellyfinServers.map { server ->
            keptJellyfin[server.profile.id]?.let { old ->
                return@map server.profile.copy(authenticated = old.authenticated, userId = old.userId)
            }
            val hasToken = !server.token.isNullOrBlank()
            server.profile.copy(
                authenticated = hasToken,
                userId = server.profile.userId.takeIf { hasToken },
            )
        }.distinctBy { it.id }
        preferences.replaceJellyfinServerProfiles(jellyfinProfiles, backup.activeJellyfinServerId)
        tokenStore.retainOnlyJellyfinCredentials(keptJellyfin.keys)
        backup.jellyfinServers.forEach { server ->
            server.token?.let { tokenStore.setJellyfinTokenForServer(server.profile.id, it) }
            server.password?.let { tokenStore.setJellyfinPasswordForServer(server.profile.id, it) }
        }
        preferences.jellyfinEnabled = backup.jellyfinEnabled
        preferences.jellyfinHiddenSectionKeys = backup.jellyfinHiddenSectionKeys
        val previousEmby = preferences.embyServerProfiles.associateBy { it.id }
        val keptEmby = backup.embyServers.filter { server ->
            server.token.isNullOrBlank() && previousEmby[server.profile.id]?.let { old ->
                old.host.equals(server.profile.host, ignoreCase = true) && old.port == server.profile.port &&
                    old.useHttps == server.profile.useHttps && old.basePath == server.profile.basePath && old.username == server.profile.username
            } == true && !tokenStore.embyTokenForServer(server.profile.id).isNullOrBlank()
        }.associate { it.profile.id to previousEmby.getValue(it.profile.id) }
        val embyProfiles = backup.embyServers.map { server ->
            keptEmby[server.profile.id]?.let { old ->
                return@map server.profile.copy(authenticated = old.authenticated, userId = old.userId)
            }
            val hasToken = !server.token.isNullOrBlank()
            server.profile.copy(
                authenticated = hasToken,
                userId = server.profile.userId.takeIf { hasToken },
            )
        }.distinctBy { it.id }
        preferences.replaceEmbyServerProfiles(embyProfiles, backup.activeEmbyServerId)
        tokenStore.retainOnlyEmbyCredentials(keptEmby.keys)
        backup.embyServers.forEach { server ->
            server.token?.let { tokenStore.setEmbyTokenForServer(server.profile.id, it) }
            server.password?.let { tokenStore.setEmbyPasswordForServer(server.profile.id, it) }
        }
        preferences.embyEnabled = backup.embyEnabled
        preferences.embyHiddenSectionKeys = backup.embyHiddenSectionKeys
        preferences.plexEnabled = backup.plexEnabled
        preferences.youtubeEnabled = backup.youtubeEnabled
        preferences.spotifyEnabled = backup.spotifyEnabled
        preferences.trailersEnabled = backup.trailersEnabled
        preferences.trailerLanguage = backup.trailerLanguage
        if (backup.schemaVersion >= 47) backup.tmdbApiKey?.let { tokenStore.tmdbApiKey = it }
        if (preferences.spotifyClientId != backup.spotifyClientId) tokenStore.clearSpotifyWebAuth()
        backup.spotifyAccount?.let { account ->
            if (account.refreshToken != null || account.accessToken != null || account == SpotifyAccountBackup()) {
                tokenStore.clearSpotifyWebAuth()
                tokenStore.spotifyWebClientId = account.clientId
                tokenStore.spotifyAccountName = account.name
                tokenStore.spotifyWebAccessToken = account.accessToken
                tokenStore.spotifyWebRefreshToken = account.refreshToken
                tokenStore.spotifyWebAccessTokenExpiresAtMs = account.expiresAtMs
                tokenStore.spotifyWebScopes = account.scopes
            }
        }
        backup.smartTubeAccounts?.let { smartTube?.importAccounts(it) }
        preferences.spotifyClientId = backup.spotifyClientId
        preferences.spotifyDeviceName = backup.spotifyDeviceName
        preferences.spotifyBitrateKbps = backup.spotifyBitrateKbps
        preferences.spotifyVisualizerModeKey = backup.spotifyVisualizerModeKey
        preferences.uiLanguageKey = backup.uiLanguageKey
        preferences.metadataLanguageKey = backup.metadataLanguageKey
        preferences.audioLanguageKey = if (backup.audioLanguageKey != LanguagePreferences.AUTO) {
            backup.audioLanguageKey
        } else {
            backup.preferredAudioLanguage ?: LanguagePreferences.AUTO
        }
        preferences.preferredSubtitleLanguage = backup.preferredSubtitleLanguage
        preferences.subtitlesEnabled = backup.subtitlesEnabled
        preferences.forcedSubtitlesEnabled = backup.forcedSubtitlesEnabled
        preferences.subtitleModeKey = backup.subtitleModeKey ?: when {
            backup.forcedSubtitlesEnabled -> "forced"
            backup.subtitlesEnabled -> "full"
            else -> "off"
        }
        preferences.subtitleLanguageKey = backup.subtitleLanguageKey ?: if (backup.forcedSubtitlesEnabled) "auto" else backup.preferredSubtitleLanguage ?: "auto"
        preferences.subtitleFallbackLanguageKey = backup.subtitleFallbackLanguageKey
        preferences.fullSubtitlesOnForeignAudio = backup.fullSubtitlesOnForeignAudio
        preferences.forcedSubtitlesInUiLanguage = backup.forcedSubtitlesInUiLanguage
        preferences.chooseServerOnStartup = backup.chooseServerOnStartup
        preferences.localNetworkOnly = backup.localNetworkOnly
        preferences.plexThemeMusicEnabled = backup.plexThemeMusicEnabled
        preferences.jellyfinThemeMusicEnabled = backup.jellyfinThemeMusicEnabled
        preferences.embyThemeMusicEnabled = backup.embyThemeMusicEnabled
        preferences.themeMusicVolume = backup.themeMusicVolume
        preferences.autoSelectDisplayVersion = backup.autoSelectDisplayVersion
        preferences.displayChangeNotificationsEnabled = backup.displayChangeNotificationsEnabled
        // svpAvailable, displayModeWhitelist and a concrete display mode describe this device's
        // hardware and installed apps; they are exported for reference but never imported.
        // The automatic policies ("system", auto_content, auto_display) are portable.
        preferences.displayRefreshAdjustmentKey = backup.displayRefreshAdjustmentKey
        if (backup.preferredDisplayModeKey in PORTABLE_DISPLAY_MODES) preferences.preferredDisplayModeKey = backup.preferredDisplayModeKey
        preferences.allowDoubleRefreshRate = backup.allowDoubleRefreshRate
        preferences.allowThreeTwoPulldown = backup.allowThreeTwoPulldown
        // Older backups predate the option; their default must not switch the receiver's pause off.
        if (backup.schemaVersion >= 61) preferences.displaySwitchPauseMs = backup.displaySwitchPauseMs
        preferences.mediaSelectionBehaviorKey = backup.mediaSelectionBehaviorKey
        preferences.showLibraryRowsOnHome = backup.showLibraryRowsOnHome
        preferences.showContinueWatchingRow = backup.showContinueWatchingRow
        preferences.showRecentHomeRows = backup.showRecentHomeRows
        preferences.showCollectionHomeRows = backup.showCollectionHomeRows
        preferences.plexAutoRefreshIntervalSeconds = backup.plexAutoRefreshIntervalSeconds
        preferences.libraryGridColumns = backup.libraryGridColumns
        preferences.libraryListRows = backup.libraryListRows
        preferences.highQualityAssetsEnabled = backup.highQualityAssetsEnabled
        preferences.uiScale = backup.uiScale
        preferences.overscanPercent = backup.overscanPercent
        preferences.accentColorKey = normalizedAccentKey(backup.accentColorKey)
        preferences.customAccentHex = backup.customAccentHex
        preferences.customPlaybackAccentHex = com.ambient.tv.ui.theme.normalizedCustomPlaybackAccentHex(
            backup.customPlaybackAccentHex, preferences.customAccentHex,
        )
        preferences.dualAccentEnabled = backup.dualAccentEnabled
        preferences.themeModeKey = backup.themeModeKey
        preferences.uiHdrModeKey = backup.uiHdrModeKey
        preferences.sdrToHdrEnabled = backup.sdrToHdrEnabled
        preferences.sdrToHdrPeakNits = backup.sdrToHdrPeakNits
        preferences.performanceModeKey = backup.performanceModeKey
        if (backup.schemaVersion >= 49) {
            preferences.uiPerformanceModeKey = backup.uiPerformanceModeKey
            preferences.playbackPerformanceModeKey = backup.playbackPerformanceModeKey
            preferences.frameDropTestSeconds = backup.frameDropTestSeconds
        }
        preferences.homeBackgroundEverywhere = backup.homeBackgroundEverywhere
        preferences.focusArtworkBackdrop = backup.focusArtworkBackdrop
        preferences.homeHeroStyleKey = backup.homeHeroStyleKey
        preferences.homeHeroIntensityPercent = backup.homeHeroIntensityPercent
        preferences.homeHeroSpreadKey = backup.homeHeroSpreadKey
        preferences.notificationDisplayDurationKey = backup.notificationDisplayDurationKey
        preferences.navSoundsEnabled = backup.navSoundsEnabled
        preferences.navSoundsVolume = backup.navSoundsVolume
        if (backup.schemaVersion >= 50) preferences.navSoundBank = backup.navSoundBank
        preferences.playbackBackendKey = backup.playbackBackendKey
        preferences.playerTechBadgesVisible = backup.playerTechBadgesVisible
        preferences.tvheadendPlaybackBackendKey = backup.tvheadendPlaybackBackendKey ?: backup.playbackBackendKey
        preferences.mpvVideoOutputModeKey = backup.mpvVideoOutputModeKey
        preferences.mpvVideoDecoderKey = backup.mpvVideoDecoderKey
        preferences.vlcVideoOutputModeKey = backup.vlcVideoOutputModeKey
        preferences.visibleBackgroundVideoPlayback = backup.visibleBackgroundVideoPlayback
        preferences.backgroundVideoDimPercent = backup.backgroundVideoDimPercent
        // Schema 44+: use mediaAudioPassthroughEnabled.
        // Older backups: OR of legacy plex/tvh flags (if either was true → true).
        preferences.mediaAudioPassthroughEnabled = if (backup.schemaVersion >= 44) {
            backup.mediaAudioPassthroughEnabled
        } else {
            backup.plexAudioPassthroughEnabled || backup.tvheadendAudioPassthroughEnabled
        }
        preferences.audioVisualizerModeKey = backup.audioVisualizerModeKey
        preferences.projectmPresetIntervalSeconds = backup.projectmPresetIntervalSeconds
        preferences.projectmTransitionSeconds = backup.projectmTransitionSeconds
        preferences.projectmQualityKey = backup.projectmQualityKey
        preferences.projectmResolutionKey = backup.projectmResolutionKey
        preferences.projectmHdr4kAllowed = backup.projectmHdr4kAllowed
        preferences.projectmPresetCategoryKey = backup.projectmPresetCategoryKey
        preferences.bigSeekJumpSeconds = backup.bigSeekJumpSeconds
        preferences.youtubeSponsorBlockEnabled = backup.youtubeSponsorBlockEnabled
        preferences.youtubeFeedKeys = backup.youtubeFeedKeys
        preferences.youtubeMaxVideoHeight = backup.youtubeMaxVideoHeight
        // YouTube video preset since schema 66; older backups do not carry it, so their
        // default ("auto") must not replace the preset chosen on this device.
        if (backup.schemaVersion >= 66) preferences.youtubeVideoPreset = backup.youtubeVideoPreset
        preferences.screensaverEnabled = backup.screensaverEnabled
        preferences.screensaverTimeoutMinutes = backup.screensaverTimeoutMinutes
        preferences.screensaverModeKey = backup.screensaverModeKey
        preferences.screensaverShowClock = backup.screensaverShowClock
        preferences.screensaverShowDate = backup.screensaverShowDate
        preferences.screensaverUse24HourClock = backup.screensaverUse24HourClock
        preferences.screensaverUseArtwork = backup.screensaverUseArtwork
        preferences.screensaverArtworkIntervalSeconds = backup.screensaverArtworkIntervalSeconds
        preferences.screensaverDimPercent = backup.screensaverDimPercent
        preferences.screensaverQualityKey = backup.screensaverQualityKey
        preferences.photoSlideshowIntervalSeconds = backup.photoSlideshowIntervalSeconds
        preferences.photoTransitionKey = backup.photoTransitionKey.takeIf { it in setOf("fade", "slide", "none") } ?: "fade"
        preferences.photoKenBurns = backup.photoKenBurns
        preferences.photoShuffle = backup.photoShuffle
        preferences.photoIncludeSubfolders = backup.photoIncludeSubfolders
        preferences.photoSortKey = com.ambient.tv.data.photos.PhotoSort.fromKey(backup.photoSortKey).key
        preferences.photoShowInfo = backup.photoShowInfo
        preferences.screensaverPhotoAlbumKey = backup.screensaverPhotoAlbumKey
        preferences.screensaverPhotoAlbumTitle = backup.screensaverPhotoAlbumTitle
        preferences.tvheadendEnabled = backup.tvheadendEnabled
        val sameTvheadend = backup.tvheadendHost.isNotBlank() && preferences.tvheadendHost.trim().trimEnd('.').equals(backup.tvheadendHost.trim().trimEnd('.'), ignoreCase = true) &&
            preferences.tvheadendPort == backup.tvheadendPort && preferences.tvheadendUseHttps == backup.tvheadendUseHttps &&
            preferences.tvheadendUsername == backup.tvheadendUsername
        preferences.tvheadendHost = backup.tvheadendHost
        preferences.tvheadendPort = backup.tvheadendPort
        preferences.tvheadendUseHttps = backup.tvheadendUseHttps
        preferences.tvheadendUsername = backup.tvheadendUsername
        // Without a password in the backup, keep the local one only for the very same server/user.
        if (backup.tvheadendPassword != null || !sameTvheadend) tokenStore.tvheadendPassword = backup.tvheadendPassword
        preferences.tvheadendStreamProfile = backup.tvheadendStreamProfile
        preferences.tvheadendMacAddress = backup.tvheadendMacAddress
        preferences.tvheadendAutoWakeEnabled = backup.tvheadendAutoWakeEnabled
        preferences.tvheadendShowOnHome = backup.tvheadendShowOnHome
        preferences.tvheadendDeinterlaceModeKey = backup.tvheadendDeinterlaceModeKey
        preferences.tvheadendDeinterlaceFilterKey = backup.tvheadendDeinterlaceFilterKey
        preferences.tvheadendMpvDeinterlaceFilterKey = backup.tvheadendMpvDeinterlaceFilterKey
            ?: DeinterlaceCatalog.migrateMpv(backup.tvheadendDeinterlaceFilterKey)
        preferences.sidebarSystemOrder = backup.sidebarSystemOrder
        preferences.systemNames = backup.systemNames
        preferences.sidebarDividersAfter = backup.sidebarDividersAfter.toSet()
        preferences.sidebarEntryOrder = backup.sidebarEntryOrder
        preferences.sidebarEntryDividers = backup.sidebarEntryDividers
        preferences.spotifySubmenuKeys = backup.spotifySubmenuKeys
        preferences.tvheadendSubmenuKeys = backup.tvheadendSubmenuKeys
        preferences.plexHiddenSectionKeys = backup.plexHiddenSectionKeys
        preferences.sidebarSettingsShortcutHidden = backup.sidebarSettingsShortcutHidden
        preferences.mpvScalingFilterKey = backup.mpvScalingFilterKey
        preferences.mpvDebandEnabled = backup.mpvDebandEnabled
        preferences.vlcScalingFilterKey = backup.vlcScalingFilterKey
        // Import replaces advanced defaults as well; old backups must not retain stale filters.
        // Schema 70 added "Kanäle erzwingen" (AudioOptions.forceChannels). Older backups cannot
        // carry it; never let a hand-edited old file force a channel layout.
        preferences.audioSettings = (backup.audioSettings ?: AudioSettings()).let { audio ->
            if (backup.schemaVersion >= 70) audio
            else audio.copy(profiles = audio.profiles.mapValues { (_, options) -> options.copy(forceChannels = 0) })
        }
        preferences.mpvImageOptions = backup.mpvImageOptions ?: MpvImageOptions(
            scaler = backup.mpvScalingFilterKey, deband = if (backup.mpvDebandEnabled) "medium" else "off",
        )
        preferences.vlcImageOptions = backup.vlcImageOptions ?: VlcImageOptions(scaler = backup.vlcScalingFilterKey)
        preferences.media3ImageOptions = backup.media3ImageOptions ?: Media3ImageOptions()
        preferences.resolutionVideoProfiles = VideoResolutionClass.migrateProfiles(backup.resolutionVideoProfiles)
        // Title and scoped profiles since schema 69; older backups keep this device's choices.
        if (backup.schemaVersion >= 69) {
            preferences.titleVideoSettings = restorableTitleVideoSettings(backup.titleVideoSettings)
            preferences.scopedVideoSettings = restorableScopedVideoSettings(backup.scopedVideoSettings)
        }
        preferences.youtubeAutoplayNextEnabled = backup.youtubeAutoplayNextEnabled
        preferences.youtubeMediaSelectionBehaviorKey = backup.youtubeMediaSelectionBehaviorKey
        preferences.introSkipMode = backup.introSkipMode
        preferences.creditsSkipMode = backup.creditsSkipMode
        preferences.nextUpEnabled = backup.nextUpEnabled
        preferences.nextUpLeadSeconds = backup.nextUpLeadSeconds
        preferences.nextUpCreditsDelaySeconds = backup.nextUpCreditsDelaySeconds
        preferences.nextUpAutoplayEpisodes = backup.nextUpAutoplayEpisodes
        preferences.nextUpAutoplayCollections = backup.nextUpAutoplayCollections
        preferences.nextUpOverlayEnabled = backup.nextUpOverlayEnabled
        preferences.nextUpStillWatchingEpisodes = backup.nextUpStillWatchingEpisodes
        // Backups before schema 62 have no sleep-timer defaults: they restore the defaults (off, 1 min).
        val sleepDefaults = backup.schemaVersion >= 62
        preferences.sleepAutoStartKey = if (sleepDefaults) backup.sleepAutoStartKey else AppPreferences.SLEEP_AUTO_START_OFF
        preferences.sleepWarningMinutes = if (sleepDefaults) backup.sleepWarningMinutes else AppPreferences.SLEEP_WARNING_DEFAULT
        // Daily stop time since schema 63; older backups restore "off".
        preferences.sleepDailyStopMinute = if (backup.schemaVersion >= 63) backup.sleepDailyStopMinute else AppPreferences.SLEEP_DAILY_STOP_OFF
        // HDR über GPU since schema 64; older backups restore "off".
        preferences.mpvHdrGpuEnabled = backup.schemaVersion >= 64 && backup.mpvHdrGpuEnabled
        // SDR→HDR reference white since schema 65; older backups keep the default (auto).
        preferences.sdrToHdrReferenceWhiteNits = if (backup.schemaVersion >= 65) backup.sdrToHdrReferenceWhiteNits else 0
        // Since schema 66; older backups keep the defaults (no 100/120 Hz, Auto, Auto, no explicit SVP).
        val schema66 = backup.schemaVersion >= 66
        preferences.allowHighRefreshDoubling = schema66 && backup.allowHighRefreshDoubling
        preferences.gpuRenderResolutionKey = if (schema66) backup.gpuRenderResolutionKey else AppPreferences.GPU_RENDER_AUTO
        preferences.mpvSdrCurveKey = if (schema66) backup.mpvSdrCurveKey else AppPreferences.MPV_SDR_CURVE_AUTO
        preferences.svpExplicitChoice = schema66 && backup.svpExplicitChoice
        preferences.preferDoubleRefreshRate = backup.schemaVersion >= 71 && backup.preferDoubleRefreshRate
        if (backup.schemaVersion >= 72) preferences.introAnimationKey = backup.introAnimationKey
        // "An Display synchronisieren" since schema 67; older backups restore "off".
        preferences.syncPlaybackToDisplay = backup.schemaVersion >= 67 && backup.syncPlaybackToDisplay
        // Deliberate MPV scaler/deband choice since schema 68; older backups restore "not chosen".
        preferences.mpvImageExplicitChoice = backup.schemaVersion >= 68 && backup.mpvImageExplicitChoice
        preferences.wolRetryCount = backup.wolRetryCount
        if (backup.homeRows != null) preferences.homeRows = backup.homeRows
        else preferences.resetHomeRowsToLegacy()
        preferences.systemHomeHiddenRows = backup.systemHomeHiddenRows
        preferences.restorePortableLibraryPreferences(backup.libraryPreferences)
        preferences.clearRecentSearches()
        backup.recentSearches.asReversed().forEach(preferences::rememberSearch)
        preferences.wireGuardEnabled = backup.wireGuardEnabled
        preferences.wireGuardPlexEnabled = backup.wireGuardPlexEnabled
        preferences.wireGuardJellyfinEnabled = backup.wireGuardJellyfinEnabled
        preferences.wireGuardEmbyEnabled = backup.wireGuardEmbyEnabled
        preferences.wireGuardTvheadendEnabled = backup.wireGuardTvheadendEnabled
        // Keys follow the profile: a different (or removed) profile without keys must not keep
        // the old peer's keys; the same profile without keys keeps the local ones.
        val wireGuardProfileChanged = preferences.wireGuardProfile() != backup.wireGuardProfile?.normalized()
        preferences.setWireGuardProfile(backup.wireGuardProfile)
        if (backup.wireGuardPrivateKey != null) tokenStore.wireGuardPrivateKey = backup.wireGuardPrivateKey
        else if (wireGuardProfileChanged) tokenStore.wireGuardPrivateKey = null
        if (backup.wireGuardPresharedKey != null) tokenStore.wireGuardPresharedKey = backup.wireGuardPresharedKey
        else if (wireGuardProfileChanged) tokenStore.wireGuardPresharedKey = null
        if (backup.schemaVersion >= 60) preferences.playerOsdSeconds = backup.playerOsdSeconds
        if (backup.schemaVersion >= 59) {
            preferences.liveTimeshiftEnabled = backup.liveTimeshiftEnabled
            preferences.liveDirectRecordEnabled = backup.liveDirectRecordEnabled
        }
        if (backup.schemaVersion >= 58) {
            backup.iptvSources?.let { com.ambient.tv.data.iptv.IptvStore(tokenStore).replaceAll(it) }
            // The account belongs to the key: a backup with a key replaces all three (an absent account clears it).
            backup.openSubtitlesApiKey?.let { key ->
                tokenStore.openSubtitlesApiKey = key
                tokenStore.openSubtitlesUsername = backup.openSubtitlesUsername
                tokenStore.openSubtitlesPassword = backup.openSubtitlesPassword
            }
            preferences.liveTimeshiftMb = backup.liveTimeshiftMb
            context?.let { com.ambient.tv.livetv.LiveTvReminderScheduler.setLeadMinutes(it, backup.liveTvReminderLeadMinutes) }
        }
    }

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 72
        val PORTABLE_DISPLAY_MODES = setOf("system", AppPreferences.DISPLAY_MODE_AUTO_CONTENT, AppPreferences.DISPLAY_MODE_AUTO_DISPLAY)
    }
}

/** Drops malformed keys and keeps the newest [TitleVideoSettings.MAX_TITLES] titles. */
internal fun restorableTitleVideoSettings(all: Map<String, TitleVideoSettings>): Map<String, TitleVideoSettings> =
    all.filter { (key, value) -> key.isNotBlank() && ':' in key && !TitleVideoSettings.isScopedKey(key) && !value.isEmpty }
        .entries.sortedByDescending { it.value.savedAt }.take(TitleVideoSettings.MAX_TITLES).associate { it.key to it.value }

/** Only season/series/collection keys; title-only fields are never restored into a scope. */
internal fun restorableScopedVideoSettings(all: Map<String, TitleVideoSettings>): Map<String, TitleVideoSettings> =
    all.filter { (key, value) -> TitleVideoSettings.isScopedKey(key) && !value.isEmpty }
        .mapValues { (_, value) -> value.copy(snapshot = false, mpvOverloadContinueSignature = null, vlcOverloadContinueSignature = null) }
        .entries.sortedByDescending { it.value.savedAt }.take(TitleVideoSettings.MAX_SCOPES).associate { it.key to it.value }
