package com.ambient.tv.ui.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DlnaBrowserFocusTest {
    @Test fun failedOrEmptyFirstLevelFolderReturnsFocusToRoot() {
        assertEquals(-1, dlnaParentBreadcrumbIndex(pathSize = 1))
    }

    @Test fun failedOrEmptyNestedFolderReturnsFocusToItsParent() {
        assertEquals(1, dlnaParentBreadcrumbIndex(pathSize = 3))
    }

    @Test fun rootHasNoParentFocusTarget() {
        assertNull(dlnaParentBreadcrumbIndex(pathSize = 0))
    }
}
