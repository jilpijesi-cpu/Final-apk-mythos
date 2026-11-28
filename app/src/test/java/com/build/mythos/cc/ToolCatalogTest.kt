package com.build.mythos.cc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pemetaan alat agen API: setiap alat jalur baru ada di katalog, registri, skema, dan prompt sistem (semua mode). */
class ToolCatalogTest {
    private val schema = JSONObject().put("type", "object")
        .put("properties", JSONObject().put("deskripsi", JSONObject().put("type", "string")))
        .put("required", org.json.JSONArray())

    /** Registri dengan SEMUA alat aktif: bawaan, tambahan jaringan/Android, jembatan aplikasi, GithubBuild, ExitPlanMode. */
    private fun fullRegistry(): ToolRegistry {
        val extra = listOf<Tool>(
            WebSearch { true to "" }, WebFetch { true to "" },
            BuatGambar { _, _, _, _ -> true to "" }, EditGambar { _, _, _, _ -> true to "" },
            CariLeakOsint { _, _, _ -> true to "" }, Export { _, _ -> "" }, Present { _, _ -> null },
        )
        val bridge = ToolCatalog.ALL.filter { it.bridged }.map {
            AppBridgeTool(it.name, "desc", schema, Effect.READ, 1_000L) { true to "" }
        }
        return ToolRegistry.default(withBuild = true, withPlan = true, extra = extra + bridge)
    }

    @Test fun katalogTidakAdaNamaGanda() {
        assertEquals(ToolCatalog.names.size, ToolCatalog.names.toSet().size)
    }

    @Test fun katalogSamaPersisDenganRegistriPenuh() {
        assertEquals(ToolCatalog.names.toSet(), fullRegistry().names.toSet())
    }

    @Test fun setiapAlatPunyaSkemaBerparameterKecualiTime() {
        for (s in ToolSchemas.specs(fullRegistry())) {
            val props = s.schema.optJSONObject("properties")
            assertTrue("${s.name} tidak punya skema", props != null)
            if (s.name != "Time") assertTrue("${s.name} skemanya kosong", props!!.length() > 0)
        }
    }

    @Test fun setiapAlatPunyaKegunaanDiKatalog() {
        for (e in ToolCatalog.ALL) assertTrue("${e.name} tanpa kegunaan", e.purpose.length > 10)
    }

    @Test fun daftarMemuatSemuaAlatBilaSemuanyaAktif() {
        val r = ToolCatalog.roster(fullRegistry())
        for (n in ToolCatalog.names) assertTrue("$n tidak ada di daftar", r.contains("  - $n: "))
        assertFalse(r.contains("Exists but is off"))
    }

    @Test fun alatYangMatiTetapDisebutDenganAlasan() {
        val r = ToolCatalog.roster(ToolRegistry.default(withBuild = false))
        assertTrue(r.contains("  - GithubBuild: off because no GitHub token"))
        assertTrue(r.contains("  - email_kirim: off because its connector is not switched on"))
        assertTrue(r.contains("  - ExitPlanMode: off because plan mode is not active"))
        assertTrue(r.contains("  - berangkas_baca: off because"))
        assertTrue(r.contains("  - Read: "))
    }

    @Test fun alatDiRegistriTanpaKatalogTetapTampil() {
        val ghost = object : Tool {
            override val name = "AlatBaru"
            override val doc = "AlatBaru {} — alat percobaan yang belum didaftarkan"
            override val risk = Risk.SAFE
            override val effect = Effect.READ
            override suspend fun run(args: JSONObject, env: ToolEnv) = ToolResult(true, "")
        }
        val r = ToolCatalog.roster(ToolRegistry(listOf(ghost)))
        assertTrue(r.contains("  - AlatBaru: "))
    }

    @Test fun promptSistemMemuatDaftarLengkapDiSemuaMode() {
        val reg = fullRegistry()
        val prompts = listOf(
            Protocol.systemPrompt(reg, canBuild = true),
            Protocol.systemPrompt(reg, canBuild = true, native = true),
            Protocol.systemPrompt(reg, canBuild = true, planMode = true),
        )
        for (p in prompts) {
            assertTrue(p.contains("# Your complete tool roster"))
            for (n in ToolCatalog.names) assertTrue("$n tidak ada di prompt", p.contains("  - $n: "))
        }
    }

    @Test fun deskripsiDeleteSesuaiKemampuannyaHanyaSatuBerkas() {
        val purpose = ToolCatalog.entry("Delete")!!.purpose
        assertTrue(purpose, purpose.contains("ONE file"))
        assertFalse(purpose, purpose.contains("or folder"))
    }
}
