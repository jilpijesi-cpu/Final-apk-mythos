package com.build.mythos

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Penyimpanan rahasia dan migrasi key lama (F-06). Android Keystore asli tidak ada di JVM, jadi provider diganti fake
 * AES-256-GCM berformat sama (enc1: + base64(iv + ciphertext)). Uji Keystore asli hanya bisa di perangkat Android.
 */
class SecretStorageTest {
    private class JceProvider(val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : SecretCryptoProvider {
        override fun encrypt(plain: String): Result<String> {
            if (plain.isEmpty() || plain.startsWith("enc1:")) return Result.success(plain)
            return try {
                val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
                Result.success("enc1:" + java.util.Base64.getEncoder().encodeToString(iv + ct))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        override fun decrypt(stored: String): SecretDecryptResult {
            if (stored.isEmpty()) return SecretDecryptResult.Success("")
            if (!stored.startsWith("enc1:")) return SecretDecryptResult.Success(stored)
            return try {
                val raw = java.util.Base64.getDecoder().decode(stored.removePrefix("enc1:"))
                if (raw.size <= 12) return SecretDecryptResult.Corrupt
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(0, 12)))
                SecretDecryptResult.Success(String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8))
            } catch (e: AEADBadTagException) {
                SecretDecryptResult.Corrupt
            } catch (e: Exception) {
                SecretDecryptResult.Corrupt
            }
        }
    }

    private class FailingProvider : SecretCryptoProvider {
        override fun encrypt(plain: String): Result<String> = Result.failure(IllegalStateException("keystore mati"))
        override fun decrypt(stored: String): SecretDecryptResult = SecretDecryptResult.KeystoreUnavailable
    }

    @Test fun enkripsiBolakBalikTanpaPlaintextDiPenyimpanan() {
        val p = JceProvider()
        val store = HashMap<String, String>()
        assertTrue(putEncryptedValue(store, "api", "sk-rahasia-123", p).isSuccess)
        val saved = store["api"]!!
        assertTrue(saved.startsWith("enc1:"))
        assertFalse(saved.contains("sk-rahasia-123"))
        assertEquals(SecretDecryptResult.Success("sk-rahasia-123"), p.decrypt(saved))
    }

    @Test fun providerGagalTidakPernahMenyimpanPlaintext() {
        val store = HashMap<String, String>()
        val r = putEncryptedValue(store, "api", "sk-rahasia-123", FailingProvider())
        assertTrue(r.isFailure)
        assertFalse(store.containsKey("api"))
        assertFalse(store.values.any { it.contains("sk-rahasia-123") })
    }

    @Test fun ciphertextDirusakDianggapCorruptBukanDiterimaDiamDiam() {
        val p = JceProvider()
        val saved = p.encrypt("sk-rahasia-123").getOrThrow()
        val raw = java.util.Base64.getDecoder().decode(saved.removePrefix("enc1:"))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        val tampered = "enc1:" + java.util.Base64.getEncoder().encodeToString(raw)
        assertEquals(SecretDecryptResult.Corrupt, p.decrypt(tampered))
        assertEquals(SecretDecryptResult.Corrupt, p.decrypt("enc1:AAAA"))
    }

    @Test fun kunciBerbedaTidakBisaMembuka() {
        val saved = JceProvider().encrypt("sk-rahasia-123").getOrThrow()
        assertEquals(SecretDecryptResult.Corrupt, JceProvider().decrypt(saved))
    }

    @Test fun ivTidakDipakaiUlang() {
        val p = JceProvider()
        assertTrue(p.encrypt("sama").getOrThrow() != p.encrypt("sama").getOrThrow())
    }

    @Test fun migrasiKeyLamaPlaintextMenjadiTerenkripsi() {
        val p = JceProvider()
        val r = migrateStoredKey("sk-lama-plaintext", p)
        assertTrue(r is KeyMigrationResult.Encrypted)
        val enc = (r as KeyMigrationResult.Encrypted).value
        assertTrue(enc.startsWith("enc1:"))
        assertFalse(enc.contains("sk-lama-plaintext"))
    }

    @Test fun migrasiKosongDanSudahTerenkripsiTidakDiubah() {
        val p = JceProvider()
        assertEquals(KeyMigrationResult.Unchanged, migrateStoredKey("", p))
        assertEquals(KeyMigrationResult.Unchanged, migrateStoredKey("enc1:abcdef", p))
    }

    @Test fun migrasiGagalMenghapusKeyBukanMenyimpanPlaintext() {
        assertEquals(KeyMigrationResult.Remove, migrateStoredKey("sk-lama-plaintext", FailingProvider()))
    }

    @Test fun migrasiMenolakProviderYangMengembalikanTeksTanpaAwalan() {
        val bad = object : SecretCryptoProvider {
            override fun encrypt(plain: String): Result<String> = Result.success(plain) // mengaku sukses tapi plaintext
            override fun decrypt(stored: String): SecretDecryptResult = SecretDecryptResult.Success(stored)
        }
        assertEquals(KeyMigrationResult.Remove, migrateStoredKey("sk-lama-plaintext", bad))
    }

    @Test fun putEncryptedValueMenolakProviderYangMengembalikanPlaintext() {
        val bad = object : SecretCryptoProvider {
            override fun encrypt(plain: String): Result<String> = Result.success(plain)
            override fun decrypt(stored: String): SecretDecryptResult = SecretDecryptResult.Success(stored)
        }
        val store = HashMap<String, String>()
        val r = putEncryptedValue(store, "api", "sk-rahasia-123", bad)
        assertTrue("putEncryptedValue wajib gagal jika hasil enkripsi tidak berawalan enc1:", r.isFailure)
        assertFalse(store.containsKey("api"))
        assertFalse(store.values.any { it.contains("sk-rahasia-123") })
    }

    @Test fun dekripsiNilaiKosongTidakMelempar() {
        assertEquals(SecretDecryptResult.Success(""), JceProvider().decrypt(""))
    }
}
