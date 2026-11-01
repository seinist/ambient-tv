package com.ambient.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseUiSessionPolicyTest {
    @Test fun freshProcessRejectsAnOldAndroidBundle() {
        assertFalse(BrowseUiSessionPolicy().create(Any(), hasSavedState = true))
        assertFalse(BrowseUiSessionPolicy().create(Any(), hasSavedState = false))
    }

    @Test fun configurationRecreationKeepsTheSession() {
        val policy = BrowseUiSessionPolicy()
        val first = Any()
        val second = Any()
        assertFalse(policy.create(first, hasSavedState = false))
        policy.destroy(first, changingConfigurations = true)
        assertTrue(policy.create(second, hasSavedState = true))
        policy.destroy(second, changingConfigurations = true)
        assertTrue(policy.create(Any(), hasSavedState = true))
    }

    @Test fun closingTheUiEndsTheSessionEvenWhenTheProcessSurvives() {
        val policy = BrowseUiSessionPolicy()
        val first = Any()
        policy.create(first, hasSavedState = false)
        policy.destroy(first, changingConfigurations = false)
        assertFalse(policy.create(Any(), hasSavedState = true))
    }

    @Test fun anActiveOwnerDoesNotAuthorizeAnUnrelatedBundle() {
        val policy = BrowseUiSessionPolicy()
        policy.create(Any(), hasSavedState = false)
        assertFalse(policy.create(Any(), hasSavedState = true))
    }

    @Test fun configurationPermissionIsConsumedByTheNextCreation() {
        val policy = BrowseUiSessionPolicy()
        val first = Any()
        policy.create(first, hasSavedState = false)
        policy.destroy(first, changingConfigurations = true)
        assertTrue(policy.create(Any(), hasSavedState = true))
        assertFalse(policy.create(Any(), hasSavedState = true))
    }

    @Test fun missingBundleStartsANewSessionDespitePendingRecreation() {
        val policy = BrowseUiSessionPolicy()
        val first = Any()
        policy.create(first, hasSavedState = false)
        policy.destroy(first, changingConfigurations = true)
        assertFalse(policy.create(Any(), hasSavedState = false))
        assertFalse(policy.create(Any(), hasSavedState = true))
    }

    @Test fun aLateTrampolineDestructionCannotCloseItsReplacementSession() {
        val policy = BrowseUiSessionPolicy()
        val trampoline = Any()
        val screen = Any()
        policy.create(trampoline, hasSavedState = false)
        policy.create(screen, hasSavedState = false)
        policy.destroy(trampoline, changingConfigurations = false)
        policy.destroy(screen, changingConfigurations = true)
        assertTrue(policy.create(Any(), hasSavedState = true))
    }

    @Test fun aStaleOwnerCannotGrantRestoration() {
        val policy = BrowseUiSessionPolicy()
        val old = Any()
        val current = Any()
        policy.create(old, hasSavedState = false)
        policy.create(current, hasSavedState = false)
        policy.destroy(old, changingConfigurations = true)
        assertFalse(policy.create(Any(), hasSavedState = true))
    }

    @Test fun ownerMatchingUsesIdentityRatherThanEquality() {
        data class Owner(val value: Int)
        val policy = BrowseUiSessionPolicy()
        val owner = Owner(1)
        policy.create(owner, hasSavedState = false)
        policy.destroy(Owner(1), changingConfigurations = true)
        assertFalse(policy.create(Any(), hasSavedState = true))
    }
}

class LauncherFreshUiSessionTest {
    private val launcher = setOf(android.content.Intent.CATEGORY_LAUNCHER)

    @org.junit.Test fun launcherAfterRootBackStartsFresh() {
        org.junit.Assert.assertTrue(launcherStartsFreshUiSession(true, android.content.Intent.ACTION_MAIN, launcher))
    }

    @org.junit.Test fun otherReturnsKeepTheSession() {
        org.junit.Assert.assertFalse(launcherStartsFreshUiSession(false, android.content.Intent.ACTION_MAIN, launcher))
        org.junit.Assert.assertFalse(launcherStartsFreshUiSession(true, android.content.Intent.ACTION_VIEW, launcher))
        org.junit.Assert.assertFalse(launcherStartsFreshUiSession(true, android.content.Intent.ACTION_MAIN, null))
    }
}
