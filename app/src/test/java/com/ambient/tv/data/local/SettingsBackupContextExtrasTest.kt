package com.ambient.tv.data.local

import android.content.Context
import android.os.Looper
import com.ambient.tv.livetv.LiveTvReminderScheduler
import com.ambient.tv.ui.common.projectm.ProjectMFavorites
import com.ambient.tv.update.AppUpdater
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class SettingsBackupContextExtrasTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearContextStores() {
        for (name in listOf("live_tv_reminders", "app_updates", "projectm_visualizer")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun manager(withContext: Boolean = true): SettingsBackupManager {
        val preferences = AppPreferences(memoryPreferences())
        return SettingsBackupManager(
            preferences, TokenStore(memoryPreferences(), preferences), null,
            context = context.takeIf { withContext },
        )
    }

    @Test fun reminderAndUpdateChoicesRoundTripAndRefreshAnExistingUpdater() {
        LiveTvReminderScheduler.setAutoSwitch(context, true)
        AppUpdater.restoreAutomaticUpdates(context, true)
        val raw = manager().exportJson()

        LiveTvReminderScheduler.setAutoSwitch(context, false)
        AppUpdater.restoreAutomaticUpdates(context, false)
        val runningUpdater = AppUpdater(context)
        manager().importJson(raw)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(LiveTvReminderScheduler.autoSwitch(context))
        assertTrue(AppUpdater.automaticUpdatesEnabled(context))
        assertTrue(runningUpdater.state.value.automatic)
        assertFalse(runningUpdater.state.value.checking)
    }

    @Test fun explicitFalseAndEmptyChoicesAreExportedAndReplaceTheReceiversStores() {
        val exporter = manager()
        val raw = exporter.exportJson()
        val exported = exporter.previewJson(raw).extras?.portable
        assertEquals(false, exported?.liveTvReminderAutoSwitch)
        assertEquals(false, exported?.autoUpdateCheck)
        assertEquals(emptySet<String>(), exported?.projectmFavorites)

        LiveTvReminderScheduler.setAutoSwitch(context, true)
        AppUpdater.restoreAutomaticUpdates(context, true)
        // This also exercises clearing a favorite left behind by an older preset bundle.
        context.getSharedPreferences("projectm_visualizer", Context.MODE_PRIVATE).edit()
            .putStringSet("favorites", setOf("retired/Preset.milk")).commit()
        manager().importJson(raw)

        assertFalse(LiveTvReminderScheduler.autoSwitch(context))
        assertFalse(AppUpdater.automaticUpdatesEnabled(context))
        assertTrue(ProjectMFavorites(context).all().isEmpty())
        assertTrue(context.getSharedPreferences("projectm_visualizer", Context.MODE_PRIVATE)
            .getStringSet("favorites", emptySet()).orEmpty().isEmpty())
    }

    @Test fun missingContextAndLegacyBackupsPreserveTheReceivingContextStores() {
        val noContextExport = manager(withContext = false).exportJson()
        val noContextSettings = manager().previewJson(noContextExport).extras?.portable
        assertNull(noContextSettings?.liveTvReminderAutoSwitch)
        assertNull(noContextSettings?.autoUpdateCheck)
        assertNull(noContextSettings?.projectmFavorites)

        for (raw in listOf(
            noContextExport,
            """{"schemaVersion":79,"exportedAtEpochMs":0}""",
            """{"schemaVersion":80,"exportedAtEpochMs":0,"extras":{"portable":{}}}""",
        )) {
            LiveTvReminderScheduler.setAutoSwitch(context, true)
            AppUpdater.restoreAutomaticUpdates(context, true)
            context.getSharedPreferences("projectm_visualizer", Context.MODE_PRIVATE).edit()
                .putStringSet("favorites", setOf("local/Preset.milk")).commit()
            manager().importJson(raw)
            assertTrue(LiveTvReminderScheduler.autoSwitch(context))
            assertTrue(AppUpdater.automaticUpdatesEnabled(context))
            assertEquals(setOf("local/Preset.milk"), context.getSharedPreferences("projectm_visualizer", Context.MODE_PRIVATE)
                .getStringSet("favorites", emptySet()))
        }
    }
}
