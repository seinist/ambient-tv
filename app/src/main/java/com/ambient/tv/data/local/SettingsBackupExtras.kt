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
)

@Serializable
data class DeviceSettingsExtras(
    val imageCacheMb: Int? = null,
    val cardImages1080: Boolean? = null,
)

@Serializable
data class ConnectionSettingsExtras(
    val tvheadendHtspPort: Int? = null,
)
