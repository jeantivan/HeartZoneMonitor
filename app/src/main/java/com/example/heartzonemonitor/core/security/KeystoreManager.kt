package com.example.heartzonemonitor.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class KeystoreManager(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : MasterKeyCipher {

    companion object {
        const val DEFAULT_KEY_ALIAS = "hzm_master_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private fun getOrCreateMasterKey(): SecretKey {
        if (!keyStore.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val parameterSpec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(parameterSpec)
            keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    override fun encrypt(plaintext: ByteArray): EncryptedPayload {
        val secretKey = getOrCreateMasterKey()
        return AesGcmCipherHelper.encrypt(secretKey, plaintext)
    }

    override fun decrypt(payload: EncryptedPayload): ByteArray {
        val secretKey = getOrCreateMasterKey()
        return AesGcmCipherHelper.decrypt(secretKey, payload)
    }
}
