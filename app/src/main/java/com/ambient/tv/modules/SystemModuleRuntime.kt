package com.ambient.tv.modules

import android.content.Context
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Host-owned API shared by the APK and downloaded adapter DEX. Implementations
 * must compile against this API as compileOnly, never package a second copy.
 * Signed code executes with app privileges; classloader separation is not a
 * security sandbox. No entrypoint names are taken from untrusted releases.
 */
interface SystemModuleEntrypoint {
    fun initialize(context: Context, assets: File, dependencies: Map<String, SystemModuleEntrypoint>)
}

/**
 * A process-lifetime runtime. Construct once from the host with an explicit
 * allowlist of supported module adapters. Updates/removal require a restart;
 * no attempt is made to unload JNI, JNA, or ART classes.
 */
internal class SystemModuleRuntime(
    context: Context,
    private val store: SystemModuleStore,
    private val pageSize: Long,
    private val contracts: Map<SystemModule, Contract>,
    private val root: File = File(context.noBackupFilesDir, "system-module-runtime"),
) {
    private val app = context.applicationContext
    private val hostLoader = app.classLoader

    data class Contract(
        val entryClass: String? = null,
        // Native-only mode is for host-owned JNI bindings with no bundled
        // alternative in this process. DEX adapters load their own JNI/JNA.
        val nativeRoots: Set<String> = emptySet(),
        val assetOnly: Boolean = false,
        // Explicit APK-owned ABI dependencies are never replaced by a module.
        val hostLibraries: Set<String> = emptySet(),
        // Only engines whose first load is controlled by this runtime may opt in.
        val bundledOverrides: Set<String> = emptySet(),
    )

    data class Loaded(
        val build: SystemModuleBuild,
        val assets: File,
        val entrypoint: SystemModuleEntrypoint?,
        val classLoader: ClassLoader?,
    )

    private val active = mutableMapOf<SystemModule, Loaded>()
    private val failed = mutableSetOf<SystemModule>()
    private val nativeOwners = mutableMapOf<String, SystemModule>()
    private var loading = false

    /** Call on a background worker; all initialization is serialized. */
    @Synchronized
    fun load(module: SystemModule): Loaded {
        active[module]?.let { return it }
        if (loading) throw SystemModuleTrustException("Recursive module runtime activation")
        if (module in failed) throw SystemModuleTrustException("Module activation failed; restart required")
        loading = true
        try {
            val order = SystemModuleDependencies.order(module, store::current)
            // Prepare the complete graph before executing any new code.
            val prepared = linkedMapOf<SystemModule, SystemModuleMaterializer.Materialized>()
            val reserved = nativeOwners.toMutableMap()
            try {
                for (pkg in order) {
                    val id = SystemModule.fromId(pkg.build.module)
                        ?: throw SystemModuleTrustException("Unknown module")
                    if (id in failed) throw SystemModuleTrustException("Dependency activation failed; restart required")
                    active[id]?.let {
                        if (it.build != pkg.build) throw SystemModuleTrustException("Loaded module differs from selected package; restart required")
                    }
                    if (id in active) continue
                    val contract = contracts[id] ?: throw SystemModuleTrustException("No host module contract")
                    if (!contract.hostLibraries.containsAll(pkg.build.hostNativeSha256.keys))
                        throw SystemModuleTrustException("Unapproved host native dependency")
                    val files = SystemModuleMaterializer.prepare(pkg, root, pageSize, pkg.build.hostNativeSha256.keys)
                    prepared[id] = files
                    validateContract(contract, files)
                    for (name in files.native.keys) {
                        val owner = reserved.putIfAbsent(name, id)
                        if (owner != null && owner != id)
                            throw SystemModuleTrustException("Conflicting native libraries in module graph")
                    }
                }
            } catch (e: Exception) {
                prepared.values.forEach { it.directory.deleteRecursively() }
                throw e
            }
            try {
                for ((id, files) in prepared) {
                    // Once activation begins, retain directories and reserve
                    // native names even on failure: constructors may have run.
                    files.native.keys.forEach { nativeOwners[it] = id }
                    try {
                        SystemModuleMaterializer.verifyFiles(files)
                        active[id] = activate(files, contracts.getValue(id))
                    } catch (e: Exception) {
                        failed += id
                        throw e
                    } catch (e: LinkageError) {
                        failed += id
                        throw e
                    }
                }
            } finally {
                // Prepared trees are deliberately retained for this process,
                // including failed activations. Reclamation needs process leases.
            }
            return active.getValue(module)
        } finally {
            loading = false
        }
    }

    private fun validateContract(contract: Contract, files: SystemModuleMaterializer.Materialized) {
        val modes = listOf(contract.entryClass != null, contract.nativeRoots.isNotEmpty(), contract.assetOnly).count { it }
        if (modes != 1) throw SystemModuleTrustException("Invalid host module contract")
        when {
            contract.entryClass != null -> {
                if (files.dex.isEmpty()) throw SystemModuleTrustException("Missing module adapter DEX")
            }
            contract.nativeRoots.isNotEmpty() -> {
                if (files.dex.isNotEmpty() || !files.native.keys.containsAll(contract.nativeRoots))
                    throw SystemModuleTrustException("Native-only package does not match host contract")
                val bundled = hostLoader as? BaseDexClassLoader
                if (files.native.keys.any { name ->
                        name !in contract.bundledOverrides &&
                        bundled?.findLibrary(name.removePrefix("lib").removeSuffix(".so")) != null
                    }) throw SystemModuleTrustException("Native-only module conflicts with bundled library")
                if (!contract.hostLibraries.containsAll(files.build.hostNativeSha256.keys))
                    throw SystemModuleTrustException("Host native fingerprint contract mismatch")
                for ((name, hash) in files.build.hostNativeSha256) {
                    val location = bundled?.findLibrary(name.removePrefix("lib").removeSuffix(".so"))
                        ?: throw SystemModuleTrustException("Required host library unavailable")
                    if (hostLibraryHash(location) != hash)
                        throw SystemModuleTrustException("Host native dependency fingerprint mismatch")
                }
                nativeOrder(files.native, contract.nativeRoots)
            }
            else -> if (files.dex.isNotEmpty() || files.native.isNotEmpty())
                throw SystemModuleTrustException("Executable code in asset-only module")
        }
    }

    private fun hostLibraryHash(location: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun read(input: java.io.InputStream) {
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                if (n == 0 || n > SystemModuleChannel.MAX_PACKAGE_BYTES - total)
                    throw SystemModuleTrustException("Host native library exceeds bounds")
                total += n
                digest.update(buffer, 0, n)
            }
        }
        val split = location.indexOf("!/")
        if (split < 0) File(location).inputStream().use(::read)
        else java.util.zip.ZipFile(location.substring(0, split)).use { zip ->
            val entry = zip.getEntry(location.substring(split + 2))
                ?: throw SystemModuleTrustException("Host APK native library missing")
            zip.getInputStream(entry).use(::read)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun activate(files: SystemModuleMaterializer.Materialized, contract: Contract): Loaded {
        val assets = File(files.directory, "assets")
        val dependencies = files.build.dependencies.mapNotNull {
            val id = SystemModule.fromId(it.module) ?: throw SystemModuleTrustException("Unknown dependency")
            val dependency = active[id] ?: throw SystemModuleTrustException("Dependency not loaded")
            if (dependency.build.version != it.version) throw SystemModuleTrustException("Dependency version changed")
            dependency.entrypoint?.let { entry -> it.module to entry }
        }.toMap()
        if (contract.entryClass != null) {
            val loader = DexClassLoader(
                files.dex.joinToString(File.pathSeparator) { it.absolutePath },
                null,
                File(files.directory, "lib").absolutePath,
                hostLoader,
            )
            val type = Class.forName(contract.entryClass, false, loader)
            if (type.classLoader !== loader || !SystemModuleEntrypoint::class.java.isAssignableFrom(type))
                throw SystemModuleTrustException("Entrypoint resolved outside module or has incompatible host API")
            val entry = type.getDeclaredConstructor().newInstance() as SystemModuleEntrypoint
            entry.initialize(app, assets, dependencies)
            return Loaded(files.build, assets, entry, loader)
        }
        if (contract.nativeRoots.isNotEmpty()) {
            for (name in files.build.hostNativeSha256.keys.sorted()) {
                System.loadLibrary(name.removePrefix("lib").removeSuffix(".so"))
            }
            for (name in nativeOrder(files.native, contract.nativeRoots)) {
                System.load(files.library(name).absolutePath)
            }
        }
        return Loaded(files.build, assets, null, null)
    }

    companion object {
        internal fun nativeOrder(
            libraries: Map<String, SystemModuleBinary.Native>,
            roots: Set<String>,
        ): List<String> {
            val visiting = mutableSetOf<String>()
            val ordered = linkedSetOf<String>()
            fun visit(name: String) {
                if (name in visiting) throw SystemModuleTrustException("Cyclic native-only load graph")
                if (name in ordered) return
                val binary = libraries[name] ?: throw SystemModuleTrustException("Missing native library")
                visiting += name
                binary.needed.filter { it in libraries }.sorted().forEach(::visit)
                visiting -= name
                ordered += name
            }
            roots.sorted().forEach(::visit)
            return ordered.toList()
        }
    }
}
