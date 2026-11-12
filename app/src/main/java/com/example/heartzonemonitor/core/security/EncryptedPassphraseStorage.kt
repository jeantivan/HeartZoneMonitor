package com.example.heartzonemonitor.core.security

import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Arrays

class EncryptedPassphraseStorage(
    private val storageDir: File,
    private val cipher: MasterKeyCipher
) {

    companion object {
        const val PASSPHRASE_FILE_NAME = "database_passphrase.bin"
        private const val IV_LENGTH_BYTES = 12
        private const val PASSPHRASE_LENGTH_BYTES = 32 // 256 bits
    }

    private val passphraseFile: File
        get() = File(storageDir, PASSPHRASE_FILE_NAME)

    @Synchronized
    fun getOrCreatePassphrase(): ByteArray {
        if (passphraseFile.exists()) {
            val fileBytes = passphraseFile.readBytes()
            require(fileBytes.size > IV_LENGTH_BYTES) {
                "Corrupted passphrase file: length (${fileBytes.size}) must exceed IV size ($IV_LENGTH_BYTES)"
            }
            val iv = fileBytes.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = fileBytes.copyOfRange(IV_LENGTH_BYTES, fileBytes.size)
            Arrays.fill(fileBytes, 0.toByte())
            return cipher.decrypt(EncryptedPayload(iv = iv, ciphertext = ciphertext))
        }

        val rawPassphrase = ByteArray(PASSPHRASE_LENGTH_BYTES).apply {
            SecureRandom().nextBytes(this)
        }

        val encryptedPayload = cipher.encrypt(rawPassphrase)
        val combined = encryptedPayload.iv + encryptedPayload.ciphertext

        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        val tempFile = File.createTempFile("passphrase", ".tmp", storageDir)
        FileOutputStream(tempFile).use { fos ->
            fos.write(combined)
            fos.flush()
        }
        if (!tempFile.renameTo(passphraseFile)) {
            tempFile.copyTo(passphraseFile, overwrite = true)
            tempFile.delete()
        }

        return rawPassphrase
    }
}
