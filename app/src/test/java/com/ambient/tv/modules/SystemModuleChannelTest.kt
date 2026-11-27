package com.ambient.tv.modules

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SystemModuleChannelTest {
    private val key = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(key.public.encoded)
    private val tag = "modules-test-1"
    private fun build(
        abi: String = "arm64-v8a",
        profile: String = "baseline",
        version: Long = 1,
        hostApi: Int = 1,
        minSdk: Int = 29,
    ) = SystemModuleBuild(
        "monogram", abi, profile, version, hostApi, minSdk,
        "monogram--$abi--$profile.zip", 100, "a".repeat(64),
    )

    private fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private)
        update(bytes)
        sign()
    }
    private fun manifest(vararg builds: SystemModuleBuild) =
        SystemModuleManifest(1, tag, builds.toList())

    private fun authenticate(manifest: SystemModuleManifest): SystemModuleManifest {
        val bytes = Json.encodeToString(manifest).encodeToByteArray()
        return SystemModuleChannel.authenticate(bytes, sign(bytes), publicKey, tag)
    }

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Expected trust failure")
        } catch (_: SystemModuleTrustException) {
            // Expected: never resolve or install untrusted entries.
        }
    }

    @Test fun signedIndexAndExplicitProfileFallback() {
        val baseline = build()
        val optimized = build(profile = "optimized")
        val a57 = build(profile = "optimized-a57")
        val verified = authenticate(manifest(baseline, optimized, a57))
        assertEquals(baseline, SystemModuleChannel.select(verified, SystemModule.MONOGRAM, "arm64-v8a", 1, 37))
        assertEquals(a57, SystemModuleChannel.select(verified, SystemModule.MONOGRAM, "arm64-v8a", 1, 37, "optimized-a57"))
        val partial = authenticate(manifest(baseline, optimized))
        assertEquals(optimized, SystemModuleChannel.select(partial, SystemModule.MONOGRAM, "arm64-v8a", 1, 37, "optimized-a57"))
    }

    @Test fun hostApiSdkAbiAndVersionFloorMustMatch() {
        val verified = authenticate(manifest(build(version = 3, hostApi = 2, minSdk = 35)))
        assertNull(SystemModuleChannel.select(verified, SystemModule.MONOGRAM, "arm64-v8a", 1, 37))
        assertNull(SystemModuleChannel.select(verified, SystemModule.MONOGRAM, "arm64-v8a", 2, 34))
        assertNull(SystemModuleChannel.select(verified, SystemModule.MONOGRAM, "armeabi-v7a", 2, 37))
        assertNull(SystemModuleChannel.select(verified, SystemModule.MONOGRAM, "arm64-v8a", 2, 37, minimumVersion = 4))
        assertNotNull(SystemModuleChannel.select(verified, SystemModule.MONOGRAM, "arm64-v8a", 2, 37, minimumVersion = 3))
    }

    @Test fun tamperingAndCrossChannelReplayAreRejected() {
        val bytes = Json.encodeToString(manifest(build())).encodeToByteArray()
        val signature = sign(bytes)
        rejected { SystemModuleChannel.authenticate(bytes + byteArrayOf(32), signature, publicKey, tag) }
        rejected { SystemModuleChannel.authenticate(bytes, signature, publicKey, "modules-other") }
        rejected { SystemModuleChannel.authenticate(bytes, signature, "", tag) }
    }

    @Test fun signedButInvalidEntriesAreRejected() {
        rejected { authenticate(manifest(build(), build(version = 2))) }
        rejected { authenticate(manifest(build().copy(asset = "../payload.zip"))) }
        rejected { authenticate(manifest(build().copy(module = "radio"))) }
        rejected { authenticate(manifest(build().copy(size = Long.MAX_VALUE))) }
        rejected { authenticate(manifest(build().copy(sha256 = "not-a-digest"))) }
        rejected { authenticate(manifest(build().copy(hostBindingSha256 = "not-a-digest"))) }
        rejected { authenticate(manifest(build(abi = "x86", profile = "optimized-a57"))) }
        rejected { authenticate(manifest(build(abi = "any", profile = "optimized"))) }
    }

    @Test fun hostBindingFingerprintIsCoveredBySignature() {
        val pinned = build().copy(hostBindingSha256 = "b".repeat(64))
        assertEquals(pinned, authenticate(manifest(pinned)).modules.single())
        val bytes = Json.encodeToString(manifest(pinned)).encodeToByteArray()
        val signature = sign(bytes)
        val changed = Json.encodeToString(manifest(pinned.copy(hostBindingSha256 = "c".repeat(64)))).encodeToByteArray()
        rejected { SystemModuleChannel.authenticate(changed, signature, publicKey, tag) }
    }

    @Test fun architectureIndependentPackageIsFallbackOnly() {
        val any = build(abi = "any")
        val native = build()
        val verified = authenticate(manifest(any, native))
        assertEquals(native, SystemModuleChannel.select(verified, SystemModule.MONOGRAM, "arm64-v8a", 1, 37))
        assertEquals(any, SystemModuleChannel.select(verified, SystemModule.MONOGRAM, "x86_64", 1, 37))
    }
}
