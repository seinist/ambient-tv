package com.ambient.tv.games

import android.content.Context
import com.ambient.tv.BuildConfig
import com.ambient.tv.games.RetroShaderConfig as ShaderConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signed, opt-in GLSL ES 100 shader packs. No downloaded .so, scripts or arbitrary shader
 * repositories: one-passer fragments only. The app's embedded core-channel P-256 key is the
 * trust anchor. All bytes are bounded *before* metadata or shader text is admitted.
 */
@Serializable
data class GameShaderPackManifest(
    val version: Int = 1,
    val id: String,
    val title: String,
    val presets: List<GameShaderPackPreset>,
)

@Serializable
data class GameShaderPackPreset(
    val id: String,
    val title: String,
    val category: String,
    val sha256: String,
    val license: String,
    val sourceUrl: String,
)

/** Stable external selection syntax: pack-id/preset-id, never a filesystem path. */
data class InstalledGameShaderPreset(
    val selection: String,
    val title: String,
    val category: String,
    val license: String,
    val packTitle: String,
)

internal data class VerifiedGameShaderPack(
    val manifest: GameShaderPackManifest,
    val manifestBytes: ByteArray,
    val signature: ByteArray,
    val shaderSources: Map<String, ByteArray>,
)

internal object GameShaderPackArchive {
    const val MAX_ARCHIVE_BYTES = 512 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 256 * 1024
    private const val MAX_SHADER_BYTES = 16 * 1024
    private const val MAX_ENTRIES = 40
    private val idPattern = Regex("[a-z0-9][a-z0-9_-]{0,39}")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val categories = setOf("scaling", "crt", "handheld", "signal", "effects")
    private val json = Json { ignoreUnknownKeys = false }

    fun validId(id: String) = idPattern.matches(id)

    fun verify(input: InputStream, publicKey: String): VerifiedGameShaderPack {
        if (publicKey.isBlank()) throw IOException("Shader signing public key is not configured")
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0
        ZipInputStream(BudgetInputStream(input, MAX_ARCHIVE_BYTES)).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.isDirectory || ++total > MAX_ENTRIES) throw IOException("Invalid shader archive entries")
                val name = entry.name
                val allowed = name == "manifest.json" || name == "manifest.sig" ||
                    Regex("shaders/[a-z0-9][a-z0-9_-]{0,39}\\.frag").matches(name)
                if (!allowed || entries.containsKey(name)) throw IOException("Unsafe or duplicate shader file")
                val maxSize = if (name == "manifest.json") 32 * 1024 else if (name == "manifest.sig") 512 else MAX_SHADER_BYTES
                entries[name] = readBounded(archive, maxSize)
                archive.closeEntry()
            }
        }
        val raw = entries["manifest.json"] ?: throw IOException("Shader manifest missing")
        val sig = entries["manifest.sig"] ?: throw IOException("Shader signature missing")
        if (!GameCoreChannel.verify(raw, sig, publicKey)) throw IOException("Invalid shader manifest signature")
        val manifest = runCatching { json.decodeFromString<GameShaderPackManifest>(raw.decodeToString()) }
            .getOrElse { throw IOException("Malformed shader manifest", it) }
        if (manifest.version != 1 || !validId(manifest.id) || manifest.title.length !in 1..100 ||
            manifest.presets.isEmpty() || manifest.presets.size > 32)
            throw IOException("Unsupported shader pack metadata")
        val seen = HashSet<String>()
        val needed = HashSet<String>()
        var uncompressed = raw.size + sig.size
        for (preset in manifest.presets) {
            if (!validId(preset.id) || !seen.add(preset.id) || preset.title.length !in 1..100 ||
                preset.category !in categories || !hashPattern.matches(preset.sha256) ||
                preset.license.length !in 2..90 || preset.sourceUrl.length !in 1..400 ||
                !preset.sourceUrl.startsWith("https://"))
                throw IOException("Invalid shader preset entry")
            val path = "shaders/" + preset.id + ".frag"
            needed.add(path)
            val bytes = entries[path] ?: throw IOException("Missing signed shader source")
            if (digest(bytes) != preset.sha256) throw IOException("Shader source checksum mismatch")
            val source = bytes.decodeToString()
            // Reject executable includes/extensions and macros; only pinned, stand-alone ES2 fragments.
            if (source.isBlank() || !source.contains("void main") ||
                !source.contains("sampler2D texture") || !source.contains("varying") ||
                Regex("(?m)^\\s*#\\s*(include|extension|version)").containsMatchIn(source))
                throw IOException("Unsupported GLSL fragment")
            uncompressed += bytes.size
            if (uncompressed > MAX_UNCOMPRESSED_BYTES) throw IOException("Shader content too large")
        }
        if (entries.keys != needed + setOf("manifest.json", "manifest.sig"))
            throw IOException("Unexpected shader archive file")
        return VerifiedGameShaderPack(manifest, raw, sig, needed.associateWith { entries.getValue(it) })
    }

    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun readBounded(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (n == 0) continue
            if (out.size() + n > max) throw IOException("Oversized shader entry")
            out.write(buffer, 0, n)
        }
    }

    private class BudgetInputStream(input: InputStream, private val max: Int) : FilterInputStream(input) {
        private var used = 0
        override fun read(): Int {
            if (used >= max) throw IOException("Shader archive exceeds size limit")
            val c = super.read()
            if (c >= 0) used++
            return c
        }
        override fun read(dst: ByteArray, off: Int, len: Int): Int {
            if (used >= max) throw IOException("Shader archive exceeds size limit")
            val n = super.read(dst, off, minOf(len, max - used))
            if (n > 0) used += n
            return n
        }
    }
}

@Singleton
class GameShaderPackStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val directory: File get() = File(context.noBackupFilesDir, "games/shader-packs")
    private val key: String get() = BuildConfig.CORES_PUBLIC_KEY
    private val json = Json { ignoreUnknownKeys = false }
    val available: Boolean get() = key.isNotBlank()

    /** Fully validate the archive, then install atomically. An invalid pack never replaces a valid one. */
    @Synchronized
    fun install(input: InputStream): GameShaderPackManifest {
        val verified = GameShaderPackArchive.verify(input, key)
        directory.mkdirs()
        val id = verified.manifest.id
        val target = File(directory, id)
        // Installed packs must have a hard disk/count budget, independent of archive limits.
        if (!target.exists() && directory.listFiles().orEmpty().count {
                it.isDirectory && GameShaderPackArchive.validId(it.name)
            } >= 8) throw IOException("Installed shader pack limit reached")
        val staging = File(directory, ".incoming-" + UUID.randomUUID().toString())
        if (!staging.mkdir()) throw IOException("Cannot prepare shader pack")
        try {
            File(staging, "manifest.json").writeBytes(verified.manifestBytes)
            File(staging, "manifest.sig").writeBytes(verified.signature)
            verified.shaderSources.forEach { (path, bytes) ->
                File(staging, path.substringAfterLast('/')).writeBytes(bytes)
            }
            // A local/backup-corrupted pack cannot become the active shader.
            validateInstalled(staging, id) ?: throw IOException("Shader pack validation failed")
            val backup = File(directory, ".previous-" + UUID.randomUUID().toString())
            val hadOld = target.exists()
            if (hadOld && !target.renameTo(backup)) throw IOException("Cannot retain previous shader pack")
            if (!staging.renameTo(target)) {
                if (hadOld) backup.renameTo(target)
                throw IOException("Cannot finish shader pack install")
            }
            if (hadOld) backup.deleteRecursively()
            return verified.manifest
        } finally {
            staging.deleteRecursively()
        }
    }

    @Synchronized
    fun remove(packId: String): Boolean {
        if (!GameShaderPackArchive.validId(packId)) return false
        return File(directory, packId).takeIf { it.isDirectory }?.deleteRecursively() ?: false
    }

    /** Small, lazily verified installed catalog; no HTTP traffic when opening a game. */
    @Synchronized
    fun installed(): List<InstalledGameShaderPreset> =
        directory.listFiles().orEmpty().filter { it.isDirectory && GameShaderPackArchive.validId(it.name) }
            .take(8)
            .flatMap { folder ->
                val manifest = validateInstalled(folder) ?: return@flatMap emptyList()
                manifest.presets.map { p ->
                    InstalledGameShaderPreset(manifest.id + "/" + p.id, p.title, p.category, p.license, manifest.title)
                }
            }

    @Synchronized
    fun config(selection: String?): ShaderConfig.Custom? {
        if (selection == null) return null
        val split = selection.split('/')
        if (split.size != 2 || split.any { !GameShaderPackArchive.validId(it) }) return null
        val folder = File(directory, split[0])
        val manifest = validateInstalled(folder) ?: return null
        val preset = manifest.presets.firstOrNull { it.id == split[1] } ?: return null
        val sourceFile = File(folder, preset.id + ".frag")
        val bytes = runCatching { sourceFile.readBytes() }.getOrNull() ?: return null
        if (bytes.size > 16 * 1024 || GameShaderPackArchive.digest(bytes) != preset.sha256) return null
        return ShaderConfig.Custom(listOf(ShaderConfig.Custom.ShaderPass(
            vertex = VERTEX, fragment = bytes.decodeToString(), linear = false,
        )))
    }

    private fun validateInstalled(folder: File, expectedId: String = folder.name): GameShaderPackManifest? = runCatching {
        if (!folder.isDirectory) return@runCatching null
        val manifestFile = File(folder, "manifest.json")
        val signatureFile = File(folder, "manifest.sig")
        if (manifestFile.length() !in 1..32768 || signatureFile.length() !in 1..512)
            return@runCatching null
        val raw = manifestFile.readBytes()
        val sig = signatureFile.readBytes()
        if (!GameCoreChannel.verify(raw, sig, key)) return@runCatching null
        val manifest = json.decodeFromString<GameShaderPackManifest>(raw.decodeToString())
        if (manifest.version != 1 || manifest.id != expectedId ||
            manifest.presets.isEmpty() || manifest.presets.size > 32) return@runCatching null
        if (manifest.presets.any {
            !GameShaderPackArchive.validId(it.id) || it.sha256.length != 64 ||
                File(folder, it.id + ".frag").let { source ->
                    !source.isFile || source.length() !in 1..16384 ||
                        GameShaderPackArchive.digest(source.readBytes()) != it.sha256
                }
        }) return@runCatching null
        manifest
    }.getOrNull()

    companion object {
        private const val VERTEX = """
            attribute vec4 vPosition;
            attribute vec2 vCoordinate;
            varying highp vec2 coords;
            void main() {
                coords = vCoordinate;
                gl_Position = vPosition;
            }
        """
    }
}
