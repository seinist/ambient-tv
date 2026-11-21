package com.ambient.tv.ui.games

import android.content.Context
import com.ambient.tv.games.GameShader
import java.io.File
import java.security.MessageDigest

/**
 * Small opt-in, original Vulkan Slang 1-pass presets. They are not ports of the
 * full RetroArch shader archive. GLES remains the default; only WSI diagnostic
 * renders these assets through librashader on an actual Vulkan output surface.
 *
 * Asset names are fixed and allowlisted. No untrusted path is passed to native code.
 */
internal object GameVulkanSlangPresets {
    private const val VERSION = "v1"
    private val supported: Map<GameShader, String> = mapOf(
        GameShader.CRT_LIGHT to "crt-light",
        GameShader.CRT_GEOMETRY to "crt-geometry",
        GameShader.CRT_APERTURE to "crt-aperture",
        GameShader.CRT_GLOW to "crt-glow",
        GameShader.GAMEBOY_DMG to "gb-dmg",
        GameShader.GBC_GRID to "gbc-grid",
        GameShader.NTSC_COMPOSITE to "ntsc",
        GameShader.COLOR_BOOST to "color-boost",
        GameShader.SHARP_BILINEAR to "sharp-bilinear",
    )

    fun supports(shader: GameShader, selectedPack: String?): Boolean =
        selectedPack == null && shader in supported

    /** Returns null when a built-in preset is absent/damaged; GLES still plays the game. */
    fun prepare(context: Context, shader: GameShader, selectedPack: String?): String? {
        if (selectedPack != null) return null
        val name = supported[shader] ?: return null
        return runCatching {
            val directory = File(context.noBackupFilesDir, "games/vulkan-slang/$VERSION")
            if (!directory.isDirectory && !directory.mkdirs())
                error("Cannot prepare Vulkan shader storage")
            val files = if (name == "crt-glow")
                listOf("crt-glow-horizontal.slang", "crt-glow-final.slang", "crt-glow.slangp")
            else listOf("$name.slang", "$name.slangp")
            files.forEach { filename ->
                val source = context.assets.open("games/slang/$filename").use { input ->
                    input.readBytes().also { check(it.size in 1..16_384) }
                }
                val target = File(directory, filename)
                val matches = target.isFile && target.length() == source.size.toLong() &&
                    MessageDigest.isEqual(digest(target.readBytes()), digest(source))
                if (!matches) {
                    val partial = File(directory, ".$filename.tmp")
                    try {
                        partial.outputStream().use { it.write(source) }
                        if (!partial.renameTo(target)) error("Cannot replace $filename")
                    } finally {
                        partial.delete()
                    }
                }
            }
            File(directory, "$name.slangp").absolutePath
        }.getOrNull()
    }

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
