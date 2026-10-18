package com.ambient.tv.tvheadend.data

import org.junit.Assert.*
import org.junit.Test

class TvheadendStoredFallbackTest {
    @Test fun storedDataReplacesOfflineAndServerErrorsButNotARejectedLogin() {
        assertTrue(tvhStoredDataMayReplace(java.net.SocketTimeoutException()))
        assertTrue(tvhStoredDataMayReplace(java.io.IOException("unreachable")))
        assertTrue(tvhStoredDataMayReplace(TvheadendHttpException(503, "starting")))
        assertFalse(tvhStoredDataMayReplace(TvheadendHttpException(401, "login")))
        assertFalse(tvhStoredDataMayReplace(TvheadendHttpException(403, "forbidden")))
    }
}
