package com.ambient.tv.ui.games

import com.ambient.tv.games.GameShader

/**
 * The experimental Vulkan WSI presenter performs an unfiltered nearest-neighbor
 * image blit. Only PIXEL really matches that output. Built-in Sharp, CUT3,
 * CRT and LCD all need shader passes that the raw blit does not run.
 *
 * The native presenter now matches the app's chosen core/forced aspect and
 * integer scaling. Unsupported rotation still fails closed to GLES natively.
 * Real-device visual parity and OSD layering remain acceptance tests.
 */
internal object GameVulkanPictureCompatibility {
    fun rawFullscreenEligible(shader: GameShader): Boolean = shader == GameShader.PIXEL
}
