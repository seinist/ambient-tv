package com.ambient.tv.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionPresentationPolicyTest {
    @Test fun multipleNamedNonEmptySectionsMustBePreserved() {
        assertTrue(hasMultipleNamedContentSections(listOf("Continue watching" to 3, "Recommended" to 8)))
    }

    @Test fun emptyOrDuplicateLabelsDoNotCreateFakeSubsections() {
        assertFalse(hasMultipleNamedContentSections(listOf("Feed" to 4, "Feed" to 5)))
        assertFalse(hasMultipleNamedContentSections(listOf("" to 4, "Recommended" to 0)))
    }
}
