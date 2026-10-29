package com.ambient.tv.ui.common.projectm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectMRuntimeProbeTest {
    @Test fun unsupportedAbiDoesNotTouchNativeLoader() {
        val loaded = mutableListOf<String>()
        val status = probeProjectMRuntime(
            abiSupported = false,
            loadLibrary = { loaded += it },
            readVersion = { "4.1" },
        )
        assertFalse(status.available)
        assertEquals("unsupported ABI", status.error)
        assertTrue(loaded.isEmpty())
    }

    @Test fun optionalCxxPreloadFailureDoesNotDisableProjectM() {
        val loaded = mutableListOf<String>()
        val status = probeProjectMRuntime(
            abiSupported = true,
            loadLibrary = { name ->
                loaded += name
                if (name == "c++_shared") error("optional preload")
            },
            readVersion = { " 4.1.2 " },
        )
        assertTrue(status.available)
        assertEquals("4.1.2", status.version)
        assertEquals(listOf("c++_shared", "projectM-4", "ambientprojectm"), loaded)
    }

    @Test fun requiredNativeLoadFailureKeepsUsefulDiagnostic() {
        val status = probeProjectMRuntime(
            abiSupported = true,
            loadLibrary = { name -> if (name == "projectM-4") throw UnsatisfiedLinkError("missing dep") },
            readVersion = { "4.1" },
        )
        assertFalse(status.available)
        assertTrue(status.error.orEmpty().contains("UnsatisfiedLinkError"))
        assertTrue(status.error.orEmpty().contains("missing dep"))
    }

    @Test fun emptyNativeVersionIsUnavailable() {
        val status = probeProjectMRuntime(
            abiSupported = true,
            loadLibrary = {},
            readVersion = { " " },
        )
        assertFalse(status.available)
        assertEquals("native version probe returned empty", status.error)
    }
}
