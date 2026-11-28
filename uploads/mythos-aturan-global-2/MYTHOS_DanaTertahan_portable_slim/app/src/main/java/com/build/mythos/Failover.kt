package com.build.mythos

// Logika murni (tanpa android.*) untuk peralihan AI otomatis: susunan cadangan dan status yang ditampilkan di kartu.
// Dipisah dari UI supaya bisa diuji sebagai unit test JVM.

internal data class FoCandidate(val provider: String, val model: String) {
    val id: String get() = "$provider/$model"
}

/** Daftar model satu penyedia: [saved] = model pilihan pengguna di Pengaturan, [models] = model lain yang diketahui. */
internal class ProviderPool(val name: String, val saved: String, val models: List<String>)

/** Urutkan model dari yang paling disukai (aturan [bestModel]); duplikat dibuang. */
internal fun rankModels(models: List<String>, provider: String = ""): List<String> {
    val rest = models.distinct().toMutableList()
    val out = ArrayList<String>()
    while (rest.isNotEmpty()) {
        val m = bestModel(rest, provider) ?: break
        out += m
        rest.remove(m)
    }
    return out
}

/**
 * Susun urutan percobaan lintas penyedia dan model. Penyedia utama didahulukan, tapi tiap "tingkat" hanya
 * mengambil [tierSize] model per penyedia sebelum pindah ke penyedia berikutnya. Jadi kalau satu penyedia sedang
 * bermasalah total, penyedia lain dicoba lebih cepat. Model di [blocked] (id "penyedia/model") dilewati.
 */
internal fun buildChain(
    main: String,
    pools: List<ProviderPool>,
    blocked: Set<String>,
    tierSize: Int = 2,
    tiers: Int = 3,
    total: Int = 18
): List<FoCandidate> {
    val ordered = pools.sortedBy { if (it.name == main) 0 else 1 }
    val lists = ordered.map { p ->
        val all = (listOf(p.saved).filter { it.isNotBlank() } + rankModels(p.models, p.name)).distinct()
        p.name to all.map { FoCandidate(p.name, it) }.filter { it.id !in blocked }
    }
    val out = ArrayList<FoCandidate>()
    for (t in 0 until tiers) {
        for ((_, l) in lists) out += l.drop(t * tierSize).take(tierSize)
    }
    return out.take(total)
}

/**
 * Boleh masuk antrean pemilihan otomatis? Penyedia lokal (Ollama di HP) hanya dipakai bila itu penyedia utama pilihan pengguna:
 * di CPU HP satu jawaban bisa memakan menit, jadi tidak pantas jadi cadangan yang dipilih sistem diam-diam.
 */
internal fun autoPickable(name: String, main: String, isLocal: Boolean): Boolean = name == main || !isLocal

/**
 * F-13: kebijakan failover lintas penyedia. Secara bawaan ([crossProviderOptIn] = false),
 * failover hanya mencoba model lain pada penyedia utama yang sama agar konteks percakapan
 * tidak berpindah ke vendor AI lain tanpa persetujuan. Bila opt-in aktif dan [allowlist] tidak kosong,
 * hanya penyedia pada allowlist yang boleh dipakai.
 */
internal fun autoPickableFor(
    name: String,
    main: String,
    isLocal: Boolean,
    crossProviderOptIn: Boolean,
    allowlist: Set<String> = emptySet()
): Boolean {
    if (name == main) return true
    if (isLocal) return false
    if (!crossProviderOptIn) return false
    if (allowlist.isNotEmpty() && name !in allowlist) return false
    return true
}

internal enum class FoState { WAIT, TRYING, BUSY, QUOTA, REJECTED, OFFLINE, OK }

internal class FoRow(val provider: String, val model: String, var state: FoState)

/** 0 = bekerja, 1 = berhasil, 2 = gagal total. */
internal data class FoRowSnap(val label: String, val state: FoState, val note: String)

internal data class FoSnapshot(
    val title: String,
    val subtitle: String,
    val rows: List<FoRowSnap>,
    val tone: Int,
    val waiting: Boolean
)

internal fun foNote(s: FoState): String = when (s) {
    FoState.WAIT -> "antre"
    FoState.TRYING -> "mencoba…"
    FoState.BUSY -> "sedang ramai"
    FoState.QUOTA -> "kuota habis"
    FoState.REJECTED -> "ditolak"
    FoState.OFFLINE -> "tidak terhubung"
    FoState.OK -> "tersambung"
}

/** Pelacak satu episode peralihan: kejadian masuk lewat fungsi, tampilan dibaca lewat [snapshot]. */
internal class FailoverTracker(val maxRounds: Int) {
    val rows = ArrayList<FoRow>()
    var round = 1
    var countdown = 0
    var searching = false
    private var finished = 0 // 0 berjalan, 1 berhasil, 2 gagal

    private fun find(p: String, m: String): FoRow? = rows.lastOrNull { it.provider == p && it.model == m }

    fun failed(p: String, m: String, st: FoState) {
        val r = find(p, m) ?: FoRow(p, m, st).also { rows += it }
        r.state = st
    }

    fun trying(p: String, m: String) {
        searching = false
        countdown = 0
        // Putaran baru: percobaan lama yang sama ditampilkan sebagai baris baru supaya riwayatnya terbaca.
        val r = find(p, m)
        if (r != null && r.state == FoState.TRYING) return
        rows += FoRow(p, m, FoState.TRYING)
    }

    fun ok() { rows.lastOrNull { it.state == FoState.TRYING }?.state = FoState.OK; finished = 1; countdown = 0 }
    fun giveUp() { rows.filter { it.state == FoState.TRYING }.forEach { it.state = FoState.BUSY }; finished = 2; countdown = 0 }

    fun snapshot(): FoSnapshot {
        val cur = rows.lastOrNull { it.state == FoState.TRYING || it.state == FoState.OK }
        val label = cur?.let { "${it.provider} · ${it.model}" } ?: ""
        val rs = rows.map { FoRowSnap("${it.provider} · ${it.model}", it.state, foNote(it.state)) }
        return when {
            finished == 1 -> FoSnapshot("Tersambung ke $label", "Tugas dilanjutkan otomatis", rs, 1, false)
            finished == 2 -> FoSnapshot("Tidak ada AI yang tersedia", "Periksa kuota dan API key, lalu kirim \"Lanjutkan\"", rs, 2, false)
            countdown > 0 -> FoSnapshot("Semua jalur AI sedang ramai", "Menunggu giliran, pekerjaan lanjut otomatis", rs, 0, true)
            searching -> FoSnapshot("Mencari AI yang tersedia", "Memeriksa API key yang terdaftar…", rs, 0, false)
            else -> FoSnapshot("Mengalihkan AI otomatis", if (label.isEmpty()) "Menyiapkan cadangan…" else "Mencoba $label", rs, 0, false)
        }
    }
}

/**
 * Teks chip model. Tanpa peralihan: nama model biasa. Saat peralihan otomatis aktif, awalan "↪ " menandakan
 * bahwa yang menjawab bukan pilihan di Pengaturan; penyedia ikut ditulis bila berbeda dari penyedia utama.
 */
internal fun activeModelText(
    cli: Boolean, mainProv: String, savedModel: String,
    fallbackProv: String?, fallbackModel: String?, modelOverride: String?
): String {
    if (cli) return "CLI"
    val fp = fallbackProv
    if (fp != null) {
        val m = fallbackModel.orEmpty().removePrefix("models/")
        return if (m.isBlank()) "\u21AA $fp" else "\u21AA $fp \u00B7 $m"
    }
    val ov = modelOverride
    if (!ov.isNullOrBlank()) return "\u21AA " + ov.removePrefix("models/")
    return savedModel.ifBlank { mainProv }.removePrefix("models/")
}

/** Baris identitas untuk prompt sistem, supaya model menjawab benar saat ditanya model/penyedia apa dia. */
internal fun identityLine(provider: String, model: String): String =
    "Identitas teknis (data dari aplikasi): kamu berjalan lewat penyedia $provider dengan model $model. " +
        "Bila pengguna bertanya model atau penyedia apa kamu, jawab persis sesuai data ini dan jangan menebak nama model atau perusahaan lain."
