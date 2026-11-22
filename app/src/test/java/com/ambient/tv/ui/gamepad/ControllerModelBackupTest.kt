package com.ambient.tv.ui.gamepad

import com.ambient.tv.companion.CompanionBackupPolicy
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.data.local.SettingsBackupManager
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.memoryPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerModelBackupTest {
    @After fun clearLiveConfig() { GamepadInput.reload(emptyMap()) }

    private fun manager(preferences: AppPreferences) =
        SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))

    @Test fun completeBackupRestoresVisualModelsAndIndependentButtonMappings() {
        val expected = GamepadConfig()
            .withControllerModel("living-room-controller", ControllerModel.PS5)
            .withControllerModel("second-controller", ControllerModel.GENERIC)
            .withDeviceCommand("living-room-controller", GamepadContext.MENUS, GamepadSlot(GamepadButton.A, false),
                GamepadCommand.Key(GamepadKeyCommand.BACK))
        val source = AppPreferences(memoryPreferences()).apply { gamepadSettings = expected.encode() }
        val target = AppPreferences(memoryPreferences())

        manager(target).importJson(manager(source).exportJson())

        assertEquals(expected, GamepadConfig.decode(target.gamepadSettings))
        assertEquals(expected, GamepadInput.config)
    }

    @Test fun tvPreferenceSyncCarriesVisualOverridesWhileAccountsOnlyKeepsTheReceiver() {
        val descriptor = "shared-controller-descriptor"
        val sourceConfig = GamepadConfig().withControllerModel(descriptor, ControllerModel.PS4)
        val targetConfig = GamepadConfig().withControllerModel(descriptor, ControllerModel.XBOX_360)
        fun backup(config: GamepadConfig): SettingsBackup {
            val preferences = AppPreferences(memoryPreferences()).apply { gamepadSettings = config.encode() }
            return manager(preferences).let { it.previewJson(it.exportJson()) }
        }
        val source = backup(sourceConfig)
        val target = backup(targetConfig)
        fun synced(mode: String): GamepadConfig {
            val merged = CompanionBackupPolicy.json.decodeFromString<SettingsBackup>(
                CompanionBackupPolicy.merge(source, target, mode, overwrite = true, deviceSettings = false))
            return GamepadConfig.decode(merged.extras?.portable?.gamepadSettings.orEmpty())
        }

        assertEquals(sourceConfig, synced("preferences"))
        assertEquals(targetConfig, synced("accounts"))
    }
}
