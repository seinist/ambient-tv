package com.ambient.tv.ui.gamepad

import android.view.KeyEvent
import com.ambient.tv.ui.keyboard.KeyAction
import com.ambient.tv.ui.keyboard.KeyBindingScope
import com.ambient.tv.ui.keyboard.isBindableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GamepadSettingsTest {
    private fun key(command: GamepadKeyCommand) = GamepadCommand.Key(command)

    @Test fun defaultsFollowConsoleConventions() {
        val config = GamepadConfig()
        assertEquals(key(GamepadKeyCommand.CONFIRM), config.resolve(GamepadButton.A, long = false, player = false))
        assertEquals(key(GamepadKeyCommand.BACK), config.resolve(GamepadButton.B, long = false, player = false))
        assertEquals(key(GamepadKeyCommand.CONTEXT_MENU), config.resolve(GamepadButton.X, long = false, player = false))
        assertEquals(GamepadCommand.Action(KeyAction.SEARCH), config.resolve(GamepadButton.Y, long = false, player = false))
        assertEquals(GamepadCommand.Action(KeyAction.SETTINGS), config.resolve(GamepadButton.START, long = true, player = false))
        assertEquals(GamepadCommand.None, config.resolve(GamepadButton.A, long = true, player = false))
    }

    @Test fun playerOverridesSomeButtonsAndInheritsConfirmAndBack() {
        val config = GamepadConfig()
        assertEquals(key(GamepadKeyCommand.CONFIRM), config.resolve(GamepadButton.A, long = false, player = true))
        assertEquals(key(GamepadKeyCommand.BACK), config.resolve(GamepadButton.B, long = false, player = true))
        assertEquals(key(GamepadKeyCommand.PLAY_PAUSE), config.resolve(GamepadButton.X, long = false, player = true))
        assertEquals(key(GamepadKeyCommand.FAST_FORWARD), config.resolve(GamepadButton.R2, long = false, player = true))
        assertEquals(GamepadCommand.Action(KeyAction.AUDIO_TRACKS), config.resolve(GamepadButton.Y, long = true, player = true))
        // Page navigation is not available in the player: no inherited Settings/Home.
        assertEquals(GamepadCommand.None, config.resolve(GamepadButton.START, long = true, player = true))
        assertEquals(GamepadCommand.None, config.resolve(GamepadButton.MODE, long = false, player = true))
        assertEquals(GamepadCommand.Inherit, config.configured(GamepadContext.PLAYER, GamepadSlot(GamepadButton.A, false)))
    }

    @Test fun confirmOnBSwapsOnlyTheDefaultsOfAAndB() {
        val config = GamepadConfig(confirmOnB = true)
        assertEquals(key(GamepadKeyCommand.BACK), config.resolve(GamepadButton.A, long = false, player = false))
        assertEquals(key(GamepadKeyCommand.CONFIRM), config.resolve(GamepadButton.B, long = false, player = true))
        assertEquals(key(GamepadKeyCommand.CONTEXT_MENU), config.resolve(GamepadButton.X, long = false, player = false))
    }

    @Test fun choosingTheDefaultRemovesTheOverride() {
        val slot = GamepadSlot(GamepadButton.X, long = false)
        val changed = GamepadConfig().withCommand(GamepadContext.MENUS, slot, GamepadCommand.Action(KeyAction.REFRESH))
        assertEquals(GamepadCommand.Action(KeyAction.REFRESH), changed.resolve(GamepadButton.X, long = false, player = false))
        val back = changed.withCommand(GamepadContext.MENUS, slot, key(GamepadKeyCommand.CONTEXT_MENU))
        assertTrue(back.overrides.isEmpty())
    }

    @Test fun playerAsInMenusFollowsTheMenuButton() {
        val config = GamepadConfig()
            .withCommand(GamepadContext.MENUS, GamepadSlot(GamepadButton.X, false), GamepadCommand.Volume(GamepadVolumeCommand.MUTE))
            .withCommand(GamepadContext.PLAYER, GamepadSlot(GamepadButton.X, false), GamepadCommand.Inherit)
        assertEquals(GamepadCommand.Volume(GamepadVolumeCommand.MUTE), config.resolve(GamepadButton.X, long = false, player = true))
    }

    @Test fun encodeDecodeRoundTripsAndIgnoresUnknownValues() {
        val config = GamepadConfig(
            enabled = false, confirmOnB = true, leftStickNavigates = false, rightStickScrolls = false,
            deadzone = GamepadDeadzone.LARGE, repeat = GamepadRepeatSpeed.FAST,
        )
            .withCommand(GamepadContext.MENUS, GamepadSlot(GamepadButton.L2, false), key(GamepadKeyCommand.CHANNEL_DOWN))
            .withCommand(GamepadContext.PLAYER, GamepadSlot(GamepadButton.Y, true), GamepadCommand.Inherit)
            .withCommand(GamepadContext.PLAYER, GamepadSlot(GamepadButton.THUMB_L, false), GamepadCommand.None)
        val encoded = config.encode()
        assertEquals(config, GamepadConfig.decode(encoded))
        val noisy = encoded + mapOf("menus.a" to "inherit", "menus.b" to "action:gone", "player.zz" to "key:back", "deadzone" to "huge")
        val decoded = GamepadConfig.decode(noisy)
        assertEquals(GamepadDeadzone.MEDIUM, decoded.deadzone)
        assertEquals(key(GamepadKeyCommand.BACK), decoded.resolve(GamepadButton.A, long = false, player = false))
        assertEquals(GamepadConfig(), GamepadConfig.decode(emptyMap()))
    }

    @Test fun controllerButtonsAreNotRemoteKeys() {
        assertTrue(isGamepadButtonKeyCode(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(isGamepadButtonKeyCode(KeyEvent.KEYCODE_BUTTON_16))
        assertFalse(isGamepadButtonKeyCode(KeyEvent.KEYCODE_PROG_RED))
        assertFalse(isBindableKey(KeyEvent.KEYCODE_BUTTON_Y))
        assertTrue(isBindableKey(KeyEvent.KEYCODE_PROG_RED))
    }

    @Test fun legacyRemoteBindingsOnControllerButtonsMoveToTheControllerPage() {
        val (remote, moved) = splitLegacyGamepadBindings(mapOf(
            "subtitles" to "${KeyEvent.KEYCODE_BUTTON_Y}:1",
            "search" to "${KeyEvent.KEYCODE_BUTTON_X}:0",
            "refresh" to "${KeyEvent.KEYCODE_PROG_RED}:0",
            "weather" to "${KeyEvent.KEYCODE_BUTTON_1}:0",
        ))
        assertEquals(mapOf("refresh" to "${KeyEvent.KEYCODE_PROG_RED}:0"), remote)
        assertEquals(mapOf(GamepadSlot(GamepadButton.Y, true) to GamepadCommand.Action(KeyAction.SUBTITLES)), moved[GamepadContext.PLAYER])
        assertEquals(mapOf(GamepadSlot(GamepadButton.X, false) to GamepadCommand.Action(KeyAction.SEARCH)), moved[GamepadContext.MENUS])

        // A newer choice on the controller page wins over the old remote binding.
        val existing = GamepadConfig().withCommand(GamepadContext.MENUS, GamepadSlot(GamepadButton.X, false), GamepadCommand.None)
        val merged = existing.withLegacy(moved)
        assertEquals(GamepadCommand.None, merged.resolve(GamepadButton.X, long = false, player = false))
        assertEquals(GamepadCommand.Action(KeyAction.SUBTITLES), merged.resolve(GamepadButton.Y, long = true, player = true))
    }

    @Test fun menuChoicesOfferNoPlayerActionsAndNoInherit() {
        val menus = gamepadCommandChoices(GamepadContext.MENUS)
        assertFalse(GamepadCommand.Inherit in menus)
        assertFalse(menus.any { it is GamepadCommand.Action && it.action.scope == KeyBindingScope.PLAYER })
        val player = gamepadCommandChoices(GamepadContext.PLAYER)
        assertEquals(GamepadCommand.Inherit, player.first())
        assertTrue(GamepadCommand.Action(KeyAction.SUBTITLES) in player)
        // Every choice survives storage.
        (menus + player).forEach { assertEquals(it, GamepadCommand.decode(it.storageKey)) }
    }

    @Test fun labelsFollowTheControllerFamily() {
        assertEquals(GamepadLabels.PLAYSTATION, GamepadLabels.forVendor(0x054C))
        assertEquals(GamepadLabels.XBOX, GamepadLabels.forVendor(0x045E))
        assertEquals("✕", GamepadLabels.PLAYSTATION.label(GamepadButton.A))
        assertEquals("RB", GamepadLabels.XBOX.label(GamepadButton.R1))
        // Nintendo: positions as Xbox, letters swapped (bottom B, right A).
        assertEquals(GamepadLabels.NINTENDO, GamepadLabels.forVendor(0x057E))
        assertEquals("B", GamepadLabels.NINTENDO.label(GamepadButton.A))
        assertEquals("ZR", GamepadLabels.NINTENDO.label(GamepadButton.R2))
    }
    @Test fun physicalControllerOverridesDoNotAffectOtherPadsAndSurviveBackup() {
        val slot = GamepadSlot(GamepadButton.A, false)
        val device = "DualSense Vendor 054c / two"
        val changed = GamepadConfig()
            .withDeviceCommand(device, GamepadContext.MENUS, slot, key(GamepadKeyCommand.BACK))
        assertEquals(key(GamepadKeyCommand.BACK),
            changed.resolve(GamepadButton.A, false, false, device))
        assertEquals(key(GamepadKeyCommand.CONFIRM),
            changed.resolve(GamepadButton.A, false, false, "other-device"))
        assertEquals(changed, GamepadConfig.decode(changed.encode()))
        val reset = changed.withDeviceCommand(device, GamepadContext.MENUS, slot,
            key(GamepadKeyCommand.CONFIRM))
        assertTrue(reset.deviceOverrides.isEmpty())
        assertEquals(GamepadConfig(), GamepadConfig.decode(mapOf("device.@@@.menus.a" to "none")))
    }

    @Test fun visualControllerChoicesRoundTripPerDescriptorAndCanReturnToAutomatic() {
        val first = "Wohnzimmer / Controller.ä"
        val second = "Bedroom / Controller.2"
        val configured = GamepadConfig()
            .withControllerModel(first, ControllerModel.PS5)
            .withControllerModel(second, ControllerModel.XBOX_SERIES)
        assertEquals(configured, GamepadConfig.decode(configured.encode()))
        assertEquals(mapOf(second to ControllerModel.XBOX_SERIES),
            configured.withControllerModel(first, null).controllerModels)
        assertEquals(GamepadConfig(), configured.withControllerModel(first, null).withControllerModel(second, null))
    }

    @Test fun aVisualOverrideDoesNotChangeAnyShortLongPlayerOrMenuBinding() {
        val source = GamepadConfig(confirmOnB = true)
            .withDeviceCommand("one", GamepadContext.MENUS, GamepadSlot(GamepadButton.X, true),
                GamepadCommand.Action(KeyAction.REFRESH))
        val changed = source.withControllerModel("one", ControllerModel.PS4)
        for (descriptor in listOf(null, "one", "two")) {
            for (button in GamepadButton.entries) for (long in listOf(false, true)) for (player in listOf(false, true)) {
                assertEquals(source.resolve(button, long, player, descriptor),
                    changed.resolve(button, long, player, descriptor))
            }
        }
        assertEquals(changed, GamepadConfig.decode(changed.encode()))
    }

    @Test fun malformedOrOversizedVisualOverridesAreIgnoredWithoutDroppingValidOnes() {
        fun id(value: String) = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
        val stored = mapOf(
            "model.@@@" to "ps5",
            "model." to "ps5",
            "model.${id(" ")}" to "ps5",
            "model.${id("x".repeat(GamepadConfig.MAX_CONTROLLER_DESCRIPTOR_LENGTH + 1))}" to "ps5",
            "model._w" to "ps5", // Invalid UTF-8, not a controller descriptor.
            "model.${id("future")}" to "future-model",
            "model.${id("good")}" to "ps4",
        )
        assertEquals(mapOf("good" to ControllerModel.PS4), GamepadConfig.decode(stored).controllerModels)
        assertEquals(GamepadConfig(), GamepadConfig().withControllerModel(" ", ControllerModel.PS5))
    }

    @Test fun visualOverrideCapacityIsBoundedAndExistingChoicesRemainEditable() {
        val models = (0..GamepadConfig.MAX_CONTROLLER_MODELS).associate { "pad-$it" to ControllerModel.PS3 }
        val decoded = GamepadConfig.decode(GamepadConfig(controllerModels = models).encode())
        assertEquals(GamepadConfig.MAX_CONTROLLER_MODELS, decoded.controllerModels.size)
        val unboundedStored = models.mapKeys { (descriptor, _) ->
            "model." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(descriptor.toByteArray(Charsets.UTF_8))
        }.mapValues { (_, model) -> model.key }
        assertEquals(GamepadConfig.MAX_CONTROLLER_MODELS, GamepadConfig.decode(unboundedStored).controllerModels.size)
        assertEquals(decoded, decoded.withControllerModel("new-controller", ControllerModel.PS5))
        assertEquals(ControllerModel.PS4, decoded.withControllerModel("pad-0", ControllerModel.PS4).controllerModels["pad-0"])
        assertEquals(GamepadConfig.MAX_CONTROLLER_MODELS - 1,
            decoded.withControllerModel("pad-0", null).controllerModels.size)
    }

}
