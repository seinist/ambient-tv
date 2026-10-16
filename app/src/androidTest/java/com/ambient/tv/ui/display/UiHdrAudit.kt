package com.ambient.tv.ui.display

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.player.HdrStandard
import com.ambient.tv.ui.player.PlaybackHandle
import com.ambient.tv.ui.player.PlaybackStatus
import com.ambient.tv.ui.player.backend.MpvBackendAvailability
import com.ambient.tv.ui.player.backend.MpvNativeBridge
import com.ambient.tv.ui.settings.UiHdrSettings
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.isTelevision
import com.ambient.tv.ui.theme.AmbientTvTheme
import java.io.File

/** Exercises the production HDR UI on either an SDR TV emulator or a physical handset. */
class UiHdrAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        Thread.sleep(300)
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.filesDir, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun captureSurfaceFlinger() {
        val surfaceFlinger = shell("dumpsys SurfaceFlinger")
        File(instrumentation.targetContext.filesDir, "ui-hdr-handset-surfaceflinger.txt").writeText(
            surfaceFlinger.lineSequence().filter { line ->
                line.contains("hdraudit", ignoreCase = true) ||
                    line.contains("dataspace=", ignoreCase = true) ||
                    line.contains("edrSdrRatio", ignoreCase = true) ||
                    line.contains("Current color mode", ignoreCase = true)
            }.joinToString("\n"),
        )
    }

    fun run(tmdbOnly: Boolean = false) {
        check(instrumentation.targetContext.packageName.endsWith(".hdraudit")) { "Use isolated .hdraudit app" }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            UiCatalogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        main {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }
        if (tmdbOnly) {
            try { com.ambient.tv.trailers.TrailerCatalogAudit(instrumentation, log).run(activity) }
            finally { main { activity.finish() } }
            return
        }
        val mode = mutableStateOf("auto")
        val conversion = mutableStateOf(false)
        val dialog = mutableStateOf(false)
        val focus = FocusRequester()
        var state: UiHdrState? = null
        var dialogWindow: android.view.Window? = null
        try {
            main {
                activity.setContent {
                    val hdr = rememberUiHdrState(activity, mode.value, conversion.value)
                    SideEffect { state = hdr }
                    CompositionLocalProvider(
                        LocalUiHdrState provides hdr,
                        LocalHandheldUi provides !isTelevision(instrumentation.targetContext),
                    ) {
                        AmbientTvTheme {
                            CompositionLocalProvider(
                                androidx.tv.material3.LocalContentColor provides
                                    androidx.tv.material3.MaterialTheme.colorScheme.onSurface,
                            ) {
                                Column(Modifier.fillMaxSize().padding(40.dp).focusRequester(focus)) {
                                    UiHdrSettings(mode = mode.value, sdrToHdrEnabled = conversion.value,
                                        onSdrToHdrChanged = { conversion.value = it }, onModeChanged = { mode.value = it })
                                }
                                if (dialog.value) AppDialog(title = "HDR fallback audit", onDismiss = { dialog.value = false }) {
                                    val view = LocalView.current
                                    SideEffect { dialogWindow = (view.parent as? DialogWindowProvider)?.window }
                                }
                            }
                        }
                    }
                }
            }
            Thread.sleep(1_500)
            if (!isTelevision(instrumentation.targetContext)) {
                runHandheldHdrWindowProof(activity)
                return
            }
            main {
                check(state != null && state!!.headroom == 1f && !state!!.failed)
                check(activity.window.colorMode == ActivityInfo.COLOR_MODE_DEFAULT)
                val facts = uiHdrEligibility(activity.window, activity.window.decorView.display, "auto", HdrStandard.HDR10)
                check(!facts.allowed) { "This audit expects an SDR emulator" }
                check(focus.requestFocus())
            }
            main {
                val display = activity.window.decorView.display
                check(!hdrInterfaceChoiceVisible(android.os.Build.VERSION.SDK_INT, display?.isHdr == true, display?.isWideColorGamut == true))
                check(!sdrToHdrSwitchVisible(SdrToHdrSwitchFacts(
                    android.os.Build.VERSION.SDK_INT, display?.isHdr == true, display?.isWideColorGamut == true,
                    "auto", false, null, null,
                )))
            }
            log("PASS Auto keeps SDR and hides HDR controls when the display cannot apply them")
            main { dialog.value = true }
            Thread.sleep(700)
            main {
                check(dialogWindow != null)
                check(dialogWindow!!.colorMode == ActivityInfo.COLOR_MODE_DEFAULT)
                check(state!!.headroom == 1f)
            }
            key(KeyEvent.KEYCODE_BACK)
            main { check(!dialog.value); activity.setContent {} }
            Thread.sleep(300)
            main { check(activity.window.colorMode == ActivityInfo.COLOR_MODE_DEFAULT) }
            log("PASS production dialog and root disposal preserve SDR; no video/display settings changed")
            main {
                val requests = mutableListOf<SdrToHdrRequest?>()
                val fake = object : PlaybackHandle {
                    override fun updateSdrToHdr(request: SdrToHdrRequest?): SdrToHdrStatus {
                        requests += request
                        return if (request == null) SdrToHdrStatus.OFF else SdrToHdrStatus.ACTIVE
                    }
                    override fun status() = PlaybackStatus()
                    override fun play() = Unit
                    override fun pause() = Unit
                    override fun seekTo(positionMs: Long) = Unit
                    override fun release() = Unit
                }
                val hdr = UiHdrState(activity.window)
                val request = SdrToHdrRequest(400, 0, 1)
                hdr.updateVideoConversion(fake, request, true)
                hdr.openPopup()
                check(hdr.suspended && requests.last() == null)
                hdr.updateVideoConversion(fake, request, true)
                check(requests.last() == null) { "Popup must veto even a stale non-null conversion request" }
                hdr.closePopup()
                hdr.updateVideoConversion(fake, request, false)
                check(requests.last() == null && hdr.sdrToHdrStatus == SdrToHdrStatus.OFF)
                hdr.updateVideoConversion(fake, request, true)
                hdr.updateVideoConversion(null, null, true)
                check(requests.last() == null)
                hdr.updateVideoConversion(fake, request, true)
                hdr.reset()
                check(requests.last() == null && hdr.sdrToHdrStatus == SdrToHdrStatus.OFF)
            }
            log("PASS root suspension, renderer handoff and disposal revoke the video conversion request")
            checkNativeConversionOptions()
            com.ambient.tv.ui.player.SdrHdrOutputAudit(instrumentation, log).run(activity)
            com.ambient.tv.ui.player.auditSdrHdrShader(log)
            if (android.os.Build.VERSION.SDK_INT >= 34) AmbientHdrAudit(instrumentation, log).run(activity)
            ProjectMHdrAudit(instrumentation, log).run(activity)
            ScreensaverHdrAudit(instrumentation, log).run(activity)
            com.ambient.tv.ui.settings.AppearanceAudit(instrumentation, log).run(activity)
            if (!isTelevision(instrumentation.targetContext)) runHandheldHdrWindowProof(activity)
            com.ambient.tv.trailers.TrailerCatalogAudit(instrumentation, log).run(activity)
        } finally {
            main { activity.finish() }
        }
    }

    /** Real handset proof: actual display capabilities, HDR Window, dialog propagation and SDR rollback. */
    private fun runHandheldHdrWindowProof(activity: UiCatalogActivity) {
        val display = checkNotNull(activity.window.decorView.display)
        val supported = display.mode.supportedHdrTypes.toSet()
        val source = when {
            android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in supported -> HdrStandard.HDR10_PLUS
            android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 in supported -> HdrStandard.HDR10
            android.view.Display.HdrCapabilities.HDR_TYPE_HLG in supported -> HdrStandard.HLG
            android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in supported -> HdrStandard.DOLBY_VISION
            else -> error("Handset display mode advertises no supported HDR source: $supported")
        }
        lateinit var state: UiHdrState
        var stateReady = false
        var dialogWindow: android.view.Window? = null
        val dialog = mutableStateOf(false)
        try {
            main {
                state = UiHdrState(activity.window)
                stateReady = true
                activity.setContent {
                    CompositionLocalProvider(
                        LocalHandheldUi provides true,
                        LocalUiHdrState provides state,
                    ) {
                        AmbientTvTheme {
                            CompositionLocalProvider(
                                androidx.tv.material3.LocalContentColor provides
                                    androidx.tv.material3.MaterialTheme.colorScheme.onSurface,
                            ) {
                                Box(Modifier.fillMaxSize()) {
                                    Column(
                                        Modifier.fillMaxSize().padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        UiHdrSettings(mode = "auto", sdrToHdrEnabled = false, onSdrToHdrChanged = {}, onModeChanged = {})
                                        Box(
                                            Modifier.size(120.dp)
                                                .background(uiHdrHighlight(Color(0xFFE5A00D), state.headroom)),
                                            contentAlignment = Alignment.Center,
                                        ) {}
                                    }
                                    if (dialog.value) AppDialog(
                                        title = "Handset HDR dialog",
                                        onDismiss = { dialog.value = false },
                                    ) {
                                        val view = LocalView.current
                                        SideEffect { dialogWindow = (view.parent as? DialogWindowProvider)?.window }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Thread.sleep(700)
            val raw = main { uiHdrEligibility(activity.window, display, "auto", source) }
            val facts = raw
            main {
                check(raw.displayHdr && raw.wideColor && raw.sourceSupported) { "Incomplete real HDR capabilities: $raw" }
            }
            if (!facts.allowed) {
                val platformColorMode = main {
                    activity.window.colorMode = ActivityInfo.COLOR_MODE_HDR
                    val accepted = activity.window.colorMode
                    activity.window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                    accepted
                }
                main { state.update(facts, "handset:$source:${display.mode.modeId}") }
                Thread.sleep(1_100)
                main {
                    state.update(facts, "handset:$source:${display.mode.modeId}")
                    check(!state.failed && state.headroom == 1f)
                    check(activity.window.colorMode == ActivityInfo.COLOR_MODE_DEFAULT)
                }
                screenshot("ui-hdr-handset-safe-sdr.png")
                captureSurfaceFlinger()
                main { dialog.value = true }
                Thread.sleep(700)
                main {
                    checkNotNull(dialogWindow)
                    check(dialogWindow!!.colorMode == ActivityInfo.COLOR_MODE_DEFAULT)
                }
                screenshot("ui-hdr-handset-dialog-sdr.png")
                main {
                    dialog.value = false
                    state.openPopup()
                    check(state.suspended && state.headroom == 1f)
                    state.closePopup()
                    state.update(facts.copy(automatic = false), null)
                    check(!state.failed && state.headroom == 1f)
                    check(activity.window.colorMode == ActivityInfo.COLOR_MODE_DEFAULT)
                }
                log("PASS handset safely keeps HDR UI in SDR: source=$source types=$supported " +
                    "rawHeadroom=${raw.headroom}, platformColorModeAfterHdrRequest=$platformColorMode, " +
                    "dialog/popup/automatic-off remain exact SDR")
                return
            }
            main { state.update(facts, "handset:$source:${display.mode.modeId}") }
            Thread.sleep(1_100)
            main {
                state.update(facts, "handset:$source:${display.mode.modeId}")
                check(!state.failed && state.headroom == UI_HDR_HEADROOM_CAP) {
                    "HDR controller did not activate: failed=${state.failed} headroom=${state.headroom} " +
                        "windowColorMode=${activity.window.colorMode} facts=$facts"
                }
                check(activity.window.colorMode == ActivityInfo.COLOR_MODE_HDR)
            }
            Thread.sleep(300)
            screenshot("ui-hdr-handset-active.png")
            captureSurfaceFlinger()
            main { dialog.value = true }
            Thread.sleep(700)
            main {
                checkNotNull(dialogWindow)
                check(dialogWindow!!.colorMode == ActivityInfo.COLOR_MODE_HDR)
            }
            screenshot("ui-hdr-handset-dialog.png")
            main { dialog.value = false }
            Thread.sleep(300)
            main {
                check(activity.window.colorMode == ActivityInfo.COLOR_MODE_HDR)
                state.openPopup()
                check(state.suspended && state.headroom == 1f)
                check(activity.window.colorMode == ActivityInfo.COLOR_MODE_DEFAULT)
                state.update(facts, "handset:$source:${display.mode.modeId}")
                check(state.headroom == 1f)
                state.closePopup()
                state.update(facts, "handset:$source:${display.mode.modeId}")
            }
            Thread.sleep(1_100)
            main {
                state.update(facts, "handset:$source:${display.mode.modeId}")
                check(state.headroom == UI_HDR_HEADROOM_CAP && activity.window.colorMode == ActivityInfo.COLOR_MODE_HDR)
                state.update(facts.copy(automatic = false), null)
                check(state.headroom == 1f && activity.window.colorMode == ActivityInfo.COLOR_MODE_DEFAULT)
            }
            log("PASS real handset HDR UI: source=$source types=$supported rawHeadroom=${raw.headroom}, capped HDR window, dialog propagation, popup suspension and exact SDR rollback")
        } finally {
            main {
                if (stateReady) state.reset()
                activity.setContent {}
            }
        }
    }

    /** Tests the packaged binary's options, without pretending an SDR emulator is an HDR TV. */
    private fun checkNativeConversionOptions() {
        check(MpvBackendAvailability.nativeRuntimeAvailable)
        val handle = MpvNativeBridge.nativeCreate()
        check(handle != 0L)
        try {
            check(MpvNativeBridge.nativeSetOptionString(handle, "vo", "null") >= 0)
            check(MpvNativeBridge.nativeSetOptionString(handle, "ao", "null") >= 0)
            check(MpvNativeBridge.nativeInitialize(handle) >= 0)
            val options = linkedMapOf("target-colorspace-hint-strict" to "yes",
                "target-colorspace-hint" to "yes", "target-colorspace-hint-mode" to "target",
                "target-prim" to "bt.2020", "target-trc" to "pq", "target-peak" to "400",
                "tone-mapping" to "spline", "inverse-tone-mapping" to "yes")
            val original = options.keys.associateWith {
                checkNotNull(MpvNativeBridge.nativeGetPropertyString(handle, it)) { "Bundled MPV lacks $it" }
            }
            options.forEach { (name, value) ->
                check(MpvNativeBridge.nativeSetPropertyString(handle, name, value) >= 0) { "MPV rejected $name" }
            }
            options.keys.reversed().forEach { name ->
                check(MpvNativeBridge.nativeSetPropertyString(handle, name, original.getValue(name)) >= 0)
                check(MpvNativeBridge.nativeGetPropertyString(handle, name) == original.getValue(name))
            }
            log("PASS packaged MPV supports conversion options and exact restoration (no HDR output claimed)")
        } finally {
            MpvNativeBridge.nativeDestroy(handle)
        }
    }
}
