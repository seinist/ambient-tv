package com.ambient.tv.data.files

import android.content.Context
import android.net.Uri
import com.ambient.tv.util.suspendResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceStorageRoot(val path: String, val label: String?)

/**
 * Small text files the user loads into settings: WireGuard tunnel configs and SSH private keys.
 * Sources are device/USB storage, the folders/NAS shares configured under Files, and the system
 * document picker where one exists (Android TV usually ships without DocumentsUI).
 */
@Singleton
class TextFileImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: FileStore,
    private val access: FileAccess,
) {
    /** Configured Files sources double as a file picker; [excludeId] hides the source being edited. */
    fun sources(excludeId: String? = null): List<FileSource> = store.sources().filter { it.id != excludeId }

    fun root(source: FileSource): String = access.root(source)

    suspend fun listRemote(source: FileSource, path: String, accept: (FileEntry) -> Boolean): Result<List<FileEntry>> =
        suspendResult { access.list(source, path, accept = accept).entries }

    suspend fun readRemote(source: FileSource, path: String, maxBytes: Int): String = access.readText(source, path, maxBytes)

    /**
     * Device storage can only be browsed where the build may hold that access: API 29 with read
     * permission, or API 30+ when "All files access" is declared.
     */
    private fun deviceImportSupported(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return true
        val requested = runCatching {
            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                .requestedPermissions
        }.getOrNull()
        return requested?.contains(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE) == true
    }

    /** Internal storage first, then mounted USB/SD volumes, derived from the app's per-volume dirs. */
    fun deviceRoots(): List<DeviceStorageRoot> {
        if (!deviceImportSupported()) return emptyList()
        val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
        return context.getExternalFilesDirs(null).filterNotNull().mapIndexedNotNull { index, dir ->
            val root = dir.absolutePath.substringBefore("/Android/data/").takeIf { it != dir.absolutePath }
                ?: return@mapIndexedNotNull null
            val label = if (index == 0) null else storage?.getStorageVolume(java.io.File(root))?.getDescription(context)
            DeviceStorageRoot(root, label)
        }.distinctBy { it.path }
    }

    fun hasDeviceAccess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    suspend fun listDevice(path: String, accept: (FileEntry) -> Boolean, includeHidden: Boolean = false): Result<List<FileEntry>> = suspendResult {
        withContext(Dispatchers.IO) {
            val children = java.io.File(path).listFiles() ?: throw java.io.IOException("Not readable")
            children.asSequence()
                // ~/.ssh is hidden; key imports may show dot entries.
                .filter { includeHidden || !it.isHidden }
                .map { FileEntry(DEVICE_SOURCE_ID, it.absolutePath, it.name, it.isDirectory, if (it.isFile) it.length() else 0, it.lastModified()) }
                .filter { it.directory || accept(it) }
                .sortedWith(compareBy<FileEntry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .take(500)
                .toList()
        }
    }

    suspend fun readDevice(path: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val file = java.io.File(path)
        if (file.length() > maxBytes) throw java.io.IOException("File too large")
        com.ambient.tv.vpn.decodeWireGuardBytes(file.readBytes())
    }

    suspend fun readUri(uri: Uri, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException()
        input.use {
            val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8 * 1024)
            while (true) {
                val read = it.read(chunk)
                if (read < 0) break
                out.write(chunk, 0, read)
                if (out.size() > maxBytes) throw java.io.IOException("File too large")
            }
            // UTF-8, UTF-8 with BOM or the UTF-16 that Windows tools write.
            com.ambient.tv.vpn.decodeWireGuardBytes(out.toByteArray())
        }
    }

    companion object {
        const val DEVICE_SOURCE_ID = "device"
    }
}

/** Private key files: no extension (id_ed25519, id_rsa), .key, .pem, .ppk, .openssh; never the .pub half. */
fun isSshKeyFile(entry: FileEntry): Boolean {
    val name = entry.name.lowercase()
    if (name.endsWith(".pub") || entry.size > SSH_KEY_LIMIT) return false
    val extension = FilePaths.extension(entry.name)
    return name.startsWith("id_") || extension in setOf("key", "pem", "ppk", "openssh") || ('.' !in name && name.isNotEmpty() && entry.size in 64..SSH_KEY_LIMIT.toLong())
}
