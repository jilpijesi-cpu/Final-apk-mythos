package com.build.mythos

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanaTertahanTest {
    private val hari = LocalDate.of(2026, 11, 21)

    private fun kasus(
        platform: String = "DompetKu", jenis: String = "kyc", nominal: String = "Rp1.500.000",
        tanggal: String = "2026-11-10", ref: String = "", alasan: String = "", ada: List<KasusDana> = emptyList()
    ) = DanaTertahan.buat(platform, jenis, nominal, tanggal, ref, alasan, ada, hari)

    // ------------------------------------------------------------ nominal dan tanggal

    @Test fun parseRupiahMengertiFormatIndonesia() {
        assertEquals(1_500_000L, DanaTertahan.parseRupiah("Rp 1.500.000"))
        assertEquals(1_500_000L, DanaTertahan.parseRupiah("1500000"))
        assertEquals(1_500_000L, DanaTertahan.parseRupiah("1,5jt"))
        assertEquals(250_000L, DanaTertahan.parseRupiah("250rb"))
        assertEquals(1_500_001L, DanaTertahan.parseRupiah("1.500.000,50"))
    }

    @Test fun parseRupiahMenolakMasukanBurukAtauNol() {
        for (s in listOf("", "abc", "0", "-5", "1.2.3,4,5", "Rp", "jt")) assertNull("'$s' harus ditolak", DanaTertahan.parseRupiah(s))
    }

    @Test fun formatRupiahMemakaiTitikRibuan() {
        assertEquals("Rp1.500.000", DanaTertahan.formatRupiah(1_500_000))
        assertEquals("Rp500", DanaTertahan.formatRupiah(500))
    }

    @Test fun parseTanggalMenerimaDuaFormatDanMenolakMasaDepan() {
        assertEquals("2026-11-10", DanaTertahan.parseTanggal("2026-11-10", hari))
        assertEquals("2026-11-05", DanaTertahan.parseTanggal("05/11/2026", hari))
        assertNull(DanaTertahan.parseTanggal("2026-12-01", hari))
        assertNull(DanaTertahan.parseTanggal("31/02/2026", hari))
        assertNull(DanaTertahan.parseTanggal("kemarin", hari))
    }

    // ------------------------------------------------------------ penyamaran data sensitif

    @Test fun otpPinDanSandiDisamarkan() {
        val h = DanaTertahan.samarkan("CS minta OTP 482913 dan PIN: 123456 lalu kata sandi saya abc123")
        assertTrue(h.adaSensitif)
        assertFalse(h.teks.contains("482913"))
        assertFalse(h.teks.contains("123456"))
        assertFalse(h.teks.contains("abc123"))
    }

    @Test fun nomorKartuValidDisamarkanTetapiAngkaBiasaTidak() {
        val kartu = DanaTertahan.samarkan("kartu 4111 1111 1111 1111 ditolak")
        assertTrue(kartu.adaSensitif)
        assertFalse(kartu.teks.contains("4111"))
        // 16 digit yang gagal Luhn (mis. nomor referensi) tidak boleh dirusak
        val ref = DanaTertahan.samarkan("referensi 1234567890123456")
        assertFalse(ref.adaSensitif)
        assertTrue(ref.teks.contains("1234567890123456"))
    }

    @Test fun teksBiasaTidakDisamarkan() {
        val h = DanaTertahan.samarkan("Saldo 1.500.000 tertahan sejak 10 November, tiket TKT-2231")
        assertFalse(h.adaSensitif)
        assertTrue(h.teks.contains("TKT-2231"))
    }

    @Test fun luhn() {
        assertTrue(DanaTertahan.luhnOk("4111111111111111"))
        assertFalse(DanaTertahan.luhnOk("4111111111111112"))
        assertFalse(DanaTertahan.luhnOk("123"))
    }

    // ------------------------------------------------------------ deteksi penipuan

    @Test fun tandaPenipuanDikenali() {
        assertTrue(DanaTertahan.deteksiPenipuan("Katanya harus bayar dulu biaya pencairan Rp500rb").isNotEmpty())
        assertTrue(DanaTertahan.deteksiPenipuan("ada jasa pencairan dana yang menawarkan diri").isNotEmpty())
        assertTrue(DanaTertahan.deteksiPenipuan("dia minta OTP saya").isNotEmpty())
        assertTrue(DanaTertahan.deteksiPenipuan("hacker bisa kembalikan saldo").isNotEmpty())
    }

    @Test fun kalimatWajarTidakDianggapPenipuan() {
        assertTrue(DanaTertahan.deteksiPenipuan("Saya sudah kirim KTP lewat aplikasi, tiket TKT-1 dibuat, status diproses").isEmpty())
    }

    // ------------------------------------------------------------ membuat dan mencatat

    @Test fun buatKasusBaruDenganIdBerurutan() {
        val a = kasus()
        assertEquals("D001", a.id)
        assertEquals(1_500_000L, a.nominal)
        assertEquals(StatusKasus.BARU, a.status)
        val b = kasus(platform = "BankX", ada = listOf(a))
        assertEquals("D002", b.id)
    }

    @Test fun buatMenolakMasukanTidakValid() {
        fun gagal(f: () -> Unit) {
            var ok = false
            try { f() } catch (e: IllegalArgumentException) { ok = true }
            assertTrue(ok)
        }
        gagal { kasus(platform = " ") }
        gagal { kasus(jenis = "ngawur") }
        gagal { kasus(nominal = "banyak") }
        gagal { kasus(tanggal = "2027-01-01") }
    }

    @Test fun batasJumlahKasusDitegakkan() {
        var l = emptyList<KasusDana>()
        repeat(DanaTertahan.MAX_KASUS) { l = l + kasus(ada = l) }
        var ditolak = false
        try { kasus(ada = l) } catch (e: IllegalArgumentException) { ditolak = true }
        assertTrue(ditolak)
    }

    @Test fun alasanDanReferensiDisamarkanSaatDisimpan() {
        val k = kasus(alasan = "CS bilang kirim OTP 998877 dulu", ref = "TKT-1")
        assertFalse(k.alasan.contains("998877"))
        assertEquals("TKT-1", k.referensi)
    }

    @Test fun catatMenambahCatatanDanMengubahStatus() {
        val k = kasus()
        val (b, w) = DanaTertahan.catat(k, "komunikasi", "CS balas lewat tiket TKT-9, minta foto KTP", "cs", hari)
        assertEquals(1, b.catatan.size)
        assertEquals(StatusKasus.DIAJUKAN_CS, b.status)
        assertTrue(w.isEmpty())
    }

    @Test fun catatMemberiPeringatanBilaAdaDataSensitifDanPenipuan() {
        val (b, w) = DanaTertahan.catat(kasus(), "komunikasi", "Orang bilang bayar dulu biaya pencairan, dan minta OTP 123456", "", hari)
        assertFalse(b.catatan.first().isi.contains("123456"))
        assertTrue(w.any { it.contains("PIN") || it.contains("OTP") })
        assertTrue(w.any { it.startsWith("WASPADA PENIPUAN") })
    }

    @Test fun catatTanpaIsiDanTanpaStatusDitolak() {
        var ditolak = false
        try { DanaTertahan.catat(kasus(), "", "", "", hari) } catch (e: IllegalArgumentException) { ditolak = true }
        assertTrue(ditolak)
    }

    @Test fun catatMenolakJenisCatatanAtauStatusTakDikenal() {
        for (f in listOf({ DanaTertahan.catat(kasus(), "aneh", "isi", "", hari) }, { DanaTertahan.catat(kasus(), "bukti", "isi", "aneh", hari) })) {
            var ditolak = false
            try { f() } catch (e: IllegalArgumentException) { ditolak = true }
            assertTrue(ditolak)
        }
    }

    @Test fun batasCatatanDitegakkan() {
        var k = kasus()
        repeat(DanaTertahan.MAX_CATATAN) { k = DanaTertahan.catat(k, "langkah", "catatan $it", "", hari).first }
        var ditolak = false
        try { DanaTertahan.catat(k, "langkah", "satu lagi", "", hari) } catch (e: IllegalArgumentException) { ditolak = true }
        assertTrue(ditolak)
    }

    // ------------------------------------------------------------ tenggat

    @Test fun tenggatTujuhHariDariStatusTerakhirDanTerlambat() {
        val k = kasus()
        assertEquals(hari.plusDays(7), DanaTertahan.tenggat(k))
        assertFalse(DanaTertahan.terlambat(k, hari))
        assertTrue(DanaTertahan.terlambat(k, hari.plusDays(8)))
    }

    @Test fun kasusSelesaiTidakPunyaTenggat() {
        val (k, _) = DanaTertahan.catat(kasus(), "", "", "selesai", hari)
        assertNull(DanaTertahan.tenggat(k))
        assertFalse(DanaTertahan.terlambat(k, hari.plusDays(100)))
    }

    @Test fun langkahBerikutSesuaiStatus() {
        assertTrue(DanaTertahan.langkahBerikut(kasus()).contains("penjelasan"))
        val (k, _) = DanaTertahan.catat(kasus(), "", "", "cs", hari)
        assertTrue(DanaTertahan.langkahBerikut(k).contains("komplain"))
    }

    @Test fun jenjangRegulatorHanyaMemuatNamaLembagaTanpaNomorAtauTautan() {
        val teks = DanaTertahan.jenjangRegulator().joinToString("\n")
        assertTrue(teks.contains("OJK") && teks.contains("Bank Indonesia"))
        assertFalse("tidak boleh menanam URL yang bisa basi", teks.contains("http") || teks.contains("www."))
        assertFalse("tidak boleh menanam nomor telepon yang bisa basi", Regex("""\d{3,}""").containsMatchIn(teks))
    }

    // ------------------------------------------------------------ draf surat

    @Test fun drafMemakaiFaktaKasusDanMenandaiYangKosong() {
        val k = kasus(ref = "TKT-9", alasan = "verifikasi identitas")
        val d = DanaTertahan.draf(k, TahapDraf.PENJELASAN, "", "", hari)
        assertTrue(d.contains("DompetKu"))
        assertTrue(d.contains("Rp1.500.000"))
        assertTrue(d.contains("TKT-9"))
        assertTrue(d.contains("[ISI: nama lengkap sesuai akun]"))
        assertTrue(d.contains("[ISI: email atau nomor HP terdaftar]"))
        assertTrue(d.contains(hari.toString()))
        assertFalse("tidak boleh ada placeholder template yang bocor", d.contains("\$hari"))
    }

    @Test fun drafTanpaReferensiDanAlasanTidakMengarang() {
        val d = DanaTertahan.draf(kasus(), TahapDraf.KOMPLAIN, "Budi", "budi@mail.id", hari)
        assertTrue(d.contains("[ISI: nomor tiket atau referensi bila ada]"))
        assertTrue(d.contains("belum menerima alasan yang jelas"))
        assertTrue(d.contains("Belum ada balasan tertulis"))
        assertTrue(d.contains("Budi") && d.contains("budi@mail.id"))
    }

    @Test fun drafKomplainMemuatRiwayatKomunikasiDanBukti() {
        var k = kasus()
        k = DanaTertahan.catat(k, "komunikasi", "CS minta foto KTP", "", hari).first
        k = DanaTertahan.catat(k, "bukti", "tangkapan layar saldo tertahan", "", hari).first
        val d = DanaTertahan.draf(k, TahapDraf.KOMPLAIN, "Budi", "b@x.id", hari)
        assertTrue(d.contains("CS minta foto KTP"))
        assertTrue(d.contains("tangkapan layar saldo tertahan"))
    }

    @Test fun drafRegulatorMemintaLembagaDiverifikasi() {
        val d = DanaTertahan.draf(kasus(), TahapDraf.REGULATOR, "Budi", "b@x.id", hari)
        assertTrue(d.contains("[ISI: nama lembaga pengawas"))
    }

    // ------------------------------------------------------------ serialisasi

    @Test fun serialisasiBolakBalik() {
        var k = kasus(ref = "TKT-1", alasan = "KYC")
        k = DanaTertahan.catat(k, "komunikasi", "CS balas", "tiket", hari).first
        val kembali = DanaTertahan.parse(DanaTertahan.toJson(listOf(k)))
        assertEquals(1, kembali.size)
        assertEquals(k, kembali.first())
    }

    @Test fun parseMengabaikanDataRusakTanpaMelempar() {
        assertTrue(DanaTertahan.parse("bukan json").isEmpty())
        assertTrue(DanaTertahan.parse("").isEmpty())
        assertTrue(DanaTertahan.parse("""[{"id":"X1","jenis":"kyc","status":"baru"}]""").isEmpty())
        assertTrue(DanaTertahan.parse("""[{"id":"D001","jenis":"aneh","status":"baru","dibuat":"2026-11-21"}]""").isEmpty())
    }

    // ------------------------------------------------------------ pendaftaran alat

    @Test fun semuaAlatDanaTerdaftarBawaanDanTerdokumentasi() {
        assertEquals(4, DANA_TOOL_NAMES.size)
        for (n in DANA_TOOL_NAMES) {
            assertTrue("$n harus ada di ToolDocs.NAMES", n in ToolDocs.NAMES)
            assertTrue("$n harus ada di ToolDocs.SIGNATURES", ToolDocs.SIGNATURES.any { it.startsWith("$n(") })
        }
    }

    @Test fun deklarasiAlatLengkapDanParameterWajibBenar() {
        val decls = danaToolDecls("deskripsi")
        assertEquals(DANA_TOOL_NAMES, decls.map { it.getString("name") })
        val buat = decls.first { it.getString("name") == "kasus_dana_buat" }.getJSONObject("parameters").getJSONArray("required")
        val wajib = (0 until buat.length()).map { buat.getString(it) }.toSet()
        assertEquals(setOf("deskripsi", "platform", "jenis", "nominal", "tanggal_tahan"), wajib)
    }

    @Test fun aturanAgenMemuatBatasKeras() {
        val r = ToolDocs.REFERENCE
        assertTrue(r.contains("DANA TERTAHAN"))
        for (kata in listOf("PIN", "OTP", "TIDAK bisa membuka", "email_kirim", "cari_web", "setara")) {
            assertTrue("aturan harus menyebut $kata", DANA_RULE.contains(kata))
        }
        // Tidak boleh ada aturan khusus yang menyebut satu alat bawaan lain: semua alat diperlakukan sama.
        for (n in ToolDocs.NAMES.filter { it !in DANA_TOOL_NAMES && it != "cari_web" && it != "baca_web" }) {
            assertFalse("DANA_RULE tidak boleh menyebut alat $n secara khusus", DANA_RULE.contains(n))
        }
    }
}
