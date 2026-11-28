package com.ambient.tv.modules

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SystemModuleRuntimeTest {
    @get:Rule val folder = TemporaryFolder()

    private fun reject(block: () -> Unit) {
        try {
            block()
            fail("Expected module validation failure")
        } catch (_: IOException) {
            // Expected.
        }
    }

    private fun elf(alignment: Long = 16384, machine: Short = 183): ByteArray {
        val bytes = ByteArray(512)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(127, 69, 76, 70, 2, 1, 1))
        b.putShort(16, 3).putShort(18, machine).putInt(20, 1)
        b.putLong(32, 64).putShort(52, 64).putShort(54, 56).putShort(56, 2)
        // PT_LOAD: executable, non-writable, complete file, aligned.
        b.putInt(64, 1).putInt(68, 5).putLong(72, 0).putLong(80, 0)
        b.putLong(96, 512).putLong(104, 512).putLong(112, alignment)
        // PT_DYNAMIC: located inside the same loadable segment.
        b.putInt(120, 2).putInt(124, 4).putLong(128, 256).putLong(136, 256)
        b.putLong(152, 80).putLong(160, 80).putLong(168, 8)
        b.putLong(256, 5).putLong(264, 384) // DT_STRTAB
        b.putLong(272, 10).putLong(280, 64) // DT_STRSZ
        b.putLong(288, 14).putLong(296, 1) // DT_SONAME
        b.putLong(304, 1).putLong(312, 12) // DT_NEEDED
        b.putLong(320, 0)
        b.position(384)
        b.put("\u0000libgood.so\u0000libc.so\u0000".toByteArray(Charsets.US_ASCII))
        return bytes
    }

    private fun dex(): ByteArray {
        val bytes = ByteArray(140)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("dex\n039\u0000".toByteArray(Charsets.US_ASCII))
        b.putInt(32, bytes.size).putInt(36, 112).putInt(40, 0x12345678)
        b.putInt(52, 112).putInt(104, 28).putInt(108, 112)
        b.putInt(112, 2)
        b.putShort(116, 0).putShort(118, 0).putInt(120, 1).putInt(124, 0)
        b.putShort(128, 0x1000).putShort(130, 0).putInt(132, 1).putInt(136, 112)
        MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size)).copyInto(bytes, 12)
        val checksum = Adler32().apply { update(bytes, 12, bytes.size - 12) }
        b.putInt(8, checksum.value.toInt())
        return bytes
    }

    private fun packageFile(
        module: SystemModule = SystemModule.MONOGRAM,
        version: Long = 1,
        dependencies: List<SystemModuleDependency> = emptyList(),
        files: Map<String, ByteArray> = mapOf("assets/test" to byteArrayOf(1)),
    ): SystemModuleStore.Package {
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                files.forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val zip = folder.newFile().apply { writeBytes(zipBytes) }
        val hash = MessageDigest.getInstance("SHA-256").digest(zipBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return SystemModuleStore.Package(zip, SystemModuleBuild(
            module.id, "arm64-v8a", "baseline", version, 1, 29,
            "${module.id}--arm64-v8a--baseline.zip", zipBytes.size.toLong(), hash, dependencies,
        ))
    }

    @Test fun dexPrivateClosureAllowsLinkerCyclesButNotUnreachableLibraries() {
        val graph = mapOf(
            "libfrontend.so" to SystemModuleBinary.Native("libfrontend.so", setOf("libc++_shared.so")),
            "libc++_shared.so" to SystemModuleBinary.Native("libc++_shared.so", setOf("libfrontend.so")),
            "libunused.so" to SystemModuleBinary.Native("libunused.so", emptySet()),
        )
        assertEquals(setOf("libfrontend.so", "libc++_shared.so"),
            SystemModuleRuntime.nativeClosure(graph, setOf("libfrontend.so")))
        reject { SystemModuleRuntime.nativeClosure(graph, setOf("libmissing.so")) }
    }

    @Test fun elfChecksMachinePageAlignmentAndDependencies() {
        val f = folder.newFile().apply { writeBytes(elf()) }
        val native = SystemModuleBinary.elf(f, "arm64-v8a", 4096)
        assertEquals("libgood.so", native.soname)
        assertEquals(setOf("libc.so"), native.needed)
        reject { SystemModuleBinary.elf(f, "x86_64", 4096) }
        f.writeBytes(elf(alignment = 4096))
        reject { SystemModuleBinary.elf(f, "arm64-v8a", 16384) }
        f.writeBytes(elf().copyOf(100))
        reject { SystemModuleBinary.elf(f, "arm64-v8a", 4096) }
        reject { SystemModuleMaterializer.validateNativeDependencies(
            mapOf("libgood.so" to SystemModuleBinary.Native("libgood.so", setOf("libmissing.so")))
        ) }
        reject { SystemModuleMaterializer.validateNativeDependencies(
            mapOf("libc.so" to SystemModuleBinary.Native("libc.so", emptySet()))
        ) }
    }

    @Test fun dexHeaderChecksumsAndMapAreValidated() {
        val f = folder.newFile().apply { writeBytes(dex()) }
        SystemModuleBinary.dex(f)
        f.writeBytes(dex().apply { this[139] = 1 })
        reject { SystemModuleBinary.dex(f) }
        f.writeBytes(dex().copyOf(100))
        reject { SystemModuleBinary.dex(f) }
    }

    @Test fun materializationSealsFilesAndDetectsUnexpectedPayload() {
        val pkg = packageFile(files = mapOf(
            "code/classes.dex" to dex(), "lib/libgood.so" to elf(), "assets/readme" to byteArrayOf(1),
        ))
        val root = folder.newFolder()
        val prepared = SystemModuleMaterializer.prepare(pkg, root, 4096)
        assertEquals(listOf("classes.dex"), prepared.dex.map { it.name })
        assertEquals(setOf("libgood.so"), prepared.native.keys)
        SystemModuleMaterializer.verifyFiles(prepared)
        File(prepared.directory, "lib/libunexpected.so").writeBytes(byteArrayOf(0))
        reject { SystemModuleMaterializer.verifyFiles(prepared) }
    }

    @Test fun badExecutableNeverReturnsPreparedTree() {
        val pkg = packageFile(files = mapOf("lib/libgood.so" to byteArrayOf(0)))
        val root = folder.newFolder()
        reject { SystemModuleMaterializer.prepare(pkg, root, 4096) }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun exactDependencyVersionsAndCyclesAreCheckedBeforeLoad() {
        val root = packageFile(dependencies = listOf(SystemModuleDependency("shaders", 2)))
        val dep = packageFile(SystemModule.SHADERS, 2)
        val graph = mapOf(SystemModule.MONOGRAM to root, SystemModule.SHADERS to dep)
        assertEquals(listOf(dep, root), SystemModuleDependencies.order(SystemModule.MONOGRAM, graph::get))
        reject { SystemModuleDependencies.order(SystemModule.MONOGRAM) { if (it == SystemModule.MONOGRAM) root else null } }
        reject { SystemModuleDependencies.order(SystemModule.MONOGRAM) {
            if (it == SystemModule.MONOGRAM) root else packageFile(SystemModule.SHADERS, 1)
        } }
        val cyclic = packageFile(SystemModule.SHADERS, 2, listOf(SystemModuleDependency("monogram", 1)))
        reject { SystemModuleDependencies.order(SystemModule.MONOGRAM) {
            if (it == SystemModule.MONOGRAM) root else cyclic
        } }
    }

    @Test fun rootPackageIsNotResolvedAgainAfterHostCompatibilityCheck() {
        val selected = packageFile(version = 1, dependencies = listOf(SystemModuleDependency("shaders", 2)))
        val newer = packageFile(version = 3)
        val dependency = packageFile(SystemModule.SHADERS, 2)
        val order = SystemModuleDependencies.orderSelected(SystemModule.MONOGRAM, selected) { module ->
            if (module == SystemModule.MONOGRAM) {
                fail("Root selection was read again")
                newer
            } else dependency
        }
        assertEquals(listOf(dependency, selected), order)
    }

    @Test fun crossModuleDependenciesRequireExplicitHostPermission() {
        val pkg = packageFile(dependencies = listOf(SystemModuleDependency("shaders", 2)))
        assertFalse(SystemModuleRuntime.dependenciesAllowed(SystemModuleRuntime.Contract(assetOnly = true), pkg.build))
        assertTrue(SystemModuleRuntime.dependenciesAllowed(SystemModuleRuntime.Contract(assetOnly = true,
            allowedModuleDependencies = setOf(SystemModule.SHADERS)), pkg.build))
    }

    @Test fun globalNativeOwnershipRejectsHostRootConflictsInBothOrders() {
        val owners = mutableMapOf<String, SystemModule>()
        val shared = mutableSetOf<String>()
        val reserve = SystemModuleRuntime.Companion::reserveGlobalNativeNames
        reserve(SystemModule.MPV, setOf("libmpv.so"), setOf("libavcodec.so"), owners, shared)
        reserve(SystemModule.VLC, setOf("libvlc.so"), setOf("libavcodec.so"), owners, shared)
        val before = owners.toMap()
        val beforeShared = shared.toSet()
        reject { reserve(SystemModule.SVP, setOf("libavcodec.so"), emptySet(), owners, shared) }
        reject { reserve(SystemModule.SVP, setOf("libsvp.so"), setOf("libmpv.so"), owners, shared) }
        reject { reserve(SystemModule.SVP, setOf("libvlc.so"), emptySet(), owners, shared) }
        reject { reserve(SystemModule.SVP, setOf("libsame.so"), setOf("libsame.so"), owners, shared) }
        assertEquals(before, owners)
        assertEquals(beforeShared, shared)
    }

    @Test fun hostNativeClosureIsExactAndOrderedBeforeModuleActivation() {
        fun lib(name: String, vararg needed: String) = SystemModuleBinary.Native(name, needed.toSet())
        val module = mapOf("libengine.so" to lib("libengine.so", "libavcodec.so", "libc.so"))
        val codec = lib("libavcodec.so", "libavutil.so")
        val util = lib("libavutil.so", "libc.so")
        val host = mapOf("libavcodec.so" to codec, "libavutil.so" to util)
        assertEquals(listOf("libavutil.so", "libavcodec.so"), SystemModuleRuntime.sharedHostOrder(module, host))
        reject { SystemModuleRuntime.sharedHostOrder(module, host - "libavutil.so") }
        reject { SystemModuleRuntime.sharedHostOrder(module, host + ("libunused.so" to lib("libunused.so"))) }
        reject { SystemModuleRuntime.sharedHostOrder(module, host + ("libavutil.so" to lib("libavutil.so", "libengine.so"))) }
        reject { SystemModuleRuntime.sharedHostOrder(module, host + ("libavutil.so" to lib("libforeign.so"))) }
        reject { SystemModuleRuntime.sharedHostOrder(module, host + ("libc.so" to lib("libc.so"))) }
        val cyclicHost = host + ("libavutil.so" to lib("libavutil.so", "libavcodec.so"))
        assertEquals(setOf("libavutil.so", "libavcodec.so"), SystemModuleRuntime.sharedHostOrder(module, cyclicHost).toSet())
    }

    @Test fun nativeOnlyLoadOrderIncludesDependenciesFirst() {
        val libs = mapOf(
            "liba.so" to SystemModuleBinary.Native("liba.so", setOf("libb.so")),
            "libb.so" to SystemModuleBinary.Native("libb.so", setOf("libc.so")),
        )
        assertEquals(listOf("libb.so", "liba.so"), SystemModuleRuntime.nativeOrder(libs, setOf("liba.so")))
        reject { SystemModuleRuntime.nativeOrder(mapOf(
            "liba.so" to SystemModuleBinary.Native("liba.so", setOf("liba.so")),
        ), setOf("liba.so")) }
    }
}
