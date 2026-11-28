package com.ambient.tv.modules

import android.content.Context
import dalvik.system.BaseDexClassLoader
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
        val hostBindingSha256: String? = null,
        val dexOwnership: ModuleDexOwnership? = null,
        val dexNativeRoots: Set<String> = emptySet(),
        val dexOptionalNativeRoots: Set<String> = emptySet(),
        val dexAlternative: Contract? = null,
        val requireSoname: Boolean = false,
        // Module-to-module dependencies require an explicit reviewed host contract.
        val allowedModuleDependencies: Set<SystemModule> = emptySet(),
    )

    data class Loaded(
        val build: SystemModuleBuild,
        val assets: File,
        val entrypoint: SystemModuleEntrypoint?,
        val classLoader: ClassLoader?,
        val nativeDirectory: File? = null,
    )

    private val active = mutableMapOf<SystemModule, Loaded>()
    private val failed = mutableSetOf<SystemModule>()
    private val nativeOwners = mutableMapOf<String, SystemModule>()
    // APK-owned libraries are immutable for this installed app process. Cache
    // their verified fingerprints and only explicitly load each SONAME once.
    private val verifiedHostHashes = mutableMapOf<String, String>()
    private val verifiedHostElf = mutableMapOf<Pair<String, String>, SystemModuleBinary.Native>()
    private val loadedHostLibraries = mutableSetOf<String>()
    // Reserve even unsuccessful linker attempts: native constructors can
    // have run before an error was reported.
    private val reservedHostLibraries = mutableSetOf<String>()
    private var loading = false

    /** Call on a background worker; all initialization is serialized. */
    @Synchronized
    fun load(module: SystemModule, selected: SystemModuleStore.Package): Loaded {
        if (selected.build.module != module.id || !store.isHostCompatible(selected.build) ||
            !matchesHost(module, selected.build))
            throw SystemModuleTrustException("Selected module is incompatible with installed host")
        active[module]?.let {
            if (it.build != selected.build)
                throw SystemModuleTrustException("Loaded module differs from selected package; restart required")
            return it
        }
        if (loading) throw SystemModuleTrustException("Recursive module runtime activation")
        if (module in failed) throw SystemModuleTrustException("Module activation failed; restart required")
        loading = true
        try {
            // The caller holds a process lease, so the exact authenticated
            // archive remains available even if another process replaces its
            // selection. Never re-read the root pointer after host validation.
            val order = SystemModuleDependencies.orderSelected(module, selected, store::current)
            // Prepare the complete graph before executing any new code.
            val prepared = linkedMapOf<SystemModule, SystemModuleMaterializer.Materialized>()
            val reserved = nativeOwners.toMutableMap()
            val reservedHost = reservedHostLibraries.toMutableSet()
            try {
                for (pkg in order) {
                    val id = SystemModule.fromId(pkg.build.module)
                        ?: throw SystemModuleTrustException("Unknown module")
                    if (id in failed) throw SystemModuleTrustException("Dependency activation failed; restart required")
                    active[id]?.let {
                        if (it.build != pkg.build) throw SystemModuleTrustException("Loaded module differs from selected package; restart required")
                    }
                    if (id in active) continue
                    val contract = contractFor(id, pkg.build)
                    if (!contract.hostLibraries.containsAll(pkg.build.hostNativeSha256.keys))
                        throw SystemModuleTrustException("Unapproved host native dependency")
                    val files = SystemModuleMaterializer.prepare(pkg, root, pageSize, pkg.build.hostNativeSha256.keys)
                    prepared[id] = files
                    validateContract(contract, files)
                    // DEX adapters use a separate linker namespace. All
                    // host-loader native roots and APK-owned dependencies
                    // must be disjoint across the complete graph.
                    if (contract.entryClass == null)
                        reserveGlobalNativeNames(id, files.native.keys, files.build.hostNativeSha256.keys,
                            reserved, reservedHost)
                    SystemModuleMaterializer.verifyFiles(files)
                }
            } catch (e: Exception) {
                prepared.values.forEach { it.directory.deleteRecursively() }
                throw e
            }
            try {
                for ((id, files) in prepared) {
                    // Once activation begins, retain directories and reserve
                    // native names even on failure: constructors may have run.
                    val contract = contractFor(id, files.build)
                    if (contract.entryClass == null)
                        reserveGlobalNativeNames(id, files.native.keys, files.build.hostNativeSha256.keys,
                            nativeOwners, reservedHostLibraries)
                    try {
                        SystemModuleMaterializer.verifyFiles(files)
                        active[id] = activate(files, contract)
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

    /** Compare authenticated metadata before downloading an archive. A mismatch
     * is an incompatible release, not evidence that the signed payload is corrupt.
     * I/O failures remain errors rather than silently selecting an older release.
     */
    @Synchronized
    fun matchesHost(module: SystemModule, build: SystemModuleBuild): Boolean {
        val contract = contractFor(module, build)
        if (build.module != module.id || build.hostBindingSha256 != contract.hostBindingSha256 ||
            !dependenciesAllowed(contract, build) ||
            !contract.hostLibraries.containsAll(build.hostNativeSha256.keys)) return false
        if (contract.entryClass != null && build.hostNativeSha256.isNotEmpty()) return false
        val bundled = hostLoader as? BaseDexClassLoader
        return build.hostNativeSha256.all { (name, hash) ->
            val location = bundled?.findLibrary(name.removePrefix("lib").removeSuffix(".so"))
            location != null && hostLibraryHash(location) == hash
        }
    }

    /** Validate without initializing classes or invoking the linker. */
    @Synchronized
    fun validateCandidate(module: SystemModule, pkg: SystemModuleStore.Package) {
        val contract = contractFor(module, pkg.build)
        if (pkg.build.module != module.id || !dependenciesAllowed(contract, pkg.build) ||
            !contract.hostLibraries.containsAll(pkg.build.hostNativeSha256.keys))
            throw SystemModuleTrustException("Unapproved module or host native dependency")
        val prepared = SystemModuleMaterializer.prepare(pkg, root, pageSize, pkg.build.hostNativeSha256.keys)
        try {
            validateContract(contract, prepared)
            SystemModuleMaterializer.verifyFiles(prepared)
        } finally {
            prepared.directory.deleteRecursively()
        }
    }

    private fun contractFor(module: SystemModule, build: SystemModuleBuild): Contract {
        val primary = contracts[module] ?: throw SystemModuleTrustException("No host module contract")
        return primary.dexAlternative?.takeIf { it.hostBindingSha256 == build.hostBindingSha256 } ?: primary
    }

    private fun validateContract(contract: Contract, files: SystemModuleMaterializer.Materialized) {
        if (!dependenciesAllowed(contract, files.build))
            throw SystemModuleTrustException("Undeclared host module dependency")
        if (files.build.hostBindingSha256 != contract.hostBindingSha256)
            throw SystemModuleTrustException("Host language binding fingerprint mismatch")
        val modes = listOf(contract.entryClass != null, contract.nativeRoots.isNotEmpty(), contract.assetOnly).count { it }
        if (modes != 1) throw SystemModuleTrustException("Invalid host module contract")
        when {
            contract.entryClass != null -> {
                if (files.dex.isEmpty() || !files.native.keys.containsAll(contract.dexNativeRoots) ||
                    !((contract.dexNativeRoots + contract.dexOptionalNativeRoots).containsAll(files.native.keys)) ||
                    files.build.hostNativeSha256.isNotEmpty())
                    throw SystemModuleTrustException("Module adapter DEX or native payload mismatch")
                val visited = nativeClosure(files.native, contract.dexNativeRoots)
                if (visited != files.native.keys) throw SystemModuleTrustException("Unused private native dependency")
                val ownership = contract.dexOwnership
                    ?: throw SystemModuleTrustException("Module adapter ownership missing")
                if (contract.entryClass !in files.dexClasses || !ownership.accepts(files.dexClasses) ||
                    !ownership.acceptsReferences(files.dexClasses, files.dexReferences))
                    throw SystemModuleTrustException("Module DEX defines foreign classes or lacks its entrypoint")
            }
            contract.nativeRoots.isNotEmpty() -> {
                if (files.dex.isNotEmpty() || files.native.keys != contract.nativeRoots)
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
                if (contract.requireSoname && files.native.any { (name, binary) -> binary.soname != name })
                    throw SystemModuleTrustException("Native runtime requires matching ELF SONAME")
                hostLoadOrder(files)
                nativeOrder(files.native, contract.nativeRoots)
            }
            else -> if (files.dex.isNotEmpty() || files.native.isNotEmpty() || files.build.hostNativeSha256.isNotEmpty())
                throw SystemModuleTrustException("Executable code or native dependency in asset-only module")
        }
    }

    private fun hostLibraryHash(location: String): String = verifiedHostHashes.getOrPut(location) {
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
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /**
     * Inspect the exact fingerprinted APK libraries before any JNI activation.
     * Android may return a standalone path or a path into the installed APK.
     * Cache parsed metadata for the lifetime of this immutable APK process.
     * A ZIP entry is copied to a bounded, private temporary file solely for
     * random-access ELF inspection; it is never passed to System.load.
     */
    private fun hostLibraryElf(location: String, abi: String): SystemModuleBinary.Native =
        verifiedHostElf.getOrPut(location to abi) {
            val split = location.indexOf("!/")
            if (split < 0) {
                SystemModuleBinary.elf(File(location), abi, pageSize)
            } else {
                val temp = File.createTempFile("host-elf-", ".tmp", root)
                try {
                    java.util.zip.ZipFile(location.substring(0, split)).use { zip ->
                        val entry = zip.getEntry(location.substring(split + 2))
                            ?: throw SystemModuleTrustException("Host APK native library missing")
                        if (entry.isDirectory || entry.size !in 1..SystemModuleChannel.MAX_PACKAGE_BYTES)
                            throw SystemModuleTrustException("Host ELF exceeds bounds")
                        zip.getInputStream(entry).use { input ->
                            temp.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n == -1) break
                                    if (n == 0 || n > entry.size - total)
                                        throw SystemModuleTrustException("Host ELF exceeds declared size")
                                    output.write(buffer, 0, n)
                                    total += n
                                }
                                if (total != entry.size)
                                    throw SystemModuleTrustException("Host ELF is truncated")
                            }
                        }
                    }
                    SystemModuleBinary.elf(temp, abi, pageSize)
                } finally {
                    temp.delete()
                }
            }
        }

    private fun hostLoadOrder(files: SystemModuleMaterializer.Materialized): List<String> {
        val bundled = hostLoader as? BaseDexClassLoader
        val host = files.build.hostNativeSha256.keys.associateWith { name ->
            if (name in nativeOwners)
                throw SystemModuleTrustException("Host native dependency is already owned by a module")
            val location = bundled?.findLibrary(name.removePrefix("lib").removeSuffix(".so"))
                ?: throw SystemModuleTrustException("Required host library unavailable")
            hostLibraryElf(location, files.build.abi)
        }
        return sharedHostOrder(files.native, host)
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
            val loader = OwnedModuleDexClassLoader(
                files.dex, File(files.directory, "lib"), hostLoader,
                checkNotNull(contract.dexOwnership),
            )
            val type = Class.forName(contract.entryClass, false, loader)
            if (type.classLoader !== loader || !SystemModuleEntrypoint::class.java.isAssignableFrom(type))
                throw SystemModuleTrustException("Entrypoint resolved outside module or has incompatible host API")
            val entry = type.getDeclaredConstructor().newInstance() as SystemModuleEntrypoint
            entry.initialize(app, assets, dependencies)
            return Loaded(files.build, assets, entry, loader)
        }
        if (contract.nativeRoots.isNotEmpty()) {
            for (name in hostLoadOrder(files)) {
                if (name !in loadedHostLibraries) {
                    System.loadLibrary(name.removePrefix("lib").removeSuffix(".so"))
                    loadedHostLibraries += name
                }
            }
            for (name in nativeOrder(files.native, contract.nativeRoots)) {
                System.load(files.library(name).absolutePath)
            }
        }
        return Loaded(files.build, assets, null, null, File(files.directory, "lib").takeIf { files.native.isNotEmpty() })
    }

    companion object {
        /** Preflight both directions, including dependencies of earlier or
         * failed activation attempts. Shared APK libraries may be reused by
         * multiple modules, but may never be claimed as replaceable roots. */
        internal fun reserveGlobalNativeNames(
            module: SystemModule,
            roots: Set<String>,
            host: Set<String>,
            owners: MutableMap<String, SystemModule>,
            reservedHost: MutableSet<String>,
        ) {
            if (roots.any { it in reservedHost || it in host ||
                    owners[it]?.let { owner -> owner != module } == true } ||
                host.any { it in owners })
                throw SystemModuleTrustException("Conflicting global native library ownership")
            roots.forEach { owners[it] = module }
            reservedHost.addAll(host)
        }

        internal fun dependenciesAllowed(contract: Contract, build: SystemModuleBuild): Boolean =
            build.dependencies.all { SystemModule.fromId(it.module) in contract.allowedModuleDependencies }

        /**
         * Exact host closure, not just direct DT_NEEDED edges from the module.
         * Host-only cycles may be resolved by Android's linker. No edge may
         * return from a shared APK library into a replaceable module root.
         */
        internal fun sharedHostOrder(
            module: Map<String, SystemModuleBinary.Native>,
            host: Map<String, SystemModuleBinary.Native>,
        ): List<String> {
            if (host.keys.any { it in module || SystemModuleMaterializer.isPlatformLibrary(it) })
                throw SystemModuleTrustException("Conflicting host native dependency")
            val visited = mutableSetOf<String>()
            val ordered = mutableListOf<String>()
            fun visit(name: String) {
                if (SystemModuleMaterializer.isPlatformLibrary(name)) return
                if (name in module)
                    throw SystemModuleTrustException("Host library links back into optional module")
                val binary = host[name] ?: throw SystemModuleTrustException("Unsigned transitive host dependency")
                if (!visited.add(name)) return
                if (binary.soname != null && binary.soname != name)
                    throw SystemModuleTrustException("Host ELF SONAME mismatch")
                binary.needed.sorted().forEach(::visit)
                ordered += name
            }
            module.values.flatMap { it.needed }.toSortedSet().filter { it !in module }.forEach(::visit)
            if (visited != host.keys)
                throw SystemModuleTrustException("Unused signed host native dependency")
            return ordered
        }

        internal fun nativeClosure(libraries: Map<String, SystemModuleBinary.Native>, roots: Set<String>): Set<String> {
            val visited = mutableSetOf<String>()
            fun visit(name: String) {
                if (!visited.add(name)) return
                val binary = libraries[name] ?: throw SystemModuleTrustException("Missing native library")
                binary.needed.filter { it in libraries }.forEach(::visit)
            }
            roots.forEach(::visit)
            return visited
        }

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
