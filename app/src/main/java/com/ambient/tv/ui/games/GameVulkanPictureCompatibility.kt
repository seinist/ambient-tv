package com.ambient.tv.ui.games

import com.ambient.tv.games.GameAspect
import com.ambient.tv.games.GameShader

/**
 * The Vulkan WSI preview currently uses a raw nearest-neighbor image blit.
 * Only settings it can genuinely reproduce may be shown over the GLES player.
 * This is a presentation safety gate, not a claim of complete native Vulkan
 * shader, crop, rotation or pixel-aspect parity on physical hardware.
 */
internal object GameVulkanPictureCompatibility {
    fun rawFullscreenEligible(
        shader: GameShader,
        aspect: GameAspect,
        integerScaling: Boolean,
    ): Boolean = shader == GameShader.SHARP &&
        aspect == GameAspect.CORE && !integerScaling
}
