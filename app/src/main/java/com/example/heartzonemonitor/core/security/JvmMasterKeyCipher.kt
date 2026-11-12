package com.example.heartzonemonitor.core.security

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class JvmMasterKeyCipher(
    private val secretKey: SecretKey = KeyGenerator.getInstance("AES").apply {
        init(256)
    }.generateKey()
) : MasterKeyCipher {

    override fun encrypt(plaintext: ByteArray): EncryptedPayload {
        return AesGcmCipherHelper.encrypt(secretKey, plaintext)
    }

    override fun decrypt(payload: EncryptedPayload): ByteArray {
        return AesGcmCipherHelper.decrypt(secretKey, payload)
    }
}
