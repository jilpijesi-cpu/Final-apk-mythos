package com.build.mythos

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Enkripsi berkas riwayat chat: gagal tertutup, kompatibel dengan berkas JSON lama, tanpa plaintext di disk. */
class ChatCryptTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Provider palsu berformat sama dengan Secret (enc1: + base64), cukup untuk menguji logika pembungkus. */
    private class FakeProvider : SecretCryptoProvider {
        override fun encrypt(plain: String): Result<String> {
            if (plain.isEmpty() || plain.startsWith("enc1:")) return Result.success(plain)
            return Result.success("enc1:" + java.util.Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8)))
        }

        override fun decrypt(stored: String): SecretDecryptResult {
            if (stored.isEmpty()) return SecretDecryptResult.Success("")
            if (!stored.startsWith("enc1:")) return SecretDecryptResult.Success(stored)
            return try {
                SecretDecryptResult.Success(String(java.util.Base64.getDecoder().decode(stored.removePrefix("enc1:")), Charsets.UTF_8))
            } catch (e: IllegalArgumentException) {
                SecretDecryptResult.Corrupt
            }
        }
    }

    private class BrokenProvider(private val reason: SecretDecryptResult) : SecretCryptoProvider {
        override fun encrypt(plain: String): Result<String> = Result.failure(IllegalStateException("keystore mati"))
        override fun decrypt(stored: String): SecretDecryptResult = reason
    }

    private val json = """{"id":"c1","title":"Rahasia","messages":[{"role":0,"text":"halo"}]}"""

    @Test fun tulisLaluBacaBolakBalikDanDiskTidakMemuatPlaintext() {
        val f = File(tmp.root, "c1.json")
        writeChatText(f, json, FakeProvider())
        val onDisk = f.readText()
        assertTrue(onDisk.startsWith("enc1:"))
        assertFalse(onDisk.contains("Rahasia"))
        assertFalse(onDisk.contains("halo"))
        assertEquals(json, readChatText(f, FakeProvider()))
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun berkasJsonLamaTetapTerbaca() {
        val f = File(tmp.root, "lama.json")
        f.writeText(json)
        assertEquals(json, readChatText(f, FakeProvider()))
    }

    @Test fun enkripsiGagalMelemparDanTidakMenulisPlaintextMaupunMerusakBerkasLama() {
        val f = File(tmp.root, "c1.json")
        f.writeText(json) // berkas lama (plaintext) harus utuh bila penulisan baru gagal
        var gagal = false
        try { writeChatText(f, """{"baru":true}""", BrokenProvider(SecretDecryptResult.KeystoreUnavailable)) } catch (e: IOException) { gagal = true }
        assertTrue(gagal)
        assertEquals(json, f.readText())
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun enkripsiGagalPadaBerkasBaruTidakMembuatBerkas() {
        val f = File(tmp.root, "baru.json")
        var gagal = false
        try { writeChatText(f, json, BrokenProvider(SecretDecryptResult.Missing)) } catch (e: IOException) { gagal = true }
        assertTrue(gagal)
        assertFalse(f.exists())
    }

    @Test fun providerYangMengembalikanPlaintextDitolak() {
        val lemah = object : SecretCryptoProvider {
            override fun encrypt(plain: String): Result<String> = Result.success(plain)
            override fun decrypt(stored: String): SecretDecryptResult = SecretDecryptResult.Success(stored)
        }
        val f = File(tmp.root, "c1.json")
        var gagal = false
        try { writeChatText(f, json, lemah) } catch (e: IOException) { gagal = true }
        assertTrue(gagal)
        assertFalse(f.exists())
    }

    @Test fun bacaGagalMelemparIOExceptionUntukSemuaKegagalanDekripsi() {
        val f = File(tmp.root, "c1.json")
        f.writeText("enc1:AAAA")
        for (r in listOf(SecretDecryptResult.Missing, SecretDecryptResult.Corrupt, SecretDecryptResult.KeystoreUnavailable)) {
            var gagal = false
            try { readChatText(f, BrokenProvider(r)) } catch (e: IOException) { gagal = true }
            assertTrue("harus melempar untuk $r", gagal)
        }
    }

    @Test fun pesanGalatTidakMembocorkanIsiBerkas() {
        val f = File(tmp.root, "c1.json")
        f.writeText("enc1:RAHASIA-ISI")
        try {
            readChatText(f, BrokenProvider(SecretDecryptResult.Corrupt))
            fail("readChatText harus melempar IOException")
        } catch (e: IOException) {
            assertFalse(e.message!!.contains("RAHASIA-ISI"))
        }
    }
}
