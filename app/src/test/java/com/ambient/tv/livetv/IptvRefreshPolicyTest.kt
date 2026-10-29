package com.ambient.tv.livetv

import org.junit.Assert.*
import org.junit.Test

class IptvRefreshPolicyTest {
    @Test fun ordinaryReadsAllowTheDiskTtlButManualRefreshBypassesIt() {
        val policy = IptvRefreshPolicy()
        assertFalse(policy.downloadGuide(force = false))
        assertTrue(policy.downloadGuide(force = true))
    }

    @Test fun reconnectInvalidationForcesTheGuideEvenForANormalCaller() {
        val policy = IptvRefreshPolicy()
        policy.invalidate()
        assertTrue(policy.downloadGuide(force = false))
        policy.guideLoaded(policy.revision, fromFallback = false)
        assertFalse(policy.downloadGuide(force = false))
    }

    @Test fun diskFallbackDoesNotSatisfyTheRequiredDownload() {
        val policy = IptvRefreshPolicy()
        policy.invalidate()
        policy.guideLoaded(policy.revision, fromFallback = true)
        assertTrue(policy.downloadGuide(force = false))
    }

    @Test fun responseStartedBeforeInvalidationCannotRestoreTheDiskTtl() {
        val policy = IptvRefreshPolicy()
        val oldRevision = policy.revision
        policy.invalidate()
        policy.guideLoaded(oldRevision, fromFallback = false)
        assertTrue(policy.downloadGuide(force = false))
    }

    @Test fun obsoleteFallbackCannotMakeAFreshlyRecoveredGuideStaleAgain() {
        val policy = IptvRefreshPolicy()
        val oldRevision = policy.revision
        policy.invalidate()
        policy.guideLoaded(policy.revision, fromFallback = false)
        policy.guideLoaded(oldRevision, fromFallback = true)
        assertFalse(policy.downloadGuide(force = false))
    }
}
