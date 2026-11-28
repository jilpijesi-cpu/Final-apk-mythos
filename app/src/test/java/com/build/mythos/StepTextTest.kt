package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepTextTest {
    @Test fun isGenericStepTextMembedakanKalimatUmumDanSpesifik() {
        assertTrue(isGenericStepText("Menjalankan perintah"))
        assertTrue(isGenericStepText("Sedang bekerja..."))
        assertFalse(isGenericStepText("Membaca logika failover di ModelCall.kt"))
    }

    @Test fun describeCommandMenerjemahkanPerintahUmumKeBahasaIndonesia() {
        val descGradle = describeCommand("./gradlew assembleDebug --no-daemon")
        assertNotNull(descGradle)
        assertTrue(descGradle!!.contains("APK debug"))

        val descGrep = describeCommand("grep -rn 'evaluateUpgrade' app/src/main")
        assertNotNull(descGrep)
        assertTrue(descGrep!!.contains("evaluateUpgrade"))
    }

    @Test fun oneLineStepLabelMengambilKalimatPertamaTanpaMarkdown() {
        assertEquals("Merapikan spasi nyasar dan jarak antar baris", oneLineStepLabel("**Merapikan spasi nyasar** dan jarak antar baris. Lalu cek lagi."))
        assertEquals("Memeriksa sampul paket bukti", oneLineStepLabel("`Memeriksa` sampul paket bukti"))
    }

    @Test fun oneLineStepLabelMemotongDenganTitikTiga() {
        val s = oneLineStepLabel("Membangun uji beban dengan setiap jenis gambar yang mungkin dikirim pengguna ke aplikasi", 40)!!
        assertEquals(40, s.length)
        assertTrue(s.endsWith("\u2026"))
    }

    @Test fun oneLineStepLabelMenolakKosongPendekDanUmum() {
        assertNull(oneLineStepLabel(null))
        assertNull(oneLineStepLabel("   "))
        assertNull(oneLineStepLabel("Oke"))
        assertNull(oneLineStepLabel("Menjalankan perintah"))
        assertNull(oneLineStepLabel("Sedang bekerja..."))
    }

    @Test fun deskripsiDiambilDariRingkasanJson() {
        assertEquals("Membaca daftar file di folder app", stepDescriptionFromSummary("Bash {\"command\": \"ls app\", \"deskripsi\": \"Membaca daftar file di folder app\"}"))
        assertEquals("Rebuild both sample documents", stepDescriptionFromSummary("Bash {\"description\": \"Rebuild both sample documents\"}"))
        assertNull(stepDescriptionFromSummary("Read {\"file_path\": \"a.kt\"}"))
    }

    @Test fun pickStepTitleMemilihDeskripsiLaluPengantarPendekLaluFallback() {
        val sum = "Bash {\"command\": \"ls\", \"deskripsi\": \"Merapikan spasi nyasar dan jarak antar baris\"}"
        assertEquals("Merapikan spasi nyasar dan jarak antar baris", pickStepTitle(sum, "Sekarang saya cek tampilannya.", "fallback"))
        assertEquals("Sekarang saya cek tampilannya", pickStepTitle("Read {\"file_path\": \"a.kt\"}", "Sekarang saya cek tampilannya.", "fallback"))
        assertEquals("fallback", pickStepTitle("Read {\"file_path\": \"a.kt\"}", null, "fallback"))
        assertEquals("fallback", pickStepTitle("Read {\"file_path\": \"a.kt\"}", "Menjalankan perintah", "fallback"))
    }

    @Test fun paragrafPanjangBukanLabelLangkah() {
        val panjang = "Saya sudah membaca seluruh dokumen dan menemukan beberapa hal yang perlu diperbaiki sebelum kita lanjut ke tahap berikutnya."
        assertEquals("fallback", pickStepTitle("Read {\"file_path\": \"a.kt\"}", panjang, "fallback"))
    }
}
