package com.ambient.tv.ui.gamepad

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControllerModelTest {
    @Test fun exactUsbAndBluetoothIdsWinOverAmbiguousNames() {
        val examples = listOf(
            Triple(0x054c, 0x0268, ControllerModel.PS3),
            Triple(0x054c, 0x05c4, ControllerModel.PS4),
            Triple(0x054c, 0x09cc, ControllerModel.PS4),
            Triple(0x054c, 0x0ba0, ControllerModel.PS4),
            Triple(0x054c, 0x0ce6, ControllerModel.PS5),
            Triple(0x045e, 0x028e, ControllerModel.XBOX_360),
            Triple(0x045e, 0x0719, ControllerModel.XBOX_360),
            Triple(0x045e, 0x02ea, ControllerModel.XBOX_ONE),
            Triple(0x045e, 0x02fd, ControllerModel.XBOX_ONE),
            Triple(0x045e, 0x0b20, ControllerModel.XBOX_ONE),
            Triple(0x045e, 0x0b12, ControllerModel.XBOX_SERIES),
            Triple(0x045e, 0x0b13, ControllerModel.XBOX_SERIES),
            Triple(0x057e, 0x2006, ControllerModel.JOY_CON_LEFT),
            Triple(0x057e, 0x2007, ControllerModel.JOY_CON_RIGHT),
            Triple(0x057e, 0x2008, ControllerModel.JOY_CON_PAIR),
            Triple(0x057e, 0x2009, ControllerModel.SWITCH_PRO),
            Triple(0x057e, 0x2017, ControllerModel.SNES),
            Triple(0x057e, 0x2019, ControllerModel.N64),
        )
        examples.forEach { (vendor, product, model) ->
            assertEquals("${vendor.toString(16)}:${product.toString(16)}", model,
                resolveControllerModel(vendor, product, "Wireless Controller"))
        }
    }

    @Test fun exactKnownNamesWorkWhenAndroidOmitsIds() {
        val examples = mapOf(
            "Sony DualSense Wireless Controller" to ControllerModel.PS5,
            "DualShock 4" to ControllerModel.PS4,
            "PLAYSTATION(R)3 Controller" to ControllerModel.PS3,
            "Microsoft X-Box 360 pad" to ControllerModel.XBOX_360,
            "Xbox One S Controller" to ControllerModel.XBOX_ONE,
            "Microsoft Xbox Series S|X Controller" to ControllerModel.XBOX_SERIES,
            "Nintendo Switch Pro Controller" to ControllerModel.SWITCH_PRO,
            "Joy-Con (L)" to ControllerModel.JOY_CON_LEFT,
            "Joy-Con (R)" to ControllerModel.JOY_CON_RIGHT,
            "Joy-Con (L/R)" to ControllerModel.JOY_CON_PAIR,
        )
        examples.forEach { (name, model) -> assertEquals(name, model, resolveControllerModel(0, 0, name)) }
    }

    @Test fun ambiguousNamesAndUnrelatedVendorIdsDoNotInventAModel() {
        listOf("", "Controller", "Wireless Controller", "Bluetooth Gamepad", "Pro Controller", "Android Gamepad")
            .forEach { assertEquals(it, ControllerModel.GENERIC, resolveControllerModel(0, 0, it)) }
        assertEquals(ControllerModel.GENERIC, resolveControllerModel(0xabcd, 0x0ce6, "Wireless Controller"))
        assertEquals(ControllerModel.GENERIC, resolveControllerModel(0, 0x0ce6, "Wireless Controller"))
        assertEquals(ControllerModel.XBOX, resolveControllerModel(0, 0, "Xbox Wireless Controller"))
        assertEquals(ControllerModel.PLAYSTATION, resolveControllerModel(0x054c, 0xffff, "Wireless Controller"))
        assertEquals(ControllerModel.NINTENDO, resolveControllerModel(0x057e, 0xffff, "Wireless Controller"))
        assertEquals(ControllerModel.XBOX, resolveControllerModel(0x045e, 0xffff, "Wireless Controller"))
    }

    @Test fun differentShellsDoNotBorrowAnOrdinaryControllerModel() {
        assertEquals(ControllerModel.PLAYSTATION, resolveControllerModel(0x054c, 0x0df2, "DualSense Wireless Controller"))
        assertEquals(ControllerModel.PLAYSTATION, resolveControllerModel(0, 0, "DualSense Edge Wireless Controller"))
        assertEquals(ControllerModel.XBOX, resolveControllerModel(0x045e, 0x0b00, "Xbox One Controller"))
        assertEquals(ControllerModel.XBOX, resolveControllerModel(0x045e, 0x0b0a, "Xbox Adaptive Controller"))
        assertEquals(ControllerModel.NINTENDO, resolveControllerModel(0x057e, 0x2069, "Pro Controller"))
        assertEquals(ControllerModel.NINTENDO, resolveControllerModel(0x057e, 0x2067, "Joy-Con (L)"))
        assertEquals(ControllerModel.NINTENDO, resolveControllerModel(0x057e, 0x200e, "Joy-Con Charging Grip"))
    }

    @Test fun compatibleThirdPartyPadsKeepTheirFamilyWithoutClaimingItsShell() {
        assertEquals(ControllerModel.NINTENDO, resolveControllerModel(0x0f0d, 0x00dc, "HORIPAD S"))
        assertEquals(ControllerModel.NINTENDO, resolveControllerModel(0x0f0d, 0x00c1, "HORIPAD"))
        assertEquals(ControllerModel.PLAYSTATION, resolveControllerModel(0x0f0d, 0x0086, "HORI Fighting Commander"))
        assertEquals(ControllerModel.XBOX, resolveControllerModel(0x0f0d, 0x0067, "HORIPAD ONE"))
        assertEquals(ControllerModel.XBOX, resolveControllerModel(0x2dc8, 0x2002, "8BitDo Ultimate Wired Controller"))
        assertEquals(ControllerModel.XBOX, resolveControllerModel(0x2dc8, 0, "8BitDo XInput Controller"))
        assertEquals(ControllerModel.NINTENDO, resolveControllerModel(0x2dc8, 0, "8BitDo Switch Pro Controller"))
        assertEquals(ControllerModel.GENERIC, resolveControllerModel(0x2dc8, 0, "8BitDo Pro 2"))
        // Compatibility mode can expose a first-party ID; explicit third-party names take precedence.
        assertEquals(ControllerModel.XBOX, resolveControllerModel(0x045e, 0x028e, "8BitDo SN30 Pro"))
        assertEquals(ControllerModel.NINTENDO, resolveControllerModel(0x057e, 0x2009, "8BitDo Pro 2"))
        assertEquals(ControllerModel.PLAYSTATION, resolveControllerModel(0x0f0d, 0, "DualShock 4"))
    }

    @Test fun nameNormalizationIsIndependentOfTheUiLanguage() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(ControllerModel.SWITCH_PRO, resolveControllerModel(0, 0, "NINTENDO SWITCH PRO CONTROLLER"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun missingDeviceCannotBeCreatedByAVisualOverride() {
        assertEquals(ControllerModel.GENERIC, resolveControllerModel(null, ControllerModel.PS5.key))
        assertEquals(ControllerModel.GENERIC, GamepadConfig(controllerModels = mapOf("old-pad" to ControllerModel.PS5))
            .controllerModel(null))
    }

    @Test fun generationsUseTheirOwnSystemButtonLegends() {
        assertEquals("Start", ControllerModel.PS3.label(GamepadButton.START))
        assertEquals("Select", ControllerModel.PS3.label(GamepadButton.SELECT))
        assertEquals("Options", ControllerModel.PS4.label(GamepadButton.START))
        assertEquals("Share", ControllerModel.PS4.label(GamepadButton.SELECT))
        assertEquals("Create", ControllerModel.PS5.label(GamepadButton.SELECT))
        assertEquals("Start", ControllerModel.XBOX_360.label(GamepadButton.START))
        assertEquals("Back", ControllerModel.XBOX_360.label(GamepadButton.SELECT))
        assertEquals("Menu", ControllerModel.XBOX_ONE.label(GamepadButton.START))
        assertEquals("View", ControllerModel.XBOX_SERIES.label(GamepadButton.SELECT))
        assertEquals("L1", ControllerModel.GENERIC.label(GamepadButton.L1))
        assertEquals("B", ControllerModel.SWITCH_PRO.label(GamepadButton.A))
        assertEquals("✕", ControllerModel.PS5.label(GamepadButton.A))
        assertEquals(GamepadLabels.XBOX, GamepadLabels.forVendor(null)) // Legacy callers stay unchanged.
        assertEquals(GamepadLabels.GENERIC, GamepadLabels.forController(0, 0, "unknown"))
    }

    @Test fun persistedModelKeysAreUniqueAndUnknownKeysStayAutomatic() {
        assertEquals(ControllerModel.entries.size, ControllerModel.entries.map { it.key }.toSet().size)
        ControllerModel.entries.forEach { assertEquals(it, ControllerModel.fromKey(it.key)) }
        assertNull(ControllerModel.fromKey("future-model"))
        assertNull(ControllerModel.fromKey(null))
        assertNull(ControllerModel.GENERIC.assetKey)
        assertNull(ControllerModel.PLAYSTATION.assetKey)
    }
}
