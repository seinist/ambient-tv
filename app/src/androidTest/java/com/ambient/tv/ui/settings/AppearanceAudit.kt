package com.ambient.tv.ui.settings

import android.app.Instrumentation
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.data.local.HomeHeroStyle
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.common.rememberArtworkSampledAccent
import com.ambient.tv.ui.common.RightPanelChoice
import com.ambient.tv.ui.common.RightPanelValueSlider
import com.ambient.tv.ui.home.HomeHero
import com.ambient.tv.ui.home.HomeHeroContent
import com.ambient.tv.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Production picker and hero with local artwork: no credentials or remote services. */
internal class AppearanceAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var error: Throwable? = null
        instrumentation.runOnMainSync { runCatching(action).onFailure { error = it } }
        error?.let { throw it }
    }
    private fun settle() { Thread.sleep(500); instrumentation.waitForIdleSync() }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); settle() }

    fun run(activity: UiCatalogActivity) {
        val hex = mutableStateOf("#123456")
        val showPicker = mutableStateOf(true)
        val scale = mutableFloatStateOf(1f)
        val style = mutableStateOf(HomeHeroStyle.DEFAULT)
        val hero = MutableStateFlow<HomeHeroContent?>(HomeHeroContent("audit", "Hero audit", metadata = listOf("2026", "TV")))
        val bitmap = android.graphics.Bitmap.createBitmap(96, 64, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(30, 200, 150))
        var sampled = Color.Magenta
        var primary = Color.Unspecified
        var onPrimary = Color.Unspecified
        try {
            main {
                activity.setContent {
                    AmbientTvTheme(uiScale = scale.floatValue, accentColorKey = "custom", customAccentHex = hex.value) {
                        // Match MainActivity's content-colour provider, not Compose's black default.
                        CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        val sample = rememberArtworkSampledAccent(bitmap, true, Color.Magenta)
                        val scheme = MaterialTheme.colorScheme
                        SideEffect { sampled = sample; primary = scheme.primary; onPrimary = scheme.onPrimary }
                        Column(Modifier.fillMaxSize()) {
                            HomeHero(hero, style.value, HomeHeroStyle.DEFAULT_INTENSITY, HomeHeroStyle.DEFAULT_SPREAD, { 1f })
                        }
                        if (showPicker.value) AccentColorPickerDialog(hex.value, { hex.value = it }, { showPicker.value = false })
                        }
                    }
                }
            }
            settle()
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                try {
                    java.io.File(instrumentation.targetContext.filesDir, "audit-accent-picker.png").outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { image.recycle() }
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(hex.value == "#123456") { "Picker failed to focus the custom colour" } }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(hex.value != "#123456") { "Picker D-pad did not change colour" } }
            key(KeyEvent.KEYCODE_BACK)
            main { check(!showPicker.value) }
            for (color in listOf("#000000", "#FFFFFF", "#003366", "#invalid")) {
                main { hex.value = color }
                settle()
                main { check(contrastRatio(primary, onPrimary) >= 4.5f) { "Unreadable accent content: $color" } }
            }
            main { check(sampled != Color.Magenta) { "Artwork sampler fell back instead of decoding software thumbnail" } }
            main { scale.floatValue = 1.2f; hex.value = "#123456"; showPicker.value = true }
            settle()
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(hex.value == "#FFFFFF") { "Last colour row is unreachable at 120% UI scale: ${hex.value}" } }
            key(KeyEvent.KEYCODE_BACK)
            main { scale.floatValue = 1f }
            for (key in HomeHeroStyle.keys) {
                main { style.value = key }
                settle()
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    java.io.File(instrumentation.targetContext.filesDir, "audit-hero-$key.png").outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    var visible = 0
                    for (y in 0 until screenshot.height / 2 step 8) for (x in 0 until screenshot.width step 8) {
                        if ((screenshot.getPixel(x, y) and 0xffffff) > 0x202020) visible++
                    }
                    check(visible > 10) { "Hero $key rendered no visible content" }
                    if (HomeHeroStyle.isFancy(key)) {
                        Thread.sleep(1_600)
                        val later = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try {
                            var changed = 0
                            for (y in 0 until screenshot.height / 2 step 4) for (x in 0 until screenshot.width step 4) {
                                if (screenshot.getPixel(x, y) != later.getPixel(x, y)) changed++
                            }
                            check(changed > 20) { "Hero $key did not animate: $changed changed samples" }
                            java.io.File(instrumentation.targetContext.filesDir, "audit-hero-$key-motion.png").outputStream().use {
                                later.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                            }
                            log("PASS Hero $key visible motion: $changed changed samples")
                        } finally { later.recycle() }
                    }
                } finally { screenshot.recycle() }
            }
            log("PASS custom colour picker D-pad/Back and 120% UI scale, dark/light accent contrast, software artwork sampling and all ${HomeHeroStyle.keys.size} production Hero styles")
            auditIntensityFocus(activity)
        } finally {
            main { activity.setContent {} }
            settle()
            bitmap.recycle()
        }
    }

    private fun auditIntensityFocus(activity: UiCatalogActivity) {
        val intensity = mutableStateOf(60)
        val locked = mutableStateOf(false)
        val first = FocusRequester()
        var beforeFocused = false
        var intensityFocused = false
        var afterFocused = false
        var escapedHorizontalKeys = 0
        main {
            activity.setContent {
                AmbientTvTheme {
                    Column(Modifier.fillMaxSize().padding(40.dp).onKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key in listOf(Key.DirectionLeft, Key.DirectionRight)) {
                            escapedHorizontalKeys++
                        }
                        false
                    }) {
                        RightPanelChoice("Hero style", false, {}, modifier = Modifier.focusRequester(first)
                            .onFocusChanged { beforeFocused = it.isFocused })
                        RightPanelValueSlider(
                            title = androidx.compose.ui.res.stringResource(com.ambient.tv.R.string.settings_home_hero_intensity),
                            value = intensity.value,
                            valueRange = if (locked.value) intensity.value..intensity.value else 0..100,
                            step = 10,
                            valueLabel = "${intensity.value} %",
                            onValueChange = { intensity.value = HomeHeroStyle.normalizeIntensity(it) },
                            modifier = Modifier.onFocusChanged { intensityFocused = it.isFocused },
                        )
                        RightPanelChoice("Hero spread", false, {}, modifier = Modifier
                            .onFocusChanged { afterFocused = it.isFocused })
                    }
                }
            }
        }
        settle()
        main { check(first.requestFocus()) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        main { check(intensityFocused) { "Vertical navigation skipped the intensity row" } }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        main { check(intensity.value == 70 && intensityFocused) }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        main { check(intensity.value == 60 && intensityFocused) }
        repeat(5) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        main { check(intensity.value == 100 && intensityFocused) { "Maximum lost stepper focus" } }
        repeat(11) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
        main { check(intensity.value == 0 && intensityFocused) { "Minimum lost stepper focus" } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        main { check(afterFocused) { "Down did not leave the intensity row" } }
        key(KeyEvent.KEYCODE_DPAD_UP)
        main { check(intensityFocused) { "Up skipped intensity at the minimum" } }
        key(KeyEvent.KEYCODE_DPAD_UP)
        main { check(beforeFocused) }
        main { locked.value = true }
        settle()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        main { check(intensityFocused && intensity.value == 0 && escapedHorizontalKeys == 0) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        main { check(afterFocused) }
        log("PASS Hero intensity row receives vertical focus, Left/Right adjust by 10%, limits retain focus, and disabled actions allow vertical exit")
    }
}
