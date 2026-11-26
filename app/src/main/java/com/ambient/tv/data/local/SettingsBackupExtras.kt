package com.ambient.tv.data.local

import kotlinx.serialization.Serializable

/**
 * Settings added in schema 80. Keep additions grouped: SettingsBackup's generated constructors
 * are close to the JVM parameter-slot limit. Missing groups or fields preserve the receiver.
 * The groups also let sharing copy preferences, device tuning and connections independently.
 */
@Serializable
data class SettingsBackupExtras(
    val portable: PortableSettingsExtras? = null,
    val device: DeviceSettingsExtras? = null,
    val connections: ConnectionSettingsExtras? = null,
    /** Present only when the user explicitly includes credentials. Never contains built-in keys. */
    val credentials: CredentialSettingsExtras? = null,
)

@Serializable
data class PortableSettingsExtras(
    val allContinueSystems: Set<String>? = null,
    val filterBarShown: Boolean? = null,
    val launcherIntegrationEnabled: Boolean? = null,
    val settingsLevelKey: String? = null,
    val autoPictureInPicture: Boolean? = null,
    val projectmShowPresetName: Boolean? = null,
    val projectmPresetShuffle: Boolean? = null,
    val gaplessMusicEnabled: Boolean? = null,
    val teletextSubtitleChoice: String? = null,
    val lyricsVisible: Boolean? = null,
    val lyricsOnlineEnabled: Boolean? = null,
    val frameDropOverlayHistory: Boolean? = null,
    /** Opt-in Next Up layout. Omitted by old backups; keeps the receiving device's choice. */
    val nextUpMinimalEnabled: Boolean? = null,
    /** Opt-in UI navigation transition. Null from old/partial backups preserves the receiver. */
    val viewTransitionStyleKey: String? = null,
    /** Null from old/partial backups keeps the receiver's mouse pointer timeout. */
    val mousePointerHideSeconds: Int? = null,
    val liveTvReminderAutoSwitch: Boolean? = null,
    val autoUpdateCheck: Boolean? = null,
    val projectmFavorites: Set<String>? = null,
    /** Remote/keyboard key bindings (action key -> "keyCode:long"). */
    val keyBindings: Map<String, String>? = null,
    /** Controller options and button overrides (see ui.gamepad.GamepadConfig). */
    val gamepadSettings: Map<String, String>? = null,
    /** Game input players and layouts (see ui.gamepad.retro.RetroInputConfig). */
    val retroInputSettings: Map<String, String>? = null,
    /** Game picture filters and core options (see games.GameSettingsState). */
    val gameSettings: Map<String, String>? = null,
    /** Console browse mode, density, sorting and ROM-title priority (no ROMs or catalog cache). */
    val gameLibraryOptions: Map<String, String>? = null,
    /** Portable ROM title overrides, favorites and recent launches (no ROM data). */
    val gameLibraryOverrides: List<com.ambient.tv.games.library.GamePortableState>? = null,
    /** Source-linked ROM folder assignments live in Extras to stay under JVM 255-slot limit. */
    val gameLibraryFolders: List<com.ambient.tv.games.library.GameLibraryFolder>? = null,
    val gameMetadataOptions: com.ambient.tv.games.metadata.GameMetadataOptions? = null,
    /** Portable playback quality; no GameStream certificates or private client keys. */
    val moonlightStreamProfile: com.ambient.tv.moonlight.MoonlightStreamProfile? = null,
    val moonlightLibraryView: String? = null,
    val moonlightGridColumns: Int? = null,
    val moonlightListRows: Int? = null,
    val moonlightSortKey: String? = null,
    val moonlightSortDescending: Boolean? = null,
)

@Serializable
data class DeviceSettingsExtras(
    val imageCacheMb: Int? = null,
    val cardImages1080: Boolean? = null,
)

@Serializable
data class ConnectionSettingsExtras(
    val tvheadendHtspPort: Int? = null,
    /** RomM switch, address and account name; each device signs in itself (its own device token). */
    val rommEnabled: Boolean? = null,
    val rommServerUrl: String? = null,
    val rommUsername: String? = null,
    /** Host metadata is portable, but pairing certificates and AndroidKeyStore client keys are not. */
    val moonlightEnabled: Boolean? = null,
    val moonlightHosts: List<com.ambient.tv.moonlight.MoonlightHost>? = null,
    val moonlightHostProfiles: Map<String, com.ambient.tv.moonlight.MoonlightStreamProfile>? = null,
)

@Serializable
data class CredentialSettingsExtras(
    val gameMetadata: com.ambient.tv.games.metadata.GameMetadataCredentials? = null,
)
