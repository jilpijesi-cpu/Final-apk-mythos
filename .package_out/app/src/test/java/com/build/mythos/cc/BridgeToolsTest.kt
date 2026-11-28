package com.build.mythos.cc

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Jembatan alat aplikasi untuk agen cc: skema ikut deklarasi lama, hasil dan gerbang persetujuan tidak berubah. */
class BridgeToolsTest {
    @get:Rule val tmp = TemporaryFolder()
    private var envNumber = 0

    private val schema = JSONObject().put("type", "object")
        .put("properties", JSONObject()
            .put("deskripsi", JSONObject().put("type", "string").put("description", "Langkah singkat"))
            .put("jumlah", JSONObject().put("type", "string").put("description", "Jumlah email")))
        .put("required", JSONArray().put("deskripsi"))

    private fun tool(effect: Effect = Effect.READ, run: (JSONObject) -> Pair<Boolean, String>) =
        AppBridgeTool("email_daftar", "Daftar email. Tanyakan lewat ask_user.", schema, effect, 1_000L, run)

    private fun env(): ToolEnv {
        val folder = tmp.newFolder("p-${envNumber++}")
        return ToolEnv(ProjectSandbox(folder), SessionState(), { "" }, null)
    }

    @Test fun skemaDanDokumenMengikutiDeklarasiLama() {
        val t = tool { true to "" }
        assertEquals(schema.toString(), t.schema!!.toString())
        assertTrue(t.doc.startsWith("email_daftar {"))
        assertTrue(t.doc.contains("\"jumlah\": \"Jumlah email (opsional)\""))
        assertFalse(t.doc.contains("\"deskripsi\": \"Langkah singkat (opsional)\""))
        assertTrue("nama alat lama diganti nama alat cc", t.doc.contains("AskUserQuestion") && !t.doc.contains("ask_user"))
    }

    @Test fun registriMemakaiSkemaMilikAlat() {
        val reg = ToolRegistry(listOf(tool { true to "" }))
        val spec = ToolSchemas.specs(reg).single()
        assertEquals("email_daftar", spec.name)
        assertEquals(2, spec.schema.getJSONObject("properties").length())
    }

    @Test fun dialogPersetujuanTetapDiAlatLamaBukanDiAgen() {
        // SAFE: agen tidak menambah dialog kedua; alat kirim lama punya gerbangnya sendiri.
        assertEquals(Risk.SAFE, tool(Effect.WRITE) { true to "" }.risk)
        assertEquals(Effect.WRITE, tool(Effect.WRITE) { true to "" }.effect)
    }

    @Test fun hasilDanKegagalanDiteruskan() {
        val ok = runBlocking { tool { a -> true to "dapat ${a.optString("jumlah")} email" }.run(JSONObject().put("jumlah", "5"), env()) }
        assertTrue(ok.ok); assertEquals("dapat 5 email", ok.text)
        val gagal = runBlocking { tool { false to "Konektor belum menyala" }.run(JSONObject(), env()) }
        assertFalse(gagal.ok); assertEquals("Konektor belum menyala", gagal.text)
    }

    @Test fun ringkasanTidakMemuatIsiPesan() {
        val s = tool { true to "" }.describe(JSONObject().put("deskripsi", "Cek inbox").put("isi", "RAHASIA"))
        assertEquals("email_daftar Cek inbox", s)
    }

    @Test fun jurnalTugasDiteruskanKeAlatSelamaDijalankanLaluDilepas() {
        val proj = tmp.newFolder("pj")
        val journal = TaskJournal(proj, tmp.newFolder("jr"))
        val env = ToolEnv(ProjectSandbox(proj), SessionState(), { "" }, null, journal)
        var dilihat: TaskJournal? = null
        lateinit var t: AppBridgeTool
        t = tool(Effect.WRITE) { dilihat = t.journal; true to "ok" }
        assertEquals(null, t.journal)
        runBlocking { t.run(JSONObject(), env) }
        assertTrue("alat harus melihat jurnal tugas", dilihat === journal)
        assertEquals("jurnal dilepas setelah selesai", null, t.journal)
    }

    @Test fun berkasBaruYangDicatatDiJurnalHilangSaatTugasDibatalkan() {
        val proj = tmp.newFolder("pj2")
        val journal = TaskJournal(proj, tmp.newFolder("jr2"))
        val f = java.io.File(proj, "lampiran_email/laporan.pdf")
        f.parentFile.mkdirs()
        journal.record(f, "lampiran_email/laporan.pdf") // dicatat SEBELUM ditulis, seperti toolEmailLampiran
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(f.exists())
        val r = journal.undo()
        assertTrue(r.ok)
        assertFalse("berkas unduhan ikut dibatalkan", f.exists())
    }
}
