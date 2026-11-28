package com.ambient.tv.modules

import java.io.IOException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Only these components can be distributed as executable system modules. */
enum class SystemModule(val id: String) {
    MONOGRAM("monogram"),
    SMARTTUBE("smarttube"),
    SPOTIFY("spotify"),
    MPV("mpv"),
    VLC("vlc"),
    MOONLIGHT("moonlight"),
    LIBRETRODROID("libretrodroid"),
    PROJECTM("projectm"),
    SVP("svp"),
    SHADERS("shaders");

    companion object {
        fun fromId(id: String): SystemModule? = entries.firstOrNull { it.id == id }
    }
}

@Serializable
data class SystemModuleDependency(val module: String, val version: Long)

@Serializable
data class SystemModuleBuild(
    val module: String,
    val abi: String,
    val profile: String,
    val version: Long,
    val hostApi: Int,
    val minSdk: Int,
    val asset: String,
    val size: Long,
    val sha256: String,
    val dependencies: List<SystemModuleDependency> = emptyList(),
    val hostNativeSha256: Map<String, String> = emptyMap(),
    val hostBindingSha256: String? = null,
)

@Serializable
data class SystemModuleManifest(
    val schema: Int,
    val tag: String,
    val modules: List<SystemModuleBuild>,
)

class SystemModuleTrustException(message: String) : IOException(message)

/**
 * The release index is untrusted until authenticated. This boundary does not load
 * code or install archives. Hosts must additionally validate the archive contents,
 * dependencies and installed binary before activation.
 */
object SystemModuleChannel {
    const val SCHEMA = 1
    const val MAX_MANIFEST_BYTES = 512 * 1024
    const val MAX_PACKAGE_BYTES = 256L * 1024 * 1024
    private val abis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "any")
    private val profiles = setOf("baseline", "optimized", "optimized-a57")
    private val digest = Regex("[0-9a-f]{64}")
    private val tagPattern = Regex("modules-[A-Za-z0-9][A-Za-z0-9._-]{0,100}")
    private val json = Json { ignoreUnknownKeys = false }

    /** The key is pinned by the host; it must never be supplied by a release. */
    fun authenticate(
        bytes: ByteArray,
        signature: ByteArray,
        publicKeyBase64: String,
        expectedTag: String,
    ): SystemModuleManifest {
        if (bytes.size !in 1..MAX_MANIFEST_BYTES || signature.size !in 1..256 ||
            !tagPattern.matches(expectedTag)) {
            throw SystemModuleTrustException("Invalid system module metadata bounds or tag")
        }
        val verified = runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)),
            )
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(bytes)
                verify(signature)
            }
        }.getOrDefault(false)
        if (!verified) throw SystemModuleTrustException("System module manifest signature invalid")
        val manifest = try {
            json.decodeFromString<SystemModuleManifest>(bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (_: IllegalArgumentException) {
            throw SystemModuleTrustException("Invalid signed system module manifest")
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw SystemModuleTrustException("Invalid signed system module manifest encoding")
        }
        validate(manifest, expectedTag)
        return manifest
    }

    private fun validate(manifest: SystemModuleManifest, expectedTag: String) {
        if (manifest.schema != SCHEMA || manifest.tag != expectedTag ||
            manifest.modules.size > SystemModule.entries.size * abis.size * profiles.size) {
            throw SystemModuleTrustException("System module schema, tag or entry count mismatch")
        }
        val identities = mutableSetOf<Triple<String, String, String>>()
        val assets = mutableSetOf<String>()
        for (build in manifest.modules) {
            val expectedAsset = "${build.module}--${build.abi}--${build.profile}.zip"
            if (SystemModule.fromId(build.module) == null || build.abi !in abis ||
                build.profile !in profiles ||
                (build.profile == "optimized-a57" && build.abi != "arm64-v8a") ||
                (build.abi == "any" && build.profile != "baseline") ||
                build.version <= 0 || build.hostApi <= 0 || build.minSdk < 29 ||
                build.size !in 1..MAX_PACKAGE_BYTES || !digest.matches(build.sha256) ||
                (build.hostBindingSha256 != null && !digest.matches(build.hostBindingSha256)) ||
                build.hostNativeSha256.size > 32 ||
                build.hostNativeSha256.any { (name, sha) -> !Regex("lib[A-Za-z0-9_+.-]+[.]so").matches(name) || !digest.matches(sha) } ||
                build.dependencies.size >= SystemModule.entries.size ||
                build.dependencies.any { SystemModule.fromId(it.module) == null || it.module == build.module || it.version <= 0 } ||
                build.dependencies.map { it.module }.distinct().size != build.dependencies.size ||
                build.asset != expectedAsset || !assets.add(build.asset) ||
                !identities.add(Triple(build.module, build.abi, build.profile))) {
                throw SystemModuleTrustException("Invalid or duplicate system module entry")
            }
        }
    }

    /**
     * Resolve only an authenticated index. The host supplies its PROCESS ABI, not
     * the first hardware ABI. CPU-specific builds are explicit opt-in preferences.
     * A version floor prevents automatic downgrades; explicit rollback is separate.
     */
    fun select(
        manifest: SystemModuleManifest,
        module: SystemModule,
        processAbi: String,
        hostApi: Int,
        sdk: Int,
        preferredProfile: String = "baseline",
        minimumVersion: Long = 1,
        compatible: (SystemModuleBuild) -> Boolean = { true },
    ): SystemModuleBuild? {
        require(processAbi in abis && processAbi != "any")
        require(hostApi > 0 && minimumVersion > 0 && preferredProfile in profiles)
        val candidates = manifest.modules.filter {
            it.module == module.id && it.hostApi == hostApi && it.minSdk <= sdk &&
                it.version >= minimumVersion
        }
        val order = when (preferredProfile) {
            "optimized-a57" -> if (processAbi == "arm64-v8a")
                listOf("optimized-a57", "optimized", "baseline") else listOf("optimized", "baseline")
            "optimized" -> listOf("optimized", "baseline")
            else -> listOf("baseline")
        }
        for (profile in order) {
            candidates.singleOrNull { it.abi == processAbi && it.profile == profile && compatible(it) }?.let { return it }
        }
        return candidates.singleOrNull { it.abi == "any" && it.profile == "baseline" && compatible(it) }
    }
}
