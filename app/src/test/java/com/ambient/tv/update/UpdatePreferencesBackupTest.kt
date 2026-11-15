package com.ambient.tv.update

import android.content.Context
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class UpdatePreferencesBackupTest {
    @Test fun restoredAutomaticChoiceUpdatesLiveStateWithoutStartingACheckOrReplacingHistory() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("automatic", false)
            .putLong("last_attempt", 1234L).putLong("skipped", 777L)
            .putLong("announced", 555L).putString("release", "local cached release").commit()
        val before = prefs.all.toMap()
        val updater = AppUpdater(context)

        AppUpdater.restoreAutomaticUpdates(context, enabled = true)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(AppUpdater.automaticUpdatesEnabled(context))
        assertTrue(updater.state.value.automatic)
        assertFalse(updater.state.value.checking)
        assertFalse(updater.state.value.checked)
        assertEquals(777L, updater.state.value.skipped)
        assertEquals(before + ("automatic" to true), prefs.all)

        AppUpdater.restoreAutomaticUpdates(context, enabled = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(updater.state.value.automatic)
        assertEquals(before, prefs.all)
    }

    @Test fun exportedPreferenceUsesStoredValueAndSurvivesUpdaterRecreation() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(AppUpdater.automaticUpdatesEnabled(context))

        AppUpdater.restoreAutomaticUpdates(context, enabled = true)

        assertTrue(AppUpdater.automaticUpdatesEnabled(context))
        val restored = AppUpdater(context)
        assertTrue(restored.state.value.automatic)
        assertFalse(restored.state.value.checking)
    }
}
