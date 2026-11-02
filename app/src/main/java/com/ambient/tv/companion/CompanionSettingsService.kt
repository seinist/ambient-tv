package com.ambient.tv.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.ambient.tv.data.local.SettingsBackup
import com.ambient.tv.data.local.SettingsBackupManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompanionSettingsService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backups: SettingsBackupManager,
) {
    private val rollback get() = AtomicFile(File(context.noBackupFilesDir, "companion-settings-rollback.bin"))
    fun hasRollback() = rollback.baseFile.exists()
    fun preview(raw: String): SettingsBackup {
        require(raw.toByteArray().size <= CompanionBackupPolicy.MAX_BYTES)
        return backups.previewJson(raw)
    }
    fun snapshot() = backups.previewJson(backups.exportJson(includeCredentials = true))
    fun export(credentials: Boolean, mode: String = "settings", sessions: Boolean = credentials): String {
        val snapshot = backups.previewJson(backups.exportJson(credentials, includeSessions = sessions))
        val source = if (mode in setOf("accounts", "settings")) selectSyncAccounts(snapshot, defaultSyncAccounts(syncAccountChoices(snapshot))) else snapshot
        return CompanionBackupPolicy.prepare(source, credentials, mode, sessions)
    }
    fun prepareFile(raw: String, credentials: Boolean, mode: String = "settings", sessions: Boolean = credentials) =
        CompanionBackupPolicy.prepare(preview(raw), credentials, mode, sessions)

    @Synchronized fun apply(raw: String, mode: String, overwrite: Boolean, deviceSettings: Boolean) {
        val source = preview(raw)
        val before = backups.exportJson(includeCredentials = true)
        val merged = CompanionBackupPolicy.merge(source, backups.previewJson(before), mode, overwrite, deviceSettings)
        backups.previewJson(merged)
        saveRollback(before) // Durable encrypted recovery before the first preference write.
        try { backups.importJson(merged) }
        catch (failure: Exception) {
            runCatching { backups.importJson(before) }
            throw IllegalStateException("Settings import failed; recovery retained", failure)
        }
    }

    @Synchronized fun restore() {
        val bytes = rollback.openRead().use { input ->
            val value = input.readBytesLimited(4 * 1024 * 1024)
            require(value.size > 28); value
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val raw = cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        backups.previewJson(raw)
        val current = backups.exportJson(includeCredentials = true)
        try { backups.importJson(raw) } catch (failure: Exception) {
            runCatching { backups.importJson(current) }
            throw IllegalStateException("Recovery failed", failure)
        }
        // Retain the recovery until another successful import replaces it.
    }

    private fun saveRollback(raw: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(raw.toByteArray())
        require(bytes.size <= 4 * 1024 * 1024)
        val file = rollback
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }

    private fun key(): SecretKey {
        val alias = "ambient_companion_settings_rollback_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}

internal fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(out.size() + n <= limit) { "Backup too large" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
