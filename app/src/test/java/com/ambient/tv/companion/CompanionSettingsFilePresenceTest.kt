package com.ambient.tv.companion

import android.app.Application
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.data.local.SettingsBackupManager
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionSettingsFilePresenceTest {
    private fun service(): CompanionSettingsService {
        val preferences = AppPreferences(memoryPreferences())
        val backups = SettingsBackupManager(preferences, TokenStore(memoryPreferences(), preferences))
        return CompanionSettingsService(RuntimeEnvironment.getApplication(), backups)
    }

    @Test fun receivingAFileKeepsOmittedAndExplicitlyEmptyProfileMapsDistinct() {
        val service = service()
        val schema = SettingsBackupManager.CURRENT_SCHEMA_VERSION
        val omitted = service.prepareFile("""{"schemaVersion":$schema,"exportedAtEpochMs":0}""", false)
        assertFalse("titleVideoSettings" in CompanionBackupPolicy.presentFields(omitted))
        assertFalse("scopedVideoSettings" in CompanionBackupPolicy.presentFields(omitted))

        val explicit = CompanionBackupPolicy.prepare(SettingsBackup(schemaVersion = schema, exportedAtEpochMs = 0), false)
        val received = service.prepareFile(explicit, false)
        assertTrue("titleVideoSettings" in CompanionBackupPolicy.presentFields(received))
        assertTrue("scopedVideoSettings" in CompanionBackupPolicy.presentFields(received))
    }

    @Test fun preservingFieldPresenceDoesNotBypassTheIncomingSizeLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            service().prepareFile(" ".repeat(CompanionBackupPolicy.MAX_BYTES + 1), false)
        }
    }
}
