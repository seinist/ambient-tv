package com.ambient.tv.ui.games

import org.junit.Assert.*
import org.junit.Test

class GameSerializationPolicyTest {
    @Test fun incompleteOrVariableStatesRemainUsefulForManualSavesButNotFrameExactFeatures() {
        for (quirk in listOf(GameSerializationPolicy.INCOMPLETE, GameSerializationPolicy.VARIABLE_SIZE)) {
            val policy = GameSerializationPolicy(quirk)
            assertTrue(policy.persistentStates)
            assertFalse(policy.frameExact)
        }
    }

    @Test fun singleSessionStatesMustNeverBeOfferedAsPersistentCheckpoints() {
        val policy = GameSerializationPolicy(GameSerializationPolicy.SINGLE_SESSION)
        assertFalse(policy.persistentStates)
        assertFalse(policy.frameExact)
    }

    @Test fun initializationRequirementAllowsRetryWithoutDisablingSupportedFeatures() {
        val policy = GameSerializationPolicy(GameSerializationPolicy.MUST_INITIALIZE)
        assertTrue(policy.mustInitialize)
        assertTrue(policy.persistentStates)
        assertTrue(policy.frameExact)
        assertFalse(GameSerializationPolicy().mustInitialize)
    }
}
