package com.example.heartzonemonitor.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Arrays

class MasterKeyCipherAndStorageTest {

    @Test
    fun masterKeyCipher_encryptsAndDecrypts_preservingPlaintext() {
        val cipher: MasterKeyCipher = JvmMasterKeyCipher()
        val original = "super-secret-passphrase-32-bytes!!".toByteArray(Charsets.UTF_8)

        val encrypted = cipher.encrypt(original)
        assertEquals(12, encrypted.iv.size)
        assertFalse(Arrays.equals(original, encrypted.ciphertext))

        val decrypted = cipher.decrypt(encrypted)
        assertArrayEquals(original, decrypted)
    }

    @Test
    fun encryptedPassphraseStorage_createsOnFirstRun_andRestoresOnSubsequentRun() {
        val tempDir = File.createTempFile("keystore_test", "").apply {
            delete()
            mkdir()
        }
        try {
            val cipher: MasterKeyCipher = JvmMasterKeyCipher()
            val storage = EncryptedPassphraseStorage(tempDir, cipher)

            val passphraseFirstRun = storage.getOrCreatePassphrase()
            assertEquals(32, passphraseFirstRun.size)

            val file = File(tempDir, EncryptedPassphraseStorage.PASSPHRASE_FILE_NAME)
            assertTrue(file.exists())
            // 12-byte IV + 32-byte ciphertext + 16-byte GCM tag = 60 bytes
            assertEquals(60, file.length())

            // Subsequent invocation returns identical decrypted passphrase
            val storageSubsequent = EncryptedPassphraseStorage(tempDir, cipher)
            val passphraseSecondRun = storageSubsequent.getOrCreatePassphrase()
            assertArrayEquals(passphraseFirstRun, passphraseSecondRun)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
