package com.kita.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCryptoProvider {
    fun encrypt(plain: String): Result<String>
    fun decrypt(stored: String): SecretDecryptResult
}

sealed class SecretDecryptResult {
    data class Success(val value: String) : SecretDecryptResult()
    object Missing : SecretDecryptResult()
    object Corrupt : SecretDecryptResult()
    object KeystoreUnavailable : SecretDecryptResult()
}

object Secret {
    private const val ALIAS = "kita_api_key"
    private const val PREFIX = "enc1:"
    private const val IV_SIZE = 12

    private class KeystoreUnavailableEx(cause: Throwable) : Exception(cause)

    private val androidProvider = object : SecretCryptoProvider {
        private fun keyForEncrypt(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            return kg.generateKey()
        }

        override fun encrypt(plain: String): Result<String> {
            if (plain.isEmpty() || isEncrypted(plain)) return Result.success(plain)
            return try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, keyForEncrypt())
                val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
                Result.success(PREFIX + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP))
            } catch (e: Exception) { Result.failure(e) }
        }

        override fun decrypt(stored: String): SecretDecryptResult {
            if (stored.isEmpty()) return SecretDecryptResult.Success("")
            if (!isEncrypted(stored)) return SecretDecryptResult.Success(stored)
            return try {
                val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val key = ks.getKey(ALIAS, null) as? SecretKey ?: return SecretDecryptResult.Missing
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(0, IV_SIZE)))
                SecretDecryptResult.Success(String(c.doFinal(raw, IV_SIZE, raw.size - IV_SIZE), Charsets.UTF_8))
            } catch (e: AEADBadTagException) { SecretDecryptResult.Corrupt }
            catch (e: Exception) { SecretDecryptResult.KeystoreUnavailable }
        }
    }

    internal var provider: SecretCryptoProvider = androidProvider
    fun isEncrypted(s: String) = s.startsWith(PREFIX)
    fun encrypt(plain: String) = provider.encrypt(plain)
    fun decrypt(stored: String) = provider.decrypt(stored)
}