package com.ambient.tv.modules

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SystemModuleStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val module = SystemModule.MONOGRAM
    private val tag = "modules-test"
    private fun store() = SystemModuleStore(
        folder.root, Base64.getEncoder().encodeToString(key.public.encoded),
        "arm64-v8a", 1, 37,
    )

    private fun zip(vararg names: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { output ->
            for (name in names) {
                output.putNextEntry(ZipEntry(name))
                output.write(byteArrayOf(1, 2, 3))
                output.closeEntry()
            }
        }
    }.toByteArray()

    private fun install(
        store: SystemModuleStore,
        version: Long,
        signedArchive: ByteArray,
        received: ByteArray = signedArchive,
        onOpen: () -> Unit = {},
        validateCandidate: (SystemModuleStore.Package) -> Unit = {},
        compatible: (SystemModuleBuild) -> Boolean = { true },
        beforePublish: () -> Unit = {},
    ): SystemModuleStore.Package? {
        val digest = MessageDigest.getInstance("SHA-256").digest(signedArchive)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val build = SystemModuleBuild(
            module.id, "arm64-v8a", "baseline", version, 1, 29,
            "monogram--arm64-v8a--baseline.zip", signedArchive.size.toLong(), digest,
        )
        val bytes = Json.encodeToString(SystemModuleManifest(1, tag, listOf(build))).encodeToByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(bytes)
            sign()
        }
        return store.install(module, tag, bytes, signature, validateCandidate = validateCandidate, compatible = compatible, beforePublish = beforePublish) { onOpen(); received.inputStream() }
    }

    private fun reject(block: () -> Unit) {
        try {
            block()
            fail("Expected package rejection")
        } catch (_: IOException) {
            // Expected.
        }
    }

    @Test fun failedUpdatePreservesCurrentAndRollbackKeepsVersionFloor() {
        val manager = store()
        val one = zip("lib/libone.so")
        val two = zip("lib/libtwo.so")
        assertEquals(1L, install(manager, 1, one)?.build?.version)
        reject { install(manager, 2, two, two.copyOf(two.size - 1)) }
        reject { install(manager, 2, two, two + byteArrayOf(1)) }
        val corrupt = two.clone().apply { this[0] = 0 }
        reject { install(manager, 2, two, corrupt) }
        assertEquals(1L, store().current(module)?.build?.version)
        assertEquals(2L, install(manager, 2, two)?.build?.version)
        assertEquals(1L, store().rollback(module)?.build?.version)
        assertNull(install(manager, 1, one, onOpen = { fail("Downgrade download opened") }))
        manager.remove(module)
        assertNull(store().current(module))
        assertNull(install(manager, 1, one, onOpen = { fail("Version floor lost after remove") }))
        assertEquals(2L, install(manager, 2, two)?.build?.version)
    }

    @Test fun invalidPathsAreRejectedEvenWithValidSignatureAndHash() {
        val manager = store()
        for (names in listOf(
            arrayOf("lib/../libbad.so"),
            arrayOf("/lib/libbad.so"),
            arrayOf("lib/libbad\\name.so"),
            arrayOf("lib/A.so", "lib/a.so"),
            arrayOf("assets/file", "assets/file/child"),
            arrayOf("unknown/payload"),
            arrayOf("licenses/NOTICE"),
        )) {
            reject { install(manager, 1, zip(*names)) }
            assertNull(manager.current(module))
        }
        assertNotNull(install(manager, 1, zip("code/classes.dex", "lib/libgood.so", "licenses/NOTICE")))
    }

    @Test fun tamperedStoredArchiveIsRejectedOnReopen() {
        val archive = install(store(), 1, zip("lib/libgood.so"))!!.archive
        archive.writeBytes(byteArrayOf(1))
        reject { store().current(module) }
    }

    @Test fun unsignedCatalogNeverOpensPayload() {
        reject {
            store().install(module, tag, byteArrayOf(1), byteArrayOf(2)) {
                fail("Unauthenticated payload opened")
                error("unreachable")
            }
        }
    }

    @Test fun transportRejectsUntrustedOrigins() {
        assertTrue(SystemModuleTransport.allowed("https://github.com/seinist/ambient-tv".toHttpUrl()))
        assertTrue(SystemModuleTransport.allowed("https://release-assets.githubusercontent.com/file".toHttpUrl()))
        for (url in listOf(
            "http://github.com/file",
            "https://github.com.evil.example/file",
            "https://evil.example/file",
            "https://github.com:8443/file",
            "https://user@github.com/file",
        )) assertFalse(SystemModuleTransport.allowed(url.toHttpUrl()))
    }
    private fun centralOffset(bytes: ByteArray): Int {
        val end = bytes.size - 22
        return java.nio.ByteBuffer.wrap(bytes, end + 16, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
    }

    @Test fun signedZipSymlinkAndInconsistentLocalHeaderAreRejected() {
        val manager = store()
        val regular = zip("lib/libgood.so")
        val central = centralOffset(regular)
        val symlink = regular.clone().apply {
            this[central + 5] = 3 // Unix creator
            // S_IFLNK | 0777, high 16 bits of external attributes.
            this[central + 40] = 0xff.toByte()
            this[central + 41] = 0xa1.toByte()
        }
        reject { install(manager, 1, symlink) }
        val mismatch = regular.clone().apply { this[30] = 'x'.code.toByte() }
        reject { install(manager, 1, mismatch) }
        val trailing = regular + byteArrayOf(0)
        reject { install(manager, 1, trailing) }
        val oversized = regular.clone().apply {
            java.nio.ByteBuffer.wrap(this, central + 24, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(Int.MAX_VALUE)
        }
        reject { install(manager, 1, oversized) }
        val excessiveEntries = regular.clone().apply {
            val end = size - 22
            java.nio.ByteBuffer.wrap(this, end + 8, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putShort(4097.toShort()).putShort(4097.toShort())
        }
        reject { install(manager, 1, excessiveEntries) }
        assertNull(manager.current(module))
        assertNotNull(install(manager, 1, regular))
    }

    @Test fun corruptRollbackTargetDoesNotChangeCurrent() {
        val manager = store()
        val previous = install(manager, 1, zip("lib/libone.so"))!!.archive
        install(manager, 2, zip("lib/libtwo.so"))
        previous.writeBytes(byteArrayOf(0))
        reject { manager.rollback(module) }
        assertEquals(2L, store().current(module)?.build?.version)
    }

    @Test fun storedPackageSymlinkIsNotFollowed() {
        val manager = store()
        val installed = install(manager, 1, zip("lib/libone.so"))!!.archive
        val outside = folder.newFile("outside.zip")
        outside.writeBytes(installed.readBytes())
        assertTrue(installed.delete())
        java.nio.file.Files.createSymbolicLink(installed.toPath(), outside.toPath())
        reject { manager.current(module) }
    }

    @Test fun incompatibleSignedMetadataDoesNotOpenArchiveOrAdvanceFloor() {
        val manager = store()
        val one = zip("lib/libone.so")
        val two = zip("lib/libtwo.so")
        install(manager, 1, one)
        assertNull(install(manager, 9, two, onOpen = { fail("Incompatible download opened") },
            compatible = { false }))
        assertEquals(1L, manager.current(module)?.build?.version)
        assertEquals(2L, install(manager, 2, two)?.build?.version)
        assertNull(install(manager, 1, one, onOpen = { fail("Downgrade download opened") },
            compatible = { fail("Downgrade reached compatibility probe"); true }))
    }

    @Test fun invalidSignatureNeverReachesCompatibilityProbe() {
        reject {
            store().install(module, tag, byteArrayOf(1), byteArrayOf(2), compatible = {
                fail("Unauthenticated metadata reached host probe"); true
            }) { error("unreachable") }
        }
    }

    @Test fun cancellationBeforeAtomicPublicationRetainsSelectionAndVersionFloor() {
        val manager = store()
        val one = zip("lib/libone.so")
        val two = zip("lib/libtwo.so")
        install(manager, 1, one)
        val transfer = SystemModuleDownload()
        reject { install(manager, 9, two, validateCandidate = { transfer.cancel() }, beforePublish = transfer::beginPublication) }
        assertEquals(1L, manager.current(module)?.build?.version)
        assertEquals(2L, install(manager, 2, two)?.build?.version)
        assertEquals(1L, manager.rollback(module)?.build?.version)
    }

    @Test fun publicationBoundaryRejectsLateCancellation() {
        val transfer = SystemModuleDownload()
        transfer.beginPublication()
        assertFalse(transfer.cancel())
        transfer.check()
        assertEquals(SystemModuleDownload.COMMITTING, transfer.progress.value)
    }

    @Test fun cancelledTransferCancelsNewHttpCalls() {
        val transfer = SystemModuleDownload()
        val call = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("https://github.com/").build())
        assertTrue(transfer.cancel())
        reject { transfer.attach(call) }
        assertTrue(call.isCanceled())
        assertEquals(SystemModuleDownload.CANCELLING, transfer.progress.value)
    }

    @Test fun incompatibleCandidateDoesNotPublishOrAdvanceVersionFloor() {
        val manager = store()
        val one = zip("lib/libone.so")
        val two = zip("lib/libtwo.so")
        install(manager, 1, one)
        reject {
            install(manager, 9, two, validateCandidate = { throw SystemModuleTrustException("Incompatible ELF") })
        }
        assertEquals(1L, manager.current(module)?.build?.version)
        assertEquals(2L, install(manager, 2, two)?.build?.version)
        assertEquals(1L, manager.rollback(module)?.build?.version)
    }

    @Test fun corruptedCurrentVersionCanBeRepairedWithoutLosingRollback() {
        val manager = store()
        val one = zip("lib/libone.so")
        val two = zip("lib/libtwo.so")
        install(manager, 1, one)
        val current = install(manager, 2, two)!!.archive
        current.writeBytes(byteArrayOf(0))
        reject { manager.current(module) }
        val repaired = install(manager, 2, two)!!
        assertNotEquals(current, repaired.archive)
        assertEquals(2L, manager.current(module)?.build?.version)
        assertEquals(1L, manager.rollback(module)?.build?.version)
    }

}
