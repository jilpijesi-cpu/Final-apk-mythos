package com.build.mythos.cc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresi: model (mis. Gemini lewat protokol teks) menulis panggilan alat bergaya lain dan hasil alat karangan sendiri;
 * aplikasi menganggapnya jawaban akhir dan agen "menyelesaikan" tugas yang tak pernah dijalankan.
 */
class ReplyGuardTest {
    private val call1 = """<tool_use>{"name":"Read","input":{"file_path":"a.kt"}}</tool_use>"""
    private val call2 = """<tool_use>{"name":"Read","input":{"file_path":"b.kt"}}</tool_use>"""
    private val fake = "<tool_result id=\"toolu_1\" name=\"Read\" is_error=\"false\">isi karangan</tool_result>"

    private fun parse(s: String): Parsed {
        var n = 0
        return Protocol.parse(s) { "toolu_${++n}" }
    }

    @Test fun hasilKaranganSetelahPanggilanDipotongDanPanggilanSesudahnyaTidakDijalankan() {
        val p = parse("Saya baca dulu.\n$call1\n$fake\n$call2")
        assertTrue(p is Parsed.Reply)
        p as Parsed.Reply
        assertEquals(1, p.calls.size)
        assertEquals("a.kt", p.calls[0].input.getString("file_path"))
        assertFalse(p.text.contains("karangan"))
        assertFalse(p.text.contains("tool_result"))
    }

    @Test fun karanganTanpaPanggilanSahDitolak() {
        val p = parse("Saya akan membaca file.\n$fake")
        assertTrue(p is Parsed.Invalid)
        assertEquals(ReplyGuard.FABRICATED_REASON, (p as Parsed.Invalid).reason)
    }

    @Test fun karanganSebelumPanggilanMembuatPanggilanTidakDijalankan() {
        val p = parse("$fake\nlalu\n$call1")
        assertTrue(p is Parsed.Invalid)
    }

    @Test fun tagAlatBergayaLainDitolak() {
        // Persis pola yang ditulis model pada kejadian nyata.
        val p = parse("""BOS, saya cari dulu.<glob>{"pattern": "**/NetworkModule.kt", "path": "uploads/x"}</glob>""")
        assertTrue(p is Parsed.Invalid)
        assertEquals(ReplyGuard.FOREIGN_REASON, (p as Parsed.Invalid).reason)
    }

    @Test fun pengingatSistemTulisanModelDitolak() {
        val p = parse("Siap.\n<system-reminder>\nTodos telah diubah.\n</system-reminder>")
        assertTrue(p is Parsed.Invalid)
    }

    @Test fun penyebutanBiasaDiNarasiTidakDitolak() {
        val p = parse("Hasil alat dikirim oleh aplikasi; blok tool_result hanya ada di riwayat dan tidak ditulis model.")
        assertTrue(p is Parsed.Reply)
        assertTrue((p as Parsed.Reply).calls.isEmpty())
    }

    @Test fun isiFileYangMemuatPenandaDiDalamBlokPanggilanTidakMemotong() {
        val body = JSONObject().put("name", "Write").put(
            "input",
            JSONObject().put("file_path", "t.kt").put("content", "val s = \"<tool_result id=\\\"x\\\" name=\\\"Read\\\">\"")
        ).toString()
        val p = parse("<tool_use>$body</tool_use>")
        assertTrue(p.toString(), p is Parsed.Reply)
        p as Parsed.Reply
        assertEquals(1, p.calls.size)
        assertTrue(p.calls[0].input.getString("content").contains("<tool_result id="))
    }

    @Test fun balasanNormalDenganPanggilanTidakBerubah() {
        val p = parse("Membaca file.\n$call1")
        assertTrue(p is Parsed.Reply)
        p as Parsed.Reply
        assertEquals(1, p.calls.size)
        assertEquals("Membaca file.", p.text)
    }

    @Test fun cleanDanRejectReasonUntukJalurNative() {
        assertEquals("Halo", ReplyGuard.clean("Halo\n$fake"))
        assertEquals("Halo", ReplyGuard.clean("Halo"))
        assertEquals(ReplyGuard.FABRICATED_REASON, ReplyGuard.rejectReason("Halo $fake", 0))
        assertNull(ReplyGuard.rejectReason("Halo $fake", 1))   // ada panggilan sah: teks dibersihkan, bukan ditolak
        assertNull(ReplyGuard.rejectReason("Halo biasa", 0))
        assertTrue(ReplyGuard.isGuardReason(ReplyGuard.FOREIGN_REASON))
        assertFalse(ReplyGuard.isGuardReason("balasan kosong."))
    }
}
