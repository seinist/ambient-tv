package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupEncryptionTest {
    private val plain = """{"schemaVersion":49,"servers":[{"token":"secret-token"}]}"""

    @Test
    fun `round trip with the right passphrase`() {
        val sealed = BackupEncryption.encrypt(plain, "correct horse".toCharArray(), iterations = 10_000)
        assertTrue(BackupEncryption.isEncrypted(sealed))
        assertFalse("secret-token" in sealed)
        assertEquals(plain, BackupEncryption.decrypt(sealed, "correct horse".toCharArray()))
    }

    @Test(expected = BackupEncryption.WrongPassphraseException::class)
    fun `wrong passphrase is reported as such`() {
        val sealed = BackupEncryption.encrypt(plain, "correct horse".toCharArray(), iterations = 10_000)
        BackupEncryption.decrypt(sealed, "wrong horse!".toCharArray())
    }

    @Test
    fun `plain backups are not mistaken for encrypted ones`() {
        assertFalse(BackupEncryption.isEncrypted(plain))
        assertFalse(BackupEncryption.isEncrypted("not json"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `short passphrases are refused`() {
        BackupEncryption.encrypt(plain, "short".toCharArray())
    }
}
