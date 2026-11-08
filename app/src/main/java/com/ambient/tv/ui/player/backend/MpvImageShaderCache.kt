package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.validateNnedi3ShaderSource
import java.io.File

/**
 * File-only shader preparation. The player captures [MpvCasTransferProfile] on its owner
 * thread before dispatching here; [readSource] only reads the bundled resource bytes.
 */
internal class MpvImageShaderCache(
    private val shaderDir: File,
    private val readSource: (Int?, String?) -> String,
) {
    fun materialize(shader: MpvShaderAsset, casProfile: MpvCasTransferProfile?, gpuNextVulkan: Boolean): File {
        shaderDir.mkdirs()
        val transferProfile = if (shader.transferAware) {
            requireNotNull(casProfile) { "No captured transfer profile available for ${shader.fileName}" }
        } else null
        val openGlVariant = !gpuNextVulkan && mpvOpenGlShaderAdapter(shader) != null
        val cacheBaseName = if (openGlVariant) shader.fileName.removeSuffix(".glsl") + "_opengl.glsl"
            else shader.cacheFileName ?: shader.fileName
        val fileName = transferProfile?.let {
            cacheBaseName.removeSuffix(".glsl") + "_" + it.suffix + ".glsl"
        } ?: cacheBaseName
        val shaderFile = File(shaderDir, fileName)
        val resourceId = if (transferProfile?.singlePassHdr == true) MPV_CAS_HDR_RESOURCE else shader.resourceId
        val source = readSource(resourceId, shader.resourceName)
        val transformed = mpvShaderSourceFor(shader, source, transferProfile, openGl = !gpuNextVulkan)
        fun valid(file: File): Boolean = shader.nnedi3Preset?.let { preset ->
            runCatching { validateNnedi3ShaderSource(file.readText(), preset) }.getOrDefault(false)
        } ?: if (transferProfile != null) {
            runCatching {
                val cachedSource = file.readText()
                mpvCasSourceValid(cachedSource, transferProfile, shader.casBlend)
            }.getOrDefault(false)
        } else file.isFile

        // An APK update can fix shader code without changing the asset's name. In particular,
        // retaining the pre-fix Adaptive Sharpen source retains its NaN/black-frame bug.
        // This runs on IO only when loading shaders, never in the frame loop.
        if (shaderFile.isFile && (!valid(shaderFile) ||
                runCatching { shaderFile.readText() != transformed }.getOrDefault(true))) shaderFile.delete()
        if (!shaderFile.isFile) {
            // A cancelled IO task may finish copying while a new selection starts. Unique
            // temporary files avoid two workers truncating the same staging file.
            val pendingFile = File.createTempFile("${shader.fileName}.", ".pending", shaderDir)
            try {
                pendingFile.writeText(transformed)
                check(valid(pendingFile)) { "Bundled shader failed validation: ${shader.fileName}" }
                if (shaderFile.exists()) shaderFile.delete()
                check(pendingFile.renameTo(shaderFile)) { "Could not install ${shader.fileName}" }
            } finally {
                pendingFile.delete()
            }
        }
        return shaderFile
    }
}
