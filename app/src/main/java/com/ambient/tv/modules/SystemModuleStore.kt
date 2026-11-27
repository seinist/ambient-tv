package com.ambient.tv.modules

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
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
        state.current?.takeIf { it.build == build }?.let {
            return@locked verify(directory, module, it)
        }

        val id = UUID.randomUUID().toString()
        val packageDir = File(directory, id)
        if (!packageDir.mkdir()) throw IOException("Cannot create module staging directory")
        var published = false
        try {
            val archive = File(packageDir, "package.zip")
            openArchive(build).use { SystemModuleArchive.stage(it, archive, build) }
            writeSynced(File(packageDir, "manifest.json"), manifestBytes)
            writeSynced(File(packageDir, "manifest.sig"), signature)
            val pointer = Pointer(id, tag, build)
            val result = verify(directory, module, pointer)
            writeState(directory, State(pointer, state.current, maxOf(state.highWater, build.version)))
            published = true
            result
        } finally {
            if (!published) packageDir.deleteRecursively()
        }
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

    private fun verify(directory: File, module: SystemModule, pointer: Pointer): Package {
        if (!idPattern.matches(pointer.id)) throw SystemModuleTrustException("Invalid package pointer")
        val dir = File(directory, pointer.id)
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
        if (!file.exists()) return State()
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

    private fun readBounded(file: File, max: Int): ByteArray = file.inputStream().use { input ->
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

    private fun <T> locked(module: SystemModule, action: (File) -> T): T = synchronized(processLock) {
        val directory = File(root, module.id)
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot open module directory")
        RandomAccessFile(File(directory, ".lock"), "rw").use { file ->
            file.channel.lock().use { action(directory) }
        }
    }

    companion object {
        // FileChannel locks are process-wide; avoid overlapping JVM locks.
        private val processLock = Any()
    }
}
