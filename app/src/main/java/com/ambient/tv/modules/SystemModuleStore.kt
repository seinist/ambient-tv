package com.ambient.tv.modules

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Private, versioned package store. Publishing an archive does not activate
 * native code. A runtime loader must separately validate and materialize it.
 * The caller supplies a private no-backup directory and a pinned trust key.
 */
internal class SystemModuleStore(
    private val root: File,
    private val publicKey: String,
    private val processAbi: String,
    private val hostApi: Int,
    private val sdk: Int,
) {
    @Serializable
    private data class Pointer(val id: String, val tag: String, val build: SystemModuleBuild)
    @Serializable
    private data class State(
        val current: Pointer? = null,
        val previous: Pointer? = null,
        val highWater: Long = 0,
        val highWaterBuilds: Map<String, SystemModuleBuild> = emptyMap(),
    )
    data class Package(val archive: File, val build: SystemModuleBuild)
    data class SignedCatalog(val tag: String, val manifest: ByteArray, val signature: ByteArray)

    /**
     * Discover the highest compatible signed version, not GitHub's presentation
     * order. Never open payloads during discovery. Recheck the version floor
     * under the installation lock before publishing the chosen candidate.
     */
    fun latestCatalog(
        module: SystemModule,
        catalogs: Sequence<SignedCatalog>,
        profile: String = "baseline",
        compatible: (SystemModuleBuild) -> Boolean = { true },
    ): SignedCatalog? {
        val floor = locked(module) { maxOf(1, readState(it).highWater) }
        val seen = mutableMapOf<Triple<Long, String, String>, SystemModuleBuild>()
        var winner: SignedCatalog? = null
        var newest: SystemModuleBuild? = null
        for (catalog in catalogs) {
            val manifest = SystemModuleChannel.authenticate(catalog.manifest, catalog.signature, publicKey, catalog.tag)
            for (build in manifest.modules.filter { it.module == module.id }) {
                val key = Triple(build.version, build.abi, build.profile)
                val prior = seen.putIfAbsent(key, build)
                if (prior != null && prior != build)
                    throw SystemModuleTrustException("Conflicting signed module catalogs")
            }
            val candidate = SystemModuleChannel.select(manifest, module, processAbi, hostApi, sdk, profile, floor, compatible)
                ?: continue
            val previous = newest
            // Current publishers emit a single version per catalog. Prefer the
            // requested profile and concrete ABI for equal-version alternatives.
            fun rank(build: SystemModuleBuild): Int =
                (if (build.abi == processAbi) 10 else 0) +
                    (if (build.profile == profile) 2 else if (build.profile == "optimized") 1 else 0)
            if (previous == null || candidate.version > previous.version ||
                candidate.version == previous.version && rank(candidate) > rank(previous)) {
                winner = catalog
                newest = candidate
            }
        }
        return winner
    }

    private val json = Json { ignoreUnknownKeys = false }
    private val idPattern = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")

    /**
     * Download is opened only after the catalog has been authenticated and a
     * compatible entry at or above the persisted high-water mark was found.
     * Failure before atomic publication leaves the current pointer untouched.
     */
    fun install(
        module: SystemModule,
        tag: String,
        manifestBytes: ByteArray,
        signature: ByteArray,
        profile: String = "baseline",
        validateCandidate: (Package) -> Unit = {},
        compatible: (SystemModuleBuild) -> Boolean = { true },
        beforePublish: () -> Unit = {},
        openArchive: (SystemModuleBuild) -> InputStream,
    ): Package? = locked(module) { directory ->
        val state = readState(directory)
        val manifest = SystemModuleChannel.authenticate(manifestBytes, signature, publicKey, tag)
        val build = SystemModuleChannel.select(
            manifest, module, processAbi, hostApi, sdk, profile, maxOf(1, state.highWater), compatible,
        ) ?: return@locked null
        val identity = "${build.abi}/${build.profile}"
        val remembered = state.highWaterBuilds + listOfNotNull(state.current, state.previous)
            .filter { it.build.version == state.highWater }.associate { "${it.build.abi}/${it.build.profile}" to it.build }
        if (build.version == state.highWater && remembered[identity]?.let { it != build } == true)
            throw SystemModuleTrustException("Conflicting module payload at existing version")
        // A signed archive can still belong to an older host binding.
        // Never displace a compatible rollback with an incompatible selection.
        fun verifiedCompatible(pointer: Pointer?): Package? {
            if (pointer == null) return null
            val pkg = try { verify(directory, module, pointer) } catch (_: IOException) { return null }
            // Do not swallow cancellation or compatibility-probe failures.
            return pkg.takeIf { compatible(it.build) }
        }
        val current = verifiedCompatible(state.current)
        if (current != null && current.build == build) {
            validateCandidate(current)
            return@locked current
        }
        val rollback = if (current != null) state.current
            else state.previous?.takeIf { verifiedCompatible(it) != null }

        val id = UUID.randomUUID().toString()
        val packageDir = File(directory, id)
        if (!packageDir.mkdir()) throw IOException("Cannot create module staging directory")
        var publicationStarted = false
        try {
            val archive = File(packageDir, "package.zip")
            openArchive(build).use { SystemModuleArchive.stage(it, archive, build) }
            writeSynced(File(packageDir, "manifest.json"), manifestBytes)
            writeSynced(File(packageDir, "manifest.sig"), signature)
            val pointer = Pointer(id, tag, build)
            val result = verify(directory, module, pointer)
            validateCandidate(result)
            syncDirectory(packageDir)
            syncDirectory(directory)
            // Once publication starts, retain the payload even if the directory
            // sync fails after rename. Never delete a possibly referenced package.
            beforePublish()
            publicationStarted = true
            val floorBuilds = if (build.version == state.highWater) remembered + (identity to build)
                else mapOf(identity to build)
            writeState(directory, State(pointer, rollback, maxOf(state.highWater, build.version), floorBuilds))
            result
        } finally {
            if (!publicationStarted) packageDir.deleteRecursively()
        }
    }

    /** Selection metadata only, for recovery actions; never use as executable trust evidence. */
    fun selectedMetadata(module: SystemModule): SystemModuleBuild? = locked(module) { directory ->
        readState(directory).current?.build
    }

    /** Re-authenticate the stored manifest and archive before exposing it. */
    fun current(module: SystemModule): Package? = locked(module) { directory ->
        readState(directory).current?.let { verify(directory, module, it) }
    }

    /** Authenticate the selected archive even after an APK changes ABI/host API.
     * This is selection evidence only, not permission to load executable code.
     */
    fun currentForSelection(module: SystemModule): Package? = locked(module) { directory ->
        readState(directory).current?.let { verify(directory, module, it, requireCompatibleHost = false) }
    }

    fun isHostCompatible(build: SystemModuleBuild): Boolean =
        build.abi in setOf(processAbi, "any") && build.hostApi == hostApi && build.minSdk <= sdk

    /** Deliberate user rollback; the automatic update floor never decreases. */
    fun rollback(module: SystemModule, validateCandidate: (Package) -> Unit = {}): Package? = locked(module) { directory ->
        val state = readState(directory)
        val previous = state.previous ?: return@locked null
        val result = verify(directory, module, previous)
        validateCandidate(result)
        writeState(directory, state.copy(current = previous, previous = state.current))
        result
    }

    /**
     * Clear the downloaded selection, not user data or a bundled library.
     * Payload reclamation is deferred until the runtime can prove that no
     * process still references the files. Keep the high-water mark.
     */
    fun remove(module: SystemModule) = locked(module) { directory ->
        val state = readState(directory)
        val remembered = state.highWaterBuilds + listOfNotNull(state.current, state.previous)
            .filter { it.build.version == state.highWater }.associate { "${it.build.abi}/${it.build.profile}" to it.build }
        writeState(directory, State(highWater = state.highWater, highWaterBuilds = remembered))
    }

    /** Only call under the exclusive cross-process runtime lease. */
    fun collectUnused(module: SystemModule) = locked(module) { directory ->
        val state = readState(directory)
        val keep = listOfNotNull(state.current?.id, state.previous?.id).toSet()
        directory.listFiles().orEmpty().filter {
            idPattern.matches(it.name) && it.name !in keep &&
                Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS)
        }.forEach { candidate ->
            if (!candidate.deleteRecursively()) throw IOException("Cannot reclaim old module package")
        }
        syncDirectory(directory)
    }

    private fun verify(directory: File, module: SystemModule, pointer: Pointer, requireCompatibleHost: Boolean = true): Package {
        if (!idPattern.matches(pointer.id)) throw SystemModuleTrustException("Invalid package pointer")
        val dir = File(directory, pointer.id)
        if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw SystemModuleTrustException("Invalid stored module directory")
        val manifest = SystemModuleChannel.authenticate(
            readBounded(File(dir, "manifest.json"), SystemModuleChannel.MAX_MANIFEST_BYTES),
            readBounded(File(dir, "manifest.sig"), 256), publicKey, pointer.tag,
        )
        val build = pointer.build
        if (build.module != module.id || build !in manifest.modules)
            throw SystemModuleTrustException("Stored module does not match signed catalog")
        val archive = File(dir, "package.zip")
        SystemModuleArchive.verify(archive, build)
        if (requireCompatibleHost && !isHostCompatible(build))
            throw SystemModuleTrustException("Stored module is no longer compatible")
        return Package(archive, build)
    }

    private fun readState(directory: File): State {
        val file = File(directory, "state.json")
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return State()
        val state = try {
            json.decodeFromString<State>(readBounded(file, 64 * 1024).decodeToString(throwOnInvalidSequence = true))
        } catch (_: IllegalArgumentException) {
            throw SystemModuleTrustException("Invalid module state")
        }
        if (state.highWater < 0 || state.highWaterBuilds.size > 15 ||
            state.highWaterBuilds.any { (key, build) -> key != "${build.abi}/${build.profile}" || build.version != state.highWater } ||
            listOfNotNull(state.current, state.previous).any {
                !idPattern.matches(it.id) || it.build.version !in 1..state.highWater
            } || (state.current != null && state.current.id == state.previous?.id)) {
            throw SystemModuleTrustException("Invalid module state pointers")
        }
        return state
    }

    private fun writeState(directory: File, state: State) {
        val target = File(directory, "state.json")
        val temp = File.createTempFile("state-", ".tmp", directory)
        try {
            writeSynced(temp, json.encodeToString(state).encodeToByteArray())
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(directory)
        } finally {
            temp.delete()
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
    }

    private fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun readBounded(file: File, max: Int): ByteArray {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw SystemModuleTrustException("Invalid stored metadata file")
        return file.inputStream().use { input ->
        val buffer = ByteArray(max + 1)
        var count = 0
        while (count < buffer.size) {
            val n = input.read(buffer, count, buffer.size - count)
            if (n == -1) break
            if (n == 0) throw IOException("Stored metadata stream made no progress")
            count += n
        }
        if (count !in 1..max) throw SystemModuleTrustException("Stored metadata exceeds bounds")
        buffer.copyOf(count)
        }
    }

    private fun <T> locked(module: SystemModule, action: (File) -> T): T = synchronized(processLock) {
        val directory = File(root, module.id)
        if (!root.mkdirs() && !Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw IOException("Cannot open private module root")
        if (!directory.mkdir()) {
            if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
                throw IOException("Cannot open module directory")
        } else {
            syncDirectory(root)
        }
        val lock = File(directory, ".lock")
        if (Files.exists(lock.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(lock.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw SystemModuleTrustException("Invalid module lock")
        RandomAccessFile(lock, "rw").use { file ->
            file.channel.lock().use { action(directory) }
        }
    }

    companion object {
        // FileChannel locks are process-wide; avoid overlapping JVM locks.
        private val processLock = Any()
    }
}
