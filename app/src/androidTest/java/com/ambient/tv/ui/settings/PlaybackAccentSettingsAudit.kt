package com.ambient.tv.ui.settings

import android.app.Instrumentation
import android.content.Intent
import com.ambient.tv.ui.catalog.AppearanceSettingsActivity

/** Verifies the production settings draft/save/discard/reload path on a separate debug install. */
internal class PlaybackAccentSettingsAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { runCatching(action).onFailure { failure = it } }
        failure?.let { throw it }
    }

    private fun open(): AppearanceSettingsActivity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, AppearanceSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as AppearanceSettingsActivity

    fun run() {
        var activity = open()
        var original: SettingsUiState? = null
        try {
            instrumentation.waitForIdleSync()
            main {
                val vm = activity.settingsViewModel
                original = vm.savedAppearance
                vm.onAccentColorChanged("custom")
                vm.onCustomAccentHexChanged("#123456")
                vm.onCustomPlaybackAccentHexChanged("#FF77A8")
                check(vm.hasPendingChanges())
                check(vm.savedAppearance.customPlaybackAccentHex == original!!.customPlaybackAccentHex)
                check(vm.commitPending() != null)
                check(!vm.hasPendingChanges())
                check(vm.savedAppearance.customAccentHex == "#123456")
                check(vm.savedAppearance.customPlaybackAccentHex == "#FF77A8")
                vm.onCustomAccentHexChanged("#ABCDEF")
                check(vm.uiState.value.customPlaybackAccentHex == "#FF77A8")
                vm.onCustomPlaybackAccentHexChanged("#0099A0")
                check(vm.hasPendingChanges())
                vm.discardChanges()
                check(vm.uiState.value.customAccentHex == "#123456")
                check(vm.uiState.value.customPlaybackAccentHex == "#FF77A8")
                check(!vm.hasPendingChanges())
                vm.onAccentColorChanged("blue")
                check(vm.commitPending() != null)
                check(vm.savedAppearance.customPlaybackAccentHex == "#FF77A8")
                vm.onAccentColorChanged("custom")
                check(vm.commitPending() != null)
                activity.finish()
            }
            activity = open()
            instrumentation.waitForIdleSync()
            main {
                val restored = activity.settingsViewModel.uiState.value
                check(restored.accentColorKey == "custom")
                check(restored.customAccentHex == "#123456")
                check(restored.customPlaybackAccentHex == "#FF77A8")
            }
            log("PASS real settings: independent custom colors, deferred persistence, Save, Discard, preset round trip and fresh activity reload")
        } finally {
            main {
                original?.let { saved ->
                    val vm = activity.settingsViewModel
                    vm.onAccentColorChanged(saved.accentColorKey)
                    vm.onCustomAccentHexChanged(saved.customAccentHex)
                    vm.onCustomPlaybackAccentHexChanged(saved.customPlaybackAccentHex)
                    check(vm.commitPending() != null)
                }
                activity.finish()
            }
        }
    }
}
