package com.build.mythos

import org.json.JSONObject

// Pemangkasan konteks (logika murni, diuji di ContextTrimTest).
// Masalah lama: satu tugas panjang hanya punya SATU pesan pengguna, sedangkan pemangkasan hanya memotong di pesan
// pengguna berikutnya. Akibatnya konteks tumbuh tanpa batas sampai API menolak. Argumen write_file (isi file penuh)
// juga tidak pernah dipangkas. Sekarang: (a) argumen panjang di giliran lama dipangkas, (b) pemotongan tidak lagi
// berhenti di pesan [SISTEM] sehingga permintaan asli tidak hilang, (c) bila masih terlalu besar, pasangan
// model+hasil tertua dibuang sambil mempertahankan pesan pengguna pertama.

internal const val OLD_ARG_MAX = 300

/** Hasil alat terbaru yang dikirim utuh (penyedia internet): bergeser satu per giliran. */
internal const val RESULT_KEEP_RECENT = 4

/**
 * Model lokal: Ollama hanya memakai ulang cache untuk awalan prompt yang persis sama. Kalau batas "hasil lama dipersingkat"
 * bergeser tiap giliran, satu hasil di tengah riwayat berubah setiap kali dan seluruh bagian sesudahnya dibaca ulang CPU HP.
 * Maka batasnya melompat per [STABLE_STEP] hasil: awalan tetap sama selama beberapa giliran. Hasil utuh tersisa 3 sampai 6.
 */
internal const val STABLE_KEEP_MIN = 3
internal const val STABLE_STEP = 3

/** Indeks riwayat pertama yang masih dikirim utuh; hasil alat (role 2) sebelum indeks ini dipersingkat. [resIdx] = indeks hasil alat. */
internal fun shrinkKeepFrom(resIdx: List<Int>, start: Int, stable: Boolean): Int {
    val n = resIdx.size
    if (!stable) return if (n > RESULT_KEEP_RECENT) resIdx[n - RESULT_KEEP_RECENT] else start
    if (n <= STABLE_KEEP_MIN + STABLE_STEP) return start
    return resIdx[((n - STABLE_KEEP_MIN - 1) / STABLE_STEP) * STABLE_STEP]
}

internal fun histChars(h: Hist): Int =
    (if (h.raw != null) h.raw.toString().length
    else h.text.length + h.calls.sumOf { it.args.toString().length + 40 } + h.results.sumOf { it.second.length + 40 }) +
        h.images.size * 5000

/** Salin argumen dengan nilai teks yang panjang dipangkas. */
internal fun shrinkArgs(args: JSONObject, n: Int = OLD_ARG_MAX): JSONObject {
    val out = JSONObject()
    val it = args.keys()
    while (it.hasNext()) {
        val k = it.next()
        val v = args.opt(k)
        if (v is String && v.length > n) out.put(k, v.take(n) + "\n...(dipangkas)") else out.put(k, v)
    }
    return out
}

/** Giliran model lama: pangkas isi file panjang di argumen panggilan. Teks dan nama alat tetap utuh. */
internal fun shrinkCalls(h: Hist, n: Int = OLD_ARG_MAX): Hist {
    if (h.role != 1 || (h.calls.isEmpty() && h.raw == null)) return h
    val raw = h.raw?.let { r ->
        val copy = JSONObject(r.toString())
        val parts = copy.optJSONArray("parts")
        if (parts != null) for (i in 0 until parts.length()) {
            val fc = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: continue
            fc.optJSONObject("args")?.let { fc.put("args", shrinkArgs(it, n)) }
        }
        copy
    }
    return Hist(1, h.text, h.calls.map { Call(it.id, it.name, shrinkArgs(it.args, n)) }, raw = raw, images = h.images)
}

/** Jumlah pesan pengguna sungguhan terakhir yang tidak boleh dibuang oleh pemangkasan (agar "iya, lanjutkan" tetap nyambung). */
internal const val KEEP_USER_TURNS = 3

/** Anggaran besar (penyedia internet) mempertahankan lebih banyak pesan pengguna terakhir; anggaran kecil tetap [KEEP_USER_TURNS]. */
internal const val KEEP_USER_TURNS_BIG = 5
internal const val BIG_BUDGET_FROM = 40_000

/** Teks lama (pengguna/model) yang lebih panjang dari ini dipotong bila konteks masih terlalu besar. */
internal const val OLD_TEXT_MAX = 1500

private fun isRealUser(h: Hist): Boolean = h.role == 0 && !h.text.startsWith(SYS_PREFIX)

/** Salin giliran model bergaya raw (Gemini) tanpa functionCall dengan bagian teks panjang dipotong. Selain itu dikembalikan apa adanya. */
private fun shrinkRawText(h: Hist, n: Int): Hist {
    val r = h.raw ?: return h
    val parts = r.optJSONArray("parts") ?: return h
    var needs = false
    for (i in 0 until parts.length()) {
        val p = parts.optJSONObject(i) ?: return h
        if (p.has("functionCall")) return h
        if (p.optString("text").length > n) needs = true
    }
    if (!needs) return h
    val copy = JSONObject(r.toString())
    val cp = copy.getJSONArray("parts")
    for (i in 0 until cp.length()) {
        val p = cp.getJSONObject(i)
        val t = p.optString("text")
        if (t.length > n) p.put("text", t.take(n) + "\n...(dipangkas)")
    }
    return Hist(h.role, h.text.take(n).let { if (h.text.length > n) it + "\n...(dipangkas)" else it }, h.calls, h.results, copy, h.images)
}

/** Pangkas teks panjang sebelum pesan pengguna terakhir. */
private fun shrinkOldTexts(list: List<Hist>, n: Int): List<Hist> {
    val lastReal = list.indexOfLast { isRealUser(it) }
    if (lastReal <= 0) return list
    return list.mapIndexed { i, h ->
        when {
            i >= lastReal -> h
            h.role == 1 && h.raw != null -> shrinkRawText(h, n)
            (h.role == 0 || h.role == 1) && (h.text.length > n || h.text.contains(ATT_TAG_START)) -> {
                // Blok isi lampiran lama dibuang dulu (ringkasannya tetap ada di LAMPIRAN AKTIF), baru teksnya dipotong.
                val base = stripAttachmentBlocks(h.text, ATT_PLACEHOLDER)
                Hist(h.role, if (base.length > n) base.take(n) + "\n...(dipangkas)" else base, h.calls, h.results, null, h.images)
            }
            else -> h
        }
    }
}

internal fun trimContext(input: List<Hist>, budget: Int): List<Hist> {
    var list = input
    var total = list.sumOf { histChars(it) }
    val keepTurns = if (budget >= BIG_BUDGET_FROM) KEEP_USER_TURNS_BIG else KEEP_USER_TURNS
    // 1) Potong di pesan pengguna SUNGGUHAN berikutnya (bukan nudge [SISTEM]), tetapi JANGAN sampai kurang dari
    //    KEEP_USER_TURNS pesan pengguna terakhir. Dulu pemotongan jalan terus sampai hanya pesan terakhir tersisa,
    //    sehingga "Iya silakan kerjakan" dikirim sendirian dan model lupa seluruh percakapan.
    while (total > budget) {
        val real = list.indices.filter { isRealUser(list[it]) }
        val next = real.firstOrNull { it > 0 } ?: break
        if (real.count { it >= next } < keepTurns) break
        total -= list.subList(0, next).sumOf { histChars(it) }
        list = list.subList(next, list.size)
    }
    if (total <= budget) return list
    // 2) Tugas tunggal panjang: pertahankan pesan pertama, buang pasangan (model + hasil alat) tertua.
    var m: List<Hist> = list
    if (list.size > 4 && list[0].role == 0) {
        val w = ArrayList(list)
        var dropped = 0
        while (total > budget && w.size > 4) {
            val a = w[1]
            val b = w[2]
            if (a.role == 1 && a.calls.isNotEmpty() && b.role == 2) {
                total -= histChars(a) + histChars(b)
                w.removeAt(2); w.removeAt(1)
                dropped++
            } else break
        }
        if (dropped > 0) {
            val f = w[0]
            w[0] = Hist(0, f.text + "\n\n" + SYS_PREFIX + "$dropped langkah lama dipangkas dari konteks agar muat. " +
                "Periksa keadaan file/proyek dengan alat bila butuh detail langkah itu.", images = f.images)
        }
        m = w
    }
    // 3) Masih terlalu besar: persingkat teks lama, bukan membuang giliran percakapan.
    if (total > budget) m = shrinkOldTexts(m, OLD_TEXT_MAX)
    return m
}
