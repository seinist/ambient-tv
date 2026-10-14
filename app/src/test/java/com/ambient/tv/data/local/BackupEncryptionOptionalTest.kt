package com.ambient.tv.data.local

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class BackupEncryptionOptionalTest {
    private fun manager(prefs: android.content.SharedPreferences = memoryPreferences()): SettingsBackupManager {
        val app = AppPreferences(prefs)
        return SettingsBackupManager(app, TokenStore(memoryPreferences(), app), null)
    }

    @Test fun normalExportRoundTripsEncryptedAndImports() {
        val plain = manager().exportJson()
        val sealed = BackupEncryption.encrypt(plain, "a good passphrase".toCharArray(), iterations = 10_000)
        assertTrue(BackupEncryption.isEncrypted(sealed))
        val restored = BackupEncryption.decrypt(sealed, "a good passphrase".toCharArray())
        assertEquals(plain, restored)
        manager().importJson(restored)
    }

    @Test fun tamperedCiphertextIsRejectedLikeAWrongPassphrase() {
        val sealed = BackupEncryption.encrypt("""{"schemaVersion":1}""", "a good passphrase".toCharArray(), iterations = 10_000)
        val envelope = Json.parseToJsonElement(sealed).jsonObject
        val data = Base64.getDecoder().decode(envelope.getValue("data").jsonPrimitive.content)
        data[0] = (data[0].toInt() xor 1).toByte()
        val tampered = JsonObject(envelope + ("data" to JsonPrimitive(Base64.getEncoder().encodeToString(data)))).toString()
        assertThrows(BackupEncryption.WrongPassphraseException::class.java) {
            BackupEncryption.decrypt(tampered, "a good passphrase".toCharArray())
        }
    }

    @Test fun defaultsUseStrongKdfAndFreshSaltAndIv() {
        val a = Json.parseToJsonElement(BackupEncryption.encrypt("{}", "a good passphrase".toCharArray())).jsonObject
        val b = Json.parseToJsonElement(BackupEncryption.encrypt("{}", "a good passphrase".toCharArray())).jsonObject
        assertEquals(BackupEncryption.FORMAT, a.getValue("format").jsonPrimitive.content)
        assertTrue(a.getValue("iterations").jsonPrimitive.int >= 210_000)
        assertNotEquals(a.getValue("salt"), b.getValue("salt"))
        assertNotEquals(a.getValue("iv"), b.getValue("iv"))
    }

    @Test fun plainBackupsStillImportAndAreDetectedAsPlain() {
        val plain = manager().exportJson()
        assertFalse(BackupEncryption.isEncrypted(plain))
        manager().importJson(plain)
    }

    @Test fun companionSnapshotPathStaysPlainJson() {
        // Companion sync uses exportJson/importJson directly; encryption is only applied by the export UI.
        val json = manager().exportJson(includeCredentials = true)
        assertFalse(BackupEncryption.isEncrypted(json))
        assertTrue(Json.parseToJsonElement(json).jsonObject.containsKey("schemaVersion"))
    }
}
