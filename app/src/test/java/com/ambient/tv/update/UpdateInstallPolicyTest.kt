package com.ambient.tv.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallPolicyTest {
    // U1
    @Test fun backgroundConfirmationUsesANotificationOnlyFromAndroid14() {
        assertTrue(updateConfirmNeedsNotification(34, appInForeground = false))
        assertFalse(updateConfirmNeedsNotification(34, appInForeground = true))
        assertFalse(updateConfirmNeedsNotification(33, appInForeground = false))
    }

    @Test fun onlyProfileFailuresAreRetriedWithoutTheProfile() {
        val invalid = PackageInstaller.STATUS_FAILURE_INVALID
        assertTrue(updateRetryWithoutProfile(true, invalid, "INSTALL_FAILED_BAD_DEX_METADATA: Invalid dex metadata file"))
        assertTrue(updateRetryWithoutProfile(true, PackageInstaller.STATUS_FAILURE, "Failed to parse base.dm"))
        assertFalse(updateRetryWithoutProfile(false, invalid, "INSTALL_FAILED_BAD_DEX_METADATA"))
        assertFalse(updateRetryWithoutProfile(true, invalid, "INSTALL_PARSE_FAILED_NO_CERTIFICATES"))
        assertFalse(updateRetryWithoutProfile(true, invalid, null))
        assertFalse(updateRetryWithoutProfile(true, PackageInstaller.STATUS_FAILURE_STORAGE, "profile"))
        assertFalse(updateRetryWithoutProfile(true, PackageInstaller.STATUS_FAILURE_CONFLICT, "dex metadata"))
    }
}
