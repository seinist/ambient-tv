package com.ambient.tv.modules

import org.junit.Assert.*
import org.junit.Test

class SystemModuleAvailabilityTest {
    private val module = SystemModule.PROJECTM

    @Test fun installationAloneNeverMakesModuleUsable() {
        val registry = ModuleAvailabilityRegistry { false }
        assertEquals(ModuleRuntimeState.MISSING, registry.state(module).runtime)
        registry.selectionChanged(module, 2)
        assertFalse(registry.state(module).usable)
        assertTrue(registry.state(module).restartRequired)
        registry.observeSelection(module, 2)
        assertEquals(ModuleRuntimeState.MISSING, registry.state(module).runtime)
        val restarted = ModuleAvailabilityRegistry { false }
        restarted.observeSelection(module, 2)
        assertEquals(ModuleRuntimeState.EXTERNAL_UNVERIFIED, restarted.state(module).runtime)
        assertFalse(restarted.state(module).usable)
        restarted.reportProbe(module, external = true, success = true)
        assertEquals(ModuleRuntimeState.ACTIVE_EXTERNAL, restarted.state(module).runtime)
        assertTrue(restarted.state(module).usable)
        assertFalse(restarted.state(module).restartRequired)
    }

    @Test fun removalPreservesCurrentProcessUntilRestart() {
        val registry = ModuleAvailabilityRegistry { false }
        registry.observeSelection(module, 1)
        registry.reportProbe(module, external = true, success = true)
        registry.selectionChanged(module, null)
        assertTrue(registry.state(module).usable)
        assertTrue(registry.state(module).restartRequired)
        assertNull(registry.state(module).installedVersion)
        val restarted = ModuleAvailabilityRegistry { false }
        restarted.observeSelection(module, null)
        assertFalse(restarted.state(module).usable)
    }

    @Test fun failedProbeNeverEnablesBundledOrDownloadedCode() {
        val registry = ModuleAvailabilityRegistry { true }
        assertFalse(registry.state(module).usable)
        registry.reportProbe(module, external = false, success = false)
        assertEquals(ModuleRuntimeState.FAILED, registry.state(module).runtime)
        registry.observeSelection(module, 3)
        assertFalse(registry.state(module).usable)
        registry.reportProbe(module, external = true, success = false)
        assertEquals(ModuleRuntimeState.FAILED, registry.state(module).runtime)
    }

    @Test fun incompatibleSelectionCanUseBundledCodeOnlyAfterProbe() {
        val registry = ModuleAvailabilityRegistry { true }
        registry.observeSelection(module, 2)
        registry.reportIncompatibleSelection(module, 2)
        assertTrue(registry.state(module).incompatibleSelection)
        assertFalse(registry.state(module).usable)
        registry.observeSelection(module, 2)
        assertEquals(ModuleRuntimeState.BUNDLED_UNVERIFIED, registry.state(module).runtime)
        registry.reportProbe(module, external = false, success = true)
        assertTrue(registry.state(module).usable)
        assertTrue(registry.state(module).incompatibleSelection)
        registry.selectionChanged(module, 3)
        assertFalse(registry.state(module).incompatibleSelection)
        assertTrue(registry.state(module).restartRequired)
        val modular = ModuleAvailabilityRegistry { false }
        modular.reportIncompatibleSelection(module, 2)
        assertEquals(ModuleRuntimeState.FAILED, modular.state(module).runtime)
    }

    @Test fun observedCrossProcessSelectionChangeRequiresRestartWithoutHotSwap() {
        val registry = ModuleAvailabilityRegistry { false }
        registry.observeSelection(module, 1)
        registry.reportProbe(module, external = true, success = true)
        registry.observeSelection(module, 1)
        assertFalse(registry.state(module).restartRequired)
        registry.observeSelection(module, 2)
        assertTrue(registry.state(module).restartRequired)
        assertEquals(ModuleRuntimeState.ACTIVE_EXTERNAL, registry.state(module).runtime)
        assertEquals(2L, registry.state(module).installedVersion)
        registry.observeSelection(module, null)
        assertTrue(registry.state(module).usable)
        assertTrue(registry.state(module).restartRequired)
    }

    @Test fun bundledEngineRemainsUsableWhileNewSelectionAwaitsRestart() {
        val registry = ModuleAvailabilityRegistry { true }
        registry.reportProbe(module, external = false, success = true)
        registry.selectionChanged(module, 4)
        assertEquals(ModuleRuntimeState.ACTIVE_BUNDLED, registry.state(module).runtime)
        assertTrue(registry.state(module).usable)
        assertTrue(registry.state(module).restartRequired)
        assertEquals(4L, registry.state(module).installedVersion)
    }
}
