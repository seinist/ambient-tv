package com.ambient.tv.companion

import com.ambient.tv.data.local.SettingsBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionBackupClassificationTest {
    @Test fun everyBackupFieldIsClassified() {
        val d = SettingsBackup.serializer().descriptor
        val fields = (0 until d.elementsCount).map(d::getElementName).toSet()
        assertEquals(emptySet<String>(), fields - CompanionBackupPolicy.classifiedFields)
    }

    @Test fun sidebarDividersTravelWithTheMenuOrder() {
        assertTrue("sidebarDividersAfter" in CompanionBackupPolicy.classifiedFields)
    }
}
