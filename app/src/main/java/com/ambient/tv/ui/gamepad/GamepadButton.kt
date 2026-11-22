package com.ambient.tv.ui.gamepad

import android.view.KeyEvent

/**
 * The buttons of a standard controller as Android reports them. Stored by [key]; never rename
 * one. Labels follow the controller's family ([GamepadLabels]); A is the bottom face button on
 * Xbox-style pads and Cross on PlayStation pads.
 */
enum class GamepadButton(val key: String, val keyCode: Int) {
    A("a", KeyEvent.KEYCODE_BUTTON_A),
    B("b", KeyEvent.KEYCODE_BUTTON_B),
    X("x", KeyEvent.KEYCODE_BUTTON_X),
    Y("y", KeyEvent.KEYCODE_BUTTON_Y),
    L1("l1", KeyEvent.KEYCODE_BUTTON_L1),
    R1("r1", KeyEvent.KEYCODE_BUTTON_R1),
    L2("l2", KeyEvent.KEYCODE_BUTTON_L2),
    R2("r2", KeyEvent.KEYCODE_BUTTON_R2),
    THUMB_L("l3", KeyEvent.KEYCODE_BUTTON_THUMBL),
    THUMB_R("r3", KeyEvent.KEYCODE_BUTTON_THUMBR),
    START("start", KeyEvent.KEYCODE_BUTTON_START),
    SELECT("select", KeyEvent.KEYCODE_BUTTON_SELECT),
    MODE("mode", KeyEvent.KEYCODE_BUTTON_MODE),
    ;

    companion object {
        fun fromKey(key: String): GamepadButton? = entries.firstOrNull { it.key == key }
        fun fromKeyCode(keyCode: Int): GamepadButton? = entries.firstOrNull { it.keyCode == keyCode }
    }
}

/**
 * Key codes only controllers send: the named buttons plus the generic BUTTON_1…16. Constants
 * inline (no framework call), so backups can check codes outside Android too.
 */
fun isGamepadButtonKeyCode(keyCode: Int): Boolean =
    keyCode in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE ||
        keyCode in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16

/** Button names printed on the controller in front of the user. */
enum class GamepadLabels {
    /** Android's neutral button names; no physical brand was identified. */
    GENERIC,
    XBOX, PLAYSTATION,
    /** Android maps Nintendo pads by position: the bottom button (labelled B) is BUTTON_A. */
    NINTENDO;

    fun label(button: GamepadButton): String = when (this) {
        GENERIC -> when (button) {
            GamepadButton.A -> "A"
            GamepadButton.B -> "B"
            GamepadButton.X -> "X"
            GamepadButton.Y -> "Y"
            GamepadButton.L1 -> "L1"
            GamepadButton.R1 -> "R1"
            GamepadButton.L2 -> "L2"
            GamepadButton.R2 -> "R2"
            GamepadButton.THUMB_L -> "L3"
            GamepadButton.THUMB_R -> "R3"
            GamepadButton.START -> "Start"
            GamepadButton.SELECT -> "Select"
            GamepadButton.MODE -> "Home"
        }
        XBOX -> when (button) {
            GamepadButton.A -> "A"
            GamepadButton.B -> "B"
            GamepadButton.X -> "X"
            GamepadButton.Y -> "Y"
            GamepadButton.L1 -> "LB"
            GamepadButton.R1 -> "RB"
            GamepadButton.L2 -> "LT"
            GamepadButton.R2 -> "RT"
            GamepadButton.THUMB_L -> "L3"
            GamepadButton.THUMB_R -> "R3"
            GamepadButton.START -> "Start"
            GamepadButton.SELECT -> "Select"
            GamepadButton.MODE -> "Home"
        }
        PLAYSTATION -> when (button) {
            GamepadButton.A -> "✕"
            GamepadButton.B -> "○"
            GamepadButton.X -> "□"
            GamepadButton.Y -> "△"
            GamepadButton.L1 -> "L1"
            GamepadButton.R1 -> "R1"
            GamepadButton.L2 -> "L2"
            GamepadButton.R2 -> "R2"
            GamepadButton.THUMB_L -> "L3"
            GamepadButton.THUMB_R -> "R3"
            GamepadButton.START -> "Options"
            GamepadButton.SELECT -> "Create"
            GamepadButton.MODE -> "PS"
        }
        NINTENDO -> when (button) {
            GamepadButton.A -> "B"
            GamepadButton.B -> "A"
            GamepadButton.X -> "Y"
            GamepadButton.Y -> "X"
            GamepadButton.L1 -> "L"
            GamepadButton.R1 -> "R"
            GamepadButton.L2 -> "ZL"
            GamepadButton.R2 -> "ZR"
            GamepadButton.THUMB_L -> "L3"
            GamepadButton.THUMB_R -> "R3"
            GamepadButton.START -> "+"
            GamepadButton.SELECT -> "−"
            GamepadButton.MODE -> "Home"
        }
    }

    companion object {
        private const val VENDOR_SONY = 0x054C
        private const val VENDOR_NINTENDO = 0x057E

        fun forVendor(vendorId: Int?): GamepadLabels = when (vendorId) {
            VENDOR_SONY -> PLAYSTATION
            VENDOR_NINTENDO -> NINTENDO
            else -> XBOX
        }

        /** Use full device evidence in new presentation code; vendor-only callers stay compatible. */
        fun forController(vendorId: Int, productId: Int, name: String): GamepadLabels =
            resolveControllerModel(vendorId, productId, name).labels
    }
}
