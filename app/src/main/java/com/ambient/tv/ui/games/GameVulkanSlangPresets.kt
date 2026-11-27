package com.ambient.tv.ui.games

import android.content.Context
import com.ambient.tv.games.GameShader
import java.io.File
import java.security.MessageDigest

/**
 * Fixed Vulkan Slang presets: the compact original effects plus a pinned subset
 * of authentic Libretro upscalers, including ScaleFX's floating-point multipass
 * chain. GLES remains the default; native WSI renders these through librashader.
 *
 * Asset names are fixed and allowlisted. No untrusted path is passed to native code.
 */
internal object GameVulkanSlangPresets {
    private const val VERSION = "v2"
    private const val MAX_FILE_BYTES = 16_384
    private const val MAX_PRESET_BYTES = 96 * 1024
    private data class Preset(val path: String, val files: List<String>)
    private fun preset(path: String, vararg sources: String) =
        Preset(path, sources.toList() + path)

    // This is the complete dependency closure. No runtime imports, downloaded
    // presets or user-controlled shader paths are accepted by the native path.
    private val supported: Map<GameShader, Preset> = mapOf(
        GameShader.CRT_LIGHT to preset("crt-light.slangp", "crt-light.slang"),
        GameShader.CRT_GEOMETRY to preset("crt-geometry.slangp", "crt-geometry.slang"),
        GameShader.CRT_APERTURE to preset("crt-aperture.slangp", "crt-aperture.slang"),
        GameShader.CRT_GLOW to preset("crt-glow.slangp", "crt-glow-horizontal.slang", "crt-glow-final.slang"),
        GameShader.GAMEBOY_DMG to preset("gb-dmg.slangp", "gb-dmg.slang"),
        GameShader.GBC_GRID to preset("gbc-grid.slangp", "gbc-grid.slang"),
        GameShader.NTSC_COMPOSITE to preset("ntsc.slangp", "ntsc.slang"),
        GameShader.COLOR_BOOST to preset("color-boost.slangp", "color-boost.slang"),
        GameShader.SHARP_BILINEAR to preset("sharp-bilinear.slangp", "sharp-bilinear.slang"),
        GameShader.SCALEFX to preset(
            "upstream/edge-smoothing/scalefx/scalefx.slangp",
            "upstream/stock.slang",
            "upstream/edge-smoothing/scalefx/shaders/scalefx-pass0.slang",
            "upstream/edge-smoothing/scalefx/shaders/scalefx-pass1.slang",
            "upstream/edge-smoothing/scalefx/shaders/scalefx-pass2.slang",
            "upstream/edge-smoothing/scalefx/shaders/scalefx-pass3.slang",
            "upstream/edge-smoothing/scalefx/shaders/scalefx-pass4.slang",
        ),
        GameShader.SABR to preset(
            "upstream/edge-smoothing/sabr/sabr.slangp",
            "upstream/edge-smoothing/sabr/shaders/sabr-v3.0.slang",
        ),
        GameShader.XBR_LV2 to preset(
            "upstream/edge-smoothing/xbr/other presets/xbr-lv2-standalone.slangp",
            "upstream/edge-smoothing/xbr/shaders/xbr-lv2-standalone.slang",
        ),
        GameShader.XBRZ_FREESCALE to preset(
            "upstream/edge-smoothing/xbrz/xbrz-freescale.slangp",
            "upstream/edge-smoothing/xbrz/shaders/xbrz-freescale.slang",
        ),
        GameShader.XSAL_2X to preset(
            "upstream/edge-smoothing/xsal/2xsal.slangp",
            "upstream/edge-smoothing/xsal/shaders/2xsal.slang",
            "upstream/interpolation/shaders/bicubic/bicubic-y.slang",
            "upstream/interpolation/shaders/bicubic/bicubic-x.slang",
        ),
        GameShader.SUPER_2XSAI to preset(
            "upstream/edge-smoothing/eagle/super-2xsai.slangp",
            "upstream/edge-smoothing/eagle/shaders/super-2xsai.slang",
            "upstream/interpolation/shaders/bicubic/bicubic-y.slang",
            "upstream/interpolation/shaders/bicubic/bicubic-x.slang",
        ),
        GameShader.SUPER_EAGLE to preset(
            "upstream/edge-smoothing/eagle/supereagle.slangp",
            "upstream/edge-smoothing/eagle/shaders/supereagle.slang",
        ),
        GameShader.SCALEHQ2X to preset(
            "upstream/edge-smoothing/scalehq/2xScaleHQ.slangp",
            "upstream/edge-smoothing/scalehq/shaders/2xScaleHQ.slang",
            "upstream/interpolation/shaders/bicubic/bicubic-y.slang",
            "upstream/interpolation/shaders/bicubic/bicubic-x.slang",
        ),
    )

    fun supports(shader: GameShader, selectedPack: String?): Boolean =
        selectedPack == null && shader in supported

    /** Returns null when a built-in preset is absent/damaged; GLES still plays the game. */
    @Synchronized
    fun prepare(context: Context, shader: GameShader, selectedPack: String?): String? {
        if (selectedPack != null) return null
        val preset = supported[shader] ?: return null
        return runCatching {
            val module = com.ambient.tv.modules.SystemModuleHost.loadSelected(com.ambient.tv.modules.SystemModule.SHADERS)
            val directory = File(context.noBackupFilesDir, "games/vulkan-slang/$VERSION")
            if (!directory.isDirectory && !directory.mkdirs())
                error("Cannot prepare Vulkan shader storage")
            val directoryPrefix = directory.canonicalPath + File.separator
            var presetBytes = 0
            preset.files.forEach { filename ->
                val source = if (module != null) {
                    val file = File(module.assets, "games/slang/$filename")
                    check(file.isFile && file.length() in 1L..MAX_FILE_BYTES.toLong())
                    file.readBytes().also { check(it.size in 1..MAX_FILE_BYTES) }
                } else context.assets.open("games/slang/$filename").use { input ->
                    input.readBytes().also { check(it.size in 1..MAX_FILE_BYTES) }
                }
                presetBytes += source.size
                check(presetBytes <= MAX_PRESET_BYTES)
                val target = File(directory, filename)
                check(target.canonicalPath.startsWith(directoryPrefix))
                val parent = checkNotNull(target.parentFile)
                if (!parent.isDirectory && !parent.mkdirs()) error("Cannot prepare shader directory")
                val matches = target.isFile && target.length() == source.size.toLong() &&
                    MessageDigest.isEqual(digest(target.readBytes()), digest(source))
                if (!matches) {
                    val partial = File(parent, ".${target.name}.tmp")
                    try {
                        partial.outputStream().use { it.write(source) }
                        if (!partial.renameTo(target)) error("Cannot replace $filename")
                    } finally {
                        partial.delete()
                    }
                }
            }
            File(directory, preset.path).absolutePath
        }.getOrNull()
    }

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
