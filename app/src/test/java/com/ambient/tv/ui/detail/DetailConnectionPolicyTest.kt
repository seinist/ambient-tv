package com.ambient.tv.ui.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailConnectionPolicyTest {
    @Test fun anotherPageReachingTheServiceDoesNotUnlockCachedMetadata() {
        val policy = DetailConnectionPolicy()
        assertFalse(policy.isVerified("plex:account:network1", serviceVerified = true))
        assertTrue(policy.confirmMetadata("plex:account:network1", "plex:account:network1", serviceConfirmed = true))
        assertTrue(policy.isVerified("plex:account:network1", serviceVerified = true))
    }

    @Test fun metadataWithoutAuthenticatedServiceProofNeverUnlocksDetails() {
        val policy = DetailConnectionPolicy()
        assertFalse(policy.confirmMetadata("plex:account:network1", "plex:account:network1", serviceConfirmed = false))
        assertFalse(policy.isVerified("plex:account:network1", serviceVerified = true))
    }

    @Test fun aFailedRefreshKeepsThePreviewLockedEvenAfterBroadHostRecovery() {
        val policy = DetailConnectionPolicy()
        policy.confirmMetadata("service", "service", serviceConfirmed = true)
        policy.failed()
        assertFalse(policy.isVerified("service", serviceVerified = true))
        assertTrue(policy.confirmMetadata("service", "service", serviceConfirmed = true))
        assertTrue(policy.isVerified("service", serviceVerified = true))
    }

    @Test fun networkAccountAndServiceChangesRequireNewMetadata() {
        for (provider in listOf("plex", "jellyfin", "emby", "files:source", "dlna:server")) {
          val original = "$provider:accountA:network1"
          for (changed in listOf("$provider:accountB:network1", "$provider:accountA:network2", "another-service:accountA:network1")) {
            val policy = DetailConnectionPolicy()
            policy.confirmMetadata(original, original, true)
            assertFalse(policy.isVerified(changed, serviceVerified = true))
            assertFalse(policy.confirmMetadata(original, changed, serviceConfirmed = true))
            assertFalse(policy.isVerified(changed, serviceVerified = true))
          }
        }
    }

    @Test fun anInvalidatedServiceProofCannotResurrectTheOldDetail() {
        val policy = DetailConnectionPolicy()
        policy.confirmMetadata("service", "service", true)
        assertFalse(policy.isVerified("service", serviceVerified = false))
        assertFalse(policy.isVerified("service", serviceVerified = true))
    }

    @Test fun aLateOldAccountReadCannotReplaceNewAccountConfirmation() {
        val policy = DetailConnectionPolicy()
        policy.confirmMetadata("current", "current", true)
        assertFalse(policy.confirmMetadata("old", "current", true))
        assertTrue(policy.isVerified("current", serviceVerified = true))
    }
}
