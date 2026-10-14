package com.ambient.tv.ui.common.projectm

import androidx.compose.runtime.Composable
import com.ambient.tv.ui.display.SdrToHdrSwitchFacts
import com.ambient.tv.ui.display.sdrToHdrSwitchVisible
import com.ambient.tv.ui.player.backend.VulkanNativeProbe
import com.ambient.tv.ui.settings.rememberHdrControlFacts

/** Both projectM settings surfaces use display capabilities, never an unrelated video backend. */
@Composable
internal fun rememberProjectMHdrSupported(mode: String): Boolean = projectMHdrSupported(
    rememberHdrControlFacts(mode, inspectVideoPlayback = false),
    VulkanNativeProbe.available,
)

internal fun projectMHdrSupported(facts: SdrToHdrSwitchFacts, nativeOutputAvailable: Boolean): Boolean =
    nativeOutputAvailable && sdrToHdrSwitchVisible(facts)
