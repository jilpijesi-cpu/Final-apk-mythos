package com.build.mythos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMemoryTest {
    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    @Test
    fun kataUmumDibuangDanAkhiranDirapikan() {
        val t = ChatMemory.tokens("Tolong jelaskan perbedaan Python dan Kotlin untuk pemula")
        assertTrue("python" in t)
        assertTrue("kotlin" in t)
        assertFalse("tolong" in t)
        assertFalse("dan" in t)
        assertEquals(ChatMemory.tokens("pakai bahasanya"), ChatMemory.tokens("pakai bahasa"))
    }

    @Test
    fun rahasiaDanSapaanTidakDisimpan() {
        assertFalse(ChatMemory.worthRemembering("ini api key saya sk-abc", "oke saya catat ya"))
        assertFalse(ChatMemory.worthRemembering("tokennya ${"a".repeat(40)}", "baik sudah diterima ya"))
        assertFalse(ChatMemory.worthRemembering("halo", "halo juga, ada yang bisa dibantu?"))
        assertFalse(ChatMemory.worthRemembering("apa itu coroutine", "Error: gagal menghubungi server"))
        assertTrue(ChatMemory.worthRemembering("apa itu coroutine di Kotlin", "Coroutine adalah cara menulis kode asinkron."))
    }

    @Test
    fun pertanyaanSamaMenggantikanJawabanLamaDanMempertahankanHits() {
        var l = ChatMemory.add(emptyList(), "Apa itu coroutine di Kotlin?", "Jawaban lama tentang coroutine.", now)
        l = ChatMemory.markUsed(l, l)
        l = ChatMemory.add(l, "apa itu coroutine di kotlin", "Jawaban baru tentang coroutine.", now + 1000)
        assertEquals(1, l.size)
        assertEquals("Jawaban baru tentang coroutine.", l[0].ai)
        assertEquals(1, l[0].hits)
    }

    @Test
    fun recallMemilihYangRelevanSajaDanMengurutkan() {
        var l = emptyList<MemoryEntry>()
        l = ChatMemory.add(l, "Bagaimana cara membuat build APK di GitHub Actions", "Pakai workflow build-apk.yml lalu jalankan.", now)
        l = ChatMemory.add(l, "Resep nasi goreng sederhana", "Tumis bawang, masukkan nasi, beri kecap.", now)
        l = ChatMemory.add(l, "Apa itu coroutine di Kotlin", "Coroutine adalah kode asinkron ringan.", now)
        val hit = ChatMemory.recall(l, "build APK lewat GitHub gagal terus", now)
        assertEquals(1, hit.size)
        assertTrue(hit[0].user.contains("APK"))
        assertTrue(ChatMemory.recall(l, "cuaca hari ini cerah", now).isEmpty())
    }

    @Test
    fun seringDipakaiDanBaruSedikitDiuntungkan() {
        val a = MemoryEntry("cara install termux", "Unduh dari F-Droid lalu buka.", now - 200 * day, 0)
        val b = MemoryEntry("cara install termux api", "Pasang paket termux-api juga.", now, 4)
        val out = ChatMemory.recall(listOf(a, b), "cara install termux", now)
        assertEquals("cara install termux api", out[0].user)
    }

    @Test
    fun melebihiBatasMembuangYangLamaDanJarangDipakai() {
        val many = (0 until ChatMemory.MAX_ENTRIES + 20).map { MemoryEntry("topik$it unik", "jawaban $it cukup panjang", now - (it * day), if (it == 399) 9 else 0) }
        val t = ChatMemory.trim(many, now)
        assertEquals(ChatMemory.MAX_ENTRIES, t.size)
        assertTrue(t.any { it.user == "topik399 unik" }) // lama tapi sering dipakai: dipertahankan
        assertTrue(t.zipWithNext().all { (x, y) -> x.ts <= y.ts })
    }

    @Test
    fun formatMematuhiBatasKarakterDanKosongBilaTidakAda() {
        assertEquals("", ChatMemory.format(emptyList()))
        val es = (1..3).map { MemoryEntry("pertanyaan $it ".repeat(20), "jawaban $it ".repeat(60), now) }
        val block = ChatMemory.format(es)
        assertTrue(block.length <= ChatMemory.RECALL_BUDGET)
        assertTrue(block.startsWith("[Ingatan dari obrolan lama]"))
        assertEquals("", ChatMemory.format(es, budget = 10))
    }

    @Test
    fun jsonBolakBalikDanTeksRusakAman() {
        val l = listOf(MemoryEntry("tanya", "jawab", 5L, 2))
        val back = ChatMemory.fromJson(ChatMemory.toJson(l))
        assertEquals(1, back.size)
        assertEquals(2, back[0].hits)
        assertTrue(ChatMemory.fromJson("{rusak").isEmpty())
    }

    @Test
    fun pertukaranTerakhirAbaikanSistemDanPanggilanAlat() {
        val msgs = listOf(
            Hist(0, "pertanyaan lama"), Hist(1, "jawaban lama"),
            Hist(0, "buatkan fitur login"),
            Hist(1, "", calls = listOf(Call("1", "write_file", JSONObject()))),
            Hist(2, "hasil alat"),
            Hist(0, SYS_PREFIX + "lanjutkan"),
            Hist(1, "Fitur login sudah dibuat dan lolos tes.")
        )
        val ex = ChatMemory.lastExchange(msgs)
        assertEquals("buatkan fitur login", ex?.first)
        assertEquals("Fitur login sudah dibuat dan lolos tes.", ex?.second)
        assertEquals(null, ChatMemory.lastExchange(listOf(Hist(0, "belum dijawab"))))
    }

    @Test
    fun impordariFileChatLamaMengambilJawabanAkhirPerPertanyaan() {
        val arr = JSONArray()
            .put(JSONObject().put("role", 0).put("text", "apa itu proot"))
            .put(JSONObject().put("role", 1).put("text", "").put("calls", JSONArray().put(JSONObject().put("id", "1"))))
            .put(JSONObject().put("role", 1).put("text", "Proot menjalankan distro Linux tanpa root."))
            .put(JSONObject().put("role", 0).put("text", "dan ollama?"))
        val pairs = ChatMemory.pairsFrom(arr)
        assertEquals(1, pairs.size)
        assertEquals("apa itu proot", pairs[0].first)
    }

    @Test
    fun konteksObrolanMenyisipkanIngatanDiPesanPengguna() {
        val ctx = localChatContext(emptyList(), "bagaimana build APK", "[Ingatan dari obrolan lama]\n- Tanya: a | Jawab: b\n")
        assertTrue(ctx.last().text.startsWith("[Ingatan dari obrolan lama]"))
        assertTrue(ctx.last().text.endsWith("Pesan pengguna: bagaimana build APK"))
        assertEquals("tanpa ingatan", localChatContext(emptyList(), "tanpa ingatan").last().text)
    }

    @Test
    fun sistemObrolanMenjelaskanBlokIngatan() {
        val s = localChatSystem("qwen:latest", "BOS")
        assertTrue(s.contains("[Ingatan dari obrolan lama]"))
        assertTrue(s.length < 1500)
    }

    @Test
    fun formatBerversiDanFormatLamaKeduanyaTerbaca() {
        val l = listOf(MemoryEntry("tanya satu", "jawab satu", 5L, 3))
        val json = ChatMemory.toJson(l)
        assertEquals(ChatMemory.FORMAT_VERSION, JSONObject(json).getInt("v"))
        assertEquals(3, ChatMemory.fromJson(json)[0].hits)
        // File dari versi lama aplikasi: larik polos tanpa nomor versi.
        val lama = JSONArray().put(JSONObject().put("u", "apa itu proot").put("a", "Proot tanpa root.").put("t", 7L).put("h", 1)).toString()
        val back = ChatMemory.fromJson(lama)
        assertEquals(1, back.size)
        assertEquals("apa itu proot", back[0].user)
        assertTrue(ChatMemory.fromJson("{\"v\":2}").isEmpty())
    }

    @Test
    fun gabungCadanganMenyatukanPertanyaanSamaDanTidakMembuangYangLain() {
        val local = listOf(
            MemoryEntry("cara install termux", "jawaban lama", now - day, 1),
            MemoryEntry("apa itu coroutine", "kode asinkron", now - 3 * day, 0)
        )
        val backup = listOf(
            MemoryEntry("Cara install Termux?", "jawaban baru", now, 4),
            MemoryEntry("resep nasi goreng", "tumis bawang", now - 2 * day, 0)
        )
        val m = ChatMemory.merge(local, backup, now)
        assertEquals(3, m.size)
        val termux = m.first { it.user.contains("termux", ignoreCase = true) }
        assertEquals("jawaban baru", termux.ai)
        assertEquals(4, termux.hits)
        assertTrue(m.zipWithNext().all { (x, y) -> x.ts <= y.ts })
        assertEquals(local.size, ChatMemory.merge(local, emptyList(), now).size)
    }

    @Test
    fun kataLangkaMengalahkanKataUmumDiMemoriYangBesar() {
        val umum = (1..3).map { MemoryEntry("install paket nomor$it", "jawaban umum nomor$it cukup panjang", now, 0) }
        val langka = MemoryEntry("atur kernel baru", "jawaban kernel yang jarang dibahas", now, 0)
        // Skor kecocokan dasar sama (satu kata di pertanyaan lama); yang kata kuncinya jarang muncul harus di depan.
        val out = ChatMemory.recall(umum + langka, "kernel install", now, k = 1)
        assertEquals("atur kernel baru", out[0].user)
    }
}
