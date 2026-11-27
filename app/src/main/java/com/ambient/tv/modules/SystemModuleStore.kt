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
    )
    data class Package(val archive: File, val build: SystemModuleBuild)

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
        openArchive: (SystemModuleBuild) -> InputStream,
    ): Package? = locked(module) { directory ->
        val state = readState(directory)
        val manifest = SystemModuleChannel.authenticate(manifestBytes, signature, publicKey, tag)
        val build = SystemModuleChannel.select(
            manifest, module, processAbi, hostApi, sdk, profile, maxOf(1, state.highWater),
        ) ?: return@locked null
        var currentVerified = false
        state.current?.let {
            val verified = try { verify(directory, module, it) } catch (_: IOException) { null }
            if (verified != null) {
                currentVerified = true
                if (it.build == build) return@locked verified
            }
        }

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
            syncDirectory(packageDir)
            syncDirectory(directory)
            // Once publication starts, retain the payload even if the directory
            // sync fails after rename. Never delete a possibly referenced package.
            publicationStarted = true
            writeState(directory, State(pointer, if (currentVerified) state.current else state.previous, maxOf(state.highWater, build.version)))
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

    /** Deliberate user rollback; the automatic update floor never decreases. */
    fun rollback(module: SystemModule): Package? = locked(module) { directory ->
        val state = readState(directory)
        val previous = state.previous ?: return@locked null
        val result = verify(directory, module, previous)
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
        writeState(directory, State(highWater = state.highWater))
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

    private fun verify(directory: File, module: SystemModule, pointer: Pointer): Package {
        if (!idPattern.matches(pointer.id)) throw SystemModuleTrustException("Invalid package pointer")
        val dir = File(directory, pointer.id)
        if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw SystemModuleTrustException("Invalid stored module directory")
        val manifest = SystemModuleChannel.authenticate(
            readBounded(File(dir, "manifest.json"), SystemModuleChannel.MAX_MANIFEST_BYTES),
            readBounded(File(dir, "manifest.sig"), 256), publicKey, pointer.tag,
        )
        val build = pointer.build
        if (build.module != module.id || build !in manifest.modules ||
            build.abi !in setOf(processAbi, "any") || build.hostApi != hostApi || build.minSdk > sdk) {
            throw SystemModuleTrustException("Stored module is no longer compatible")
        }
        val archive = File(dir, "package.zip")
        SystemModuleArchive.verify(archive, build)
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
        if (state.highWater < 0 ||
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
