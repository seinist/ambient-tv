package com.ambient.tv.ui.gamepad

import android.view.InputDevice
import java.util.Locale

/**
 * Physical appearance only. Never use this choice to translate events or change a binding.
 * Family entries deliberately have no model asset: a compatible pad need not have the same shell.
 * [key] is persisted in settings/backups; [assetKey] names a bundled Controllercons illustration.
 */
enum class ControllerModel(
    val key: String,
    val assetKey: String?,
    val displayName: String,
    val labels: GamepadLabels,
) {
    GENERIC("generic", null, "Controller", GamepadLabels.GENERIC),
    XBOX("xbox", null, "Xbox", GamepadLabels.XBOX),
    XBOX_360("xbox-360", "xbox-360", "Xbox 360", GamepadLabels.XBOX),
    XBOX_ONE("xbox-one", "xbox-one", "Xbox One", GamepadLabels.XBOX),
    XBOX_SERIES("xbox-series-x", "xbox-series-x", "Xbox Series X|S", GamepadLabels.XBOX),
    PLAYSTATION("playstation", null, "PlayStation", GamepadLabels.PLAYSTATION),
    PS3("ps3", "ps3", "DualShock 3", GamepadLabels.PLAYSTATION),
    PS4("ps4", "ps4", "DualShock 4", GamepadLabels.PLAYSTATION),
    PS5("ps5", "ps5", "DualSense", GamepadLabels.PLAYSTATION),
    NINTENDO("nintendo", null, "Nintendo", GamepadLabels.NINTENDO),
    SWITCH_PRO("switch-pro", "switch-pro", "Nintendo Switch Pro Controller", GamepadLabels.NINTENDO),
    JOY_CON_LEFT("joy-con-l", "joy-con-l", "Joy-Con (L)", GamepadLabels.NINTENDO),
    JOY_CON_RIGHT("joy-con-r", "joy-con-r", "Joy-Con (R)", GamepadLabels.NINTENDO),
    JOY_CON_PAIR("joy-cons", "joy-cons", "Joy-Con (L/R)", GamepadLabels.NINTENDO),
    SNES("snes", "snes", "Super Nintendo", GamepadLabels.NINTENDO),
    N64("n64", "n64", "Nintendo 64", GamepadLabels.NINTENDO),
    ;

    /** Central buttons differ even between generations of the same controller family. */
    fun label(button: GamepadButton): String = when (button) {
        GamepadButton.START -> when (this) {
            PS3, SNES, N64, PLAYSTATION -> "Start"
            XBOX_ONE, XBOX_SERIES -> "Menu"
            else -> labels.label(button)
        }
        GamepadButton.SELECT -> when (this) {
            PS3, SNES, PLAYSTATION -> "Select"
            PS4 -> "Share"
            PS5 -> "Create"
            XBOX_360 -> "Back"
            XBOX_ONE, XBOX_SERIES -> "View"
            else -> labels.label(button)
        }
        GamepadButton.MODE -> when (this) {
            XBOX_360, XBOX_ONE, XBOX_SERIES -> "Xbox"
            else -> labels.label(button)
        }
        else -> labels.label(button)
    }

    companion object {
        fun fromKey(key: String?): ControllerModel? = entries.firstOrNull { it.key == key }
    }
}

/** A missing controller never creates a physical device from a saved visual preference. */
fun resolveControllerModel(device: InputDevice?, overrideKey: String? = null): ControllerModel =
    if (device == null) ControllerModel.GENERIC
    else ControllerModel.fromKey(overrideKey)
        ?: resolveControllerModel(device.vendorId, device.productId, device.name.orEmpty())

/**
 * Prefer known VID/PID pairs. Explicit names are a fallback for Android drivers omitting IDs;
 * generic "Wireless Controller" and "Pro Controller" names do not identify a physical model.
 * Facts checked against the upstream device tables (not their protocol-to-shell assumptions):
 * https://github.com/torvalds/linux/blob/master/drivers/hid/hid-ids.h
 * https://github.com/libsdl-org/SDL/blob/main/src/joystick/controller_list.h
 */
fun resolveControllerModel(vendorId: Int, productId: Int, name: String): ControllerModel {
    val normalized = name.take(256).lowercase(Locale.ROOT)
        .replace(nonNameCharacters, " ").trim()
    val firstPartyVendor = vendorId == 0 || vendorId == VENDOR_SONY ||
        vendorId == VENDOR_MICROSOFT || vendorId == VENDOR_NINTENDO
    val thirdPartyName = thirdPartyNames.containsMatchIn(normalized)
    if (firstPartyVendor && !thirdPartyName) {
        val exact = when (vendorId) {
            VENDOR_SONY -> when (productId) {
                0x0268 -> ControllerModel.PS3
                0x05c4, 0x09cc, 0x0ba0 -> ControllerModel.PS4
                0x0ce6 -> ControllerModel.PS5
                // Edge and Access have a different shell; keep the family fallback.
                else -> null
            }
            VENDOR_MICROSOFT -> when (productId) {
                0x028e, 0x028f, 0x0291, 0x02a1, 0x02a9, 0x0719 -> ControllerModel.XBOX_360
                0x02d1, 0x02dd, 0x02e0, 0x02ea, 0x02fd, 0x02ff, 0x0b20 -> ControllerModel.XBOX_ONE
                0x0b12, 0x0b13 -> ControllerModel.XBOX_SERIES
                // Adaptive/Elite controllers are not an ordinary Xbox One/Series shell.
                else -> null
            }
            VENDOR_NINTENDO -> when (productId) {
                0x2006 -> ControllerModel.JOY_CON_LEFT
                0x2007 -> ControllerModel.JOY_CON_RIGHT
                0x2008 -> ControllerModel.JOY_CON_PAIR
                0x2009 -> ControllerModel.SWITCH_PRO
                0x2017 -> ControllerModel.SNES
                0x2019 -> ControllerModel.N64
                // Charging grips do not say whether one or two Joy-Cons are attached.
                // Switch 2 hardware also retains its family rather than a Switch 1 shell.
                else -> null
            }
            else -> null
        }
        if (exact != null) return exact

        // A product ID that is present but unknown must not be overridden by a suggestive name.
        if (productId == 0 && (vendorId == 0 || vendorId == VENDOR_SONY)) {
            when {
                dualSenseName.matches(normalized) -> return ControllerModel.PS5
                dualShock4Name.matches(normalized) -> return ControllerModel.PS4
                dualShock3Name.matches(normalized) -> return ControllerModel.PS3
            }
        }
        if (productId == 0 && (vendorId == 0 || vendorId == VENDOR_MICROSOFT)) {
            when {
                xboxSeriesName.matches(normalized) -> return ControllerModel.XBOX_SERIES
                xboxOneName.matches(normalized) -> return ControllerModel.XBOX_ONE
                xbox360Name.matches(normalized) -> return ControllerModel.XBOX_360
            }
        }
        if (productId == 0 && (vendorId == 0 || vendorId == VENDOR_NINTENDO)) {
            when {
                joyConPairName.matches(normalized) -> return ControllerModel.JOY_CON_PAIR
                joyConLeftName.matches(normalized) -> return ControllerModel.JOY_CON_LEFT
                joyConRightName.matches(normalized) -> return ControllerModel.JOY_CON_RIGHT
                switchProName.matches(normalized) -> return ControllerModel.SWITCH_PRO
            }
        }
    }

    // Compatibility identifiers are enough for button legends, never for an exact shell.
    compatibleFamily(vendorId, productId)?.let { return it }
    return when {
        playStationFamily.containsMatchIn(normalized) -> ControllerModel.PLAYSTATION
        nintendoFamily.containsMatchIn(normalized) -> ControllerModel.NINTENDO
        xboxFamily.containsMatchIn(normalized) -> ControllerModel.XBOX
        vendorId == VENDOR_SONY -> ControllerModel.PLAYSTATION
        vendorId == VENDOR_NINTENDO -> ControllerModel.NINTENDO
        vendorId == VENDOR_MICROSOFT -> ControllerModel.XBOX
        else -> ControllerModel.GENERIC
    }
}

private fun compatibleFamily(vendorId: Int, productId: Int): ControllerModel? = when (vendorId) {
    VENDOR_HORI -> when (productId) {
        // Includes XInput devices with PlayStation/Nintendo legends, as identified by SDL.
        0x0009, 0x004d, 0x005f, 0x006a, 0x006e, 0x0085, 0x0086, 0x0088, 0x008b,
        0x0055, 0x005e, 0x0066, 0x0084, 0x0087, 0x008a, 0x009c, 0x00a0, 0x00c9,
        0x00ed, 0x00ee, 0x011c, 0x0123, 0x0162, 0x0164, 0x0163, 0x0184, 0x01b0 ->
            ControllerModel.PLAYSTATION
        0x00c1, 0x0092, 0x00f6, 0x00dc, 0x00aa, 0x00f0 -> ControllerModel.NINTENDO
        0x000a, 0x000c, 0x000d, 0x0016, 0x001b, 0x008c, 0x00db, 0x011e, 0x01b1,
        0x0063, 0x0067, 0x0078, 0x00c5, 0x0150 -> ControllerModel.XBOX
        else -> null
    }
    VENDOR_8BITDO -> when (productId) {
        0x2002, 0x200a, 0x201e, 0x3106, 0x310a -> ControllerModel.XBOX
        else -> null
    }
    else -> null
}

private const val VENDOR_SONY = 0x054c
private const val VENDOR_MICROSOFT = 0x045e
private const val VENDOR_NINTENDO = 0x057e
private const val VENDOR_HORI = 0x0f0d
private const val VENDOR_8BITDO = 0x2dc8
private val nonNameCharacters = Regex("[^a-z0-9]+")
private val thirdPartyNames = Regex("\\b(?:8bitdo|8bit do|hori\\w*|powera|pdp|razer|logitech|gamesir|nacon|gulikit|mayflash)\\b")
private val dualSenseName = Regex("(?:sony )?dualsense(?: wireless)?(?: controller)?")
private val dualShock4Name = Regex("(?:sony )?(?:dualshock ?4(?: wireless)?(?: controller)?|playstation(?: r)? 4 controller)")
private val dualShock3Name = Regex("(?:sony )?(?:dualshock ?3(?: wireless)?(?: controller)?|playstation(?: r)? 3 controller)")
private val xbox360Name = Regex("(?:microsoft )?x ?box 360(?: wireless)?(?: controller| pad)?")
private val xboxOneName = Regex("(?:microsoft )?x ?box one(?: s)?(?: wireless)?(?: controller| pad)?")
private val xboxSeriesName = Regex("(?:microsoft )?x ?box series(?: x| s| x s| s x)(?: wireless)?(?: controller| pad)?")
private val switchProName = Regex("(?:nintendo )?switch pro controller")
private val joyConLeftName = Regex("(?:nintendo )?(?:switch )?joy con (?:l|left)")
private val joyConRightName = Regex("(?:nintendo )?(?:switch )?joy con (?:r|right)")
private val joyConPairName = Regex("(?:nintendo )?(?:switch )?joy (?:cons|con (?:l r|left right)(?: combined)?)")
private val playStationFamily = Regex("\\b(?:playstation|dualshock|dualsense|ps[345])\\b")
private val nintendoFamily = Regex("\\b(?:nintendo|switch|joy con)\\b")
private val xboxFamily = Regex("\\b(?:xbox|x box|xinput)\\b")
