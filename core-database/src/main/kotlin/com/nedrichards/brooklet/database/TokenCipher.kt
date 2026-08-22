package com.nedrichards.brooklet.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TokenCipher {
    data class Encrypted(val ciphertext: ByteArray, val iv: ByteArray)
    private val alias = "brooklet_api_token"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun encrypt(token: String): Encrypted {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Encrypted(cipher.doFinal(token.encodeToByteArray()), cipher.iv)
    }

    fun decrypt(value: Encrypted): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.iv))
        return cipher.doFinal(value.ciphertext).decodeToString()
    }

    fun deleteKey() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun key(): SecretKey = (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
}
