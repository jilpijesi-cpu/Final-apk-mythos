package com.build.mythos

// Model open source lokal di HP: Ollama di Termux (penyedia "Ollama lokal", alamat 127.0.0.1:11434).
// Perintah dipisah dari UI supaya bisa diuji (OllamaSetupTest). Langkah-langkahnya muncul di "Siapkan Termux"
// bila penyedia yang dipilih adalah Ollama lokal.

internal object OllamaCmds {
    /** Jendela konteks server. Bawaan Ollama 4.096 token (tanpa GPU), lebih kecil dari prompt agen; terlalu besar memberatkan RAM HP. */
    const val CONTEXT = 8192

    /** Model kecil yang mendukung alat (tool) di Ollama. Perkiraan ukuran unduhan: ±1,9 GB dan ±1 GB. */
    const val MODEL_DEFAULT = "qwen2.5:3b"
    const val MODEL_LIGHT = "qwen2.5:1.5b"

    const val CHECK_INSTALLED = "command -v ollama >/dev/null 2>&1"
    const val CHECK_SERVER = "curl -s -m 3 http://127.0.0.1:11434/api/version"
    const val CHECK_MODELS = "curl -s -m 5 http://127.0.0.1:11434/api/tags"
    const val INSTALL = "pkg update -y >/dev/null 2>&1; pkg install -y ollama >/dev/null 2>&1"

    const val URL_VERSION = "http://127.0.0.1:11434/api/version"

    /** Lama maksimum menunggu server siap setelah dinyalakan (detik). Perintah START baru selesai saat server menjawab. */
    const val START_WAIT_SEC = 30

    /**
     * Jalankan server di latar belakang bila belum menjawab. termux-wake-lock mencegah Android mematikan Termux saat layar mati;
     * "< /dev/null" dan pengalihan log supaya perintah ini tidak menggantung menunggu server. Server yang sudah hidup TIDAK
     * dimatikan (dulu pkill selalu dijalankan, sehingga menekan tombol dua kali membunuh server yang masih bangun).
     * Perintah menunggu sampai /api/version menjawab (maks [START_WAIT_SEC] dtk) supaya pemeriksaan sesudahnya tidak salah merah.
     */
    val START: String =
        "termux-wake-lock 2>/dev/null; " +
            "if ! curl -s -m 2 $URL_VERSION >/dev/null 2>&1; then " +
            "OLLAMA_CONTEXT_LENGTH=$CONTEXT OLLAMA_NUM_PARALLEL=1 OLLAMA_KEEP_ALIVE=30m " +
            "nohup ollama serve >\$HOME/ollama.log 2>&1 < /dev/null & " +
            "i=0; while [ \$i -lt $START_WAIT_SEC ]; do curl -s -m 1 $URL_VERSION >/dev/null 2>&1 && break; i=\$((i+1)); sleep 1; done; " +
            "fi"

    /** Matikan lalu nyalakan ulang (untuk server yang macet atau perlu konteks baru). pkill -x (nama persis), bukan pkill -f, karena -f ikut mencocokkan perintah ini sendiri. */
    val RESTART: String = "pkill -x ollama 2>/dev/null; sleep 1; $START"

    fun pull(model: String): String = "ollama pull $model"

    /**
     * Tambahan laporan masalah: versi Ollama, model yang termuat (`ollama ps`), model terpasang, jumlah inti, dan baris cache terakhir di log.
     * Hanya berjalan bila Ollama terpasang. Semua perintah dibatasi `timeout` supaya server macet tidak menggantung laporan.
     */
    const val DIAG =
        "if command -v ollama >/dev/null 2>&1; then " +
            "echo \"ollama: \$(timeout 5 ollama --version 2>&1 | head -1) | inti: \$(nproc 2>&1)\"; " +
            "echo \"ollama ps:\"; timeout 5 ollama ps 2>&1 | head -5; " +
            "echo \"ollama list:\"; timeout 5 ollama list 2>&1 | head -8; " +
            "echo \"log cache:\"; grep -E \"new prompt|cached n_tokens\" \$HOME/ollama.log 2>/dev/null | tail -6 | cut -c1-160; " +
            "else echo \"ollama: belum terpasang\"; fi"

    /** Server menjawab dan sudah ada minimal satu model terunduh. */
    fun hasModel(tagsJson: String): Boolean = tagsJson.contains("\"name\"")

    /** Nama semua model terunduh dari jawaban /api/tags. */
    fun installedModels(tagsJson: String): List<String> =
        Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(tagsJson).map { it.groupValues[1] }.distinct().toList()

    /**
     * Varian cepat: model yang sama dengan jumlah thread dibatasi. Di HP 8 inti, 6 thread terukur ±2,7 kali lebih cepat daripada
     * bawaan Ollama (semua inti, inti kecil ikut menahan inti besar). Angka itu dari uji satu HP; HP lain bisa berbeda.
     */
    const val FAST_THREADS = 6
    const val FAST_SUFFIX = "-cepat"

    fun isFastVariant(model: String): Boolean = model.endsWith(FAST_SUFFIX)

    fun fastName(model: String): String = if (isFastVariant(model)) model else model + FAST_SUFFIX

    private val SAFE_MODEL = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]*$")

    /**
     * Perintah membuat varian cepat dari [model] lewat Modelfile (`num_thread`). Jumlah thread = min(inti HP, [FAST_THREADS]),
     * jadi HP berinti sedikit tidak diberi thread lebih banyak daripada intinya. Model asal dilepas dari RAM (`ollama stop`) supaya
     * dua salinan tidak berebut memori. Null bila nama model tidak aman dimasukkan ke perintah shell.
     */
    fun createFast(model: String): String? {
        if (!SAFE_MODEL.matches(model) || isFastVariant(model)) return null
        val name = fastName(model)
        return "n=\$(nproc 2>/dev/null || echo $FAST_THREADS); [ \"\$n\" -gt $FAST_THREADS ] && n=$FAST_THREADS; " +
            "printf 'FROM %s\\nPARAMETER num_thread %s\\n' '$model' \"\$n\" > \$HOME/Modelfile.cepat && " +
            "ollama create '$name' -f \$HOME/Modelfile.cepat && { ollama stop '$model' >/dev/null 2>&1; true; }"
    }

    /** Model dasar untuk varian cepat: pilihan pengguna bila terunduh, kalau tidak model non-varian pertama. Null bila tidak ada. */
    fun fastBase(installed: List<String>, selected: String): String? {
        val bases = installed.filter { !isFastVariant(it) }
        val sel = if (isFastVariant(selected)) selected.removeSuffix(FAST_SUFFIX) else selected
        return bases.firstOrNull { it == sel } ?: bases.firstOrNull()
    }
}

internal const val OLLAMA_PORT = 11434

internal const val LOCAL_DOWN_MSG =
    "Server Ollama di Termux tidak menjawab dan gagal dinyalakan otomatis. Pastikan Termux dan Ollama terpasang: " +
        "buka Pengaturan, Siapkan Termux, lalu Jalankan server, kemudian kirim \"Lanjutkan\"."

/** Server model lokal tidak bisa dihubungi. Bukan galat sementara: tidak diulang dan tidak dialihkan ke AI lain. */
internal class LocalServerDownException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/** Alamat Ollama bawaan di perangkat sendiri (127.0.0.1 atau localhost, port 11434): satu-satunya yang dinyalakan otomatis. */
internal fun isOllamaUrl(url: String): Boolean =
    Regex("^http://(127\\.0\\.0\\.1|localhost):$OLLAMA_PORT(/|$)").containsMatchIn(url.lowercase())

/** Koneksi ke server lokal ditolak = server belum jalan. Mengulang dengan jeda 2+4+8 dtk hanya membuang waktu. */
internal fun shouldFailFast(url: String, e: Throwable): Boolean = isLocalUrl(url) && e is java.net.ConnectException

/**
 * Model cadangan dalam satu penyedia. Di server lokal hanya model yang dipilih (daftar penuh tidak diambil sama sekali):
 * mengalihkan ke model lokal lain memuatnya ke RAM dan berebut CPU dengan model utama. Penyedia internet memakai [others].
 */
internal fun failoverModels(local: Boolean, saved: String, others: () -> List<String>): List<String> =
    if (local) listOfNotNull(saved.takeIf { it.isNotBlank() }) else others()

/** Cek cepat apakah port menerima koneksi (tanpa lewat Termux). */
internal fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
    java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), timeoutMs); true }
} catch (e: java.io.IOException) {
    false
}

/**
 * Pastikan server Ollama hidup sebelum permintaan model dikirim; bila mati, nyalakan lewat Termux dan tunggu sampai siap.
 * Dipanggil di thread latar. True bila server menerima koneksi.
 */
internal fun MainActivity.ensureOllamaUp(url: String): Boolean {
    if (!isOllamaUrl(url)) return true
    if (isPortOpen("127.0.0.1", OLLAMA_PORT, 400)) return true
    val snap = saveStatus()
    try {
        setStatus(IconView.CLOCK, "Menyalakan server Ollama", "Sekali saja, bisa sampai setengah menit")
        termux(OllamaCmds.START, timeoutSec = OllamaCmds.START_WAIT_SEC + 10L, useWorkdir = false)
        for (i in 0 until 10) {
            if (isPortOpen("127.0.0.1", OLLAMA_PORT, 400)) return true
            if (cancelled) throw Cancelled()
            Thread.sleep(500)
        }
        return isPortOpen("127.0.0.1", OLLAMA_PORT, 400)
    } finally {
        restoreStatus(snap)
    }
}

internal fun MainActivity.ollamaSetupSteps(again: () -> Unit): List<SetupStep> {
    val out = ArrayList<SetupStep>()
    val installed = termux(OllamaCmds.CHECK_INSTALLED, timeoutSec = 10, useWorkdir = false).startsWith("exit=0")
    out.add(
        SetupStep(
            "Ollama terpasang (model lokal)", installed,
            "Pemasangan sekitar beberapa menit.",
            listOf("Pasang Ollama" to { setupRun("Memasang Ollama", OllamaCmds.INSTALL, again) })
        )
    )
    if (!installed) return out

    val up = termux(OllamaCmds.CHECK_SERVER, timeoutSec = 10, useWorkdir = false).let { it.startsWith("exit=0") && it.contains("version") }
    out.add(
        SetupStep(
            "Server Ollama berjalan", up,
            "Server dinyalakan otomatis saat model dipakai. Tombol ini menyalakannya sekarang dan mengatur konteks ${OllamaCmds.CONTEXT} token; \"Mulai ulang\" bila server macet.",
            listOf(
                "Jalankan server" to { setupRun("Menjalankan server Ollama", OllamaCmds.START, again) },
                "Mulai ulang server" to { setupRun("Memulai ulang server Ollama", OllamaCmds.RESTART, again) }
            )
        )
    )
    if (!up) return out

    val tags = termux(OllamaCmds.CHECK_MODELS, timeoutSec = 10, useWorkdir = false)
    val has = OllamaCmds.hasModel(tags)
    out.add(
        SetupStep(
            "Model terunduh", has,
            "Butuh internet sekali saja. Model 3B butuh RAM sekitar 3 GB; pilih yang ringan bila HP lambat.",
            listOf(
                "Unduh ${OllamaCmds.MODEL_DEFAULT} (±1,9 GB)" to { setupRun("Mengunduh model", OllamaCmds.pull(OllamaCmds.MODEL_DEFAULT), again) },
                "Unduh ${OllamaCmds.MODEL_LIGHT} (±1 GB, lebih ringan)" to { setupRun("Mengunduh model", OllamaCmds.pull(OllamaCmds.MODEL_LIGHT), again) }
            )
        )
    )
    if (!has) return out

    // Varian cepat: model terpilih dengan jumlah thread dibatasi (Modelfile), dibuat dan dipilih otomatis.
    val installedModels = OllamaCmds.installedModels(tags)
    val base = OllamaCmds.fastBase(installedModels, cfgModel())
    val createCmd = base?.let { OllamaCmds.createFast(it) }
    if (base != null && createCmd != null) {
        val fast = OllamaCmds.fastName(base)
        val p = prov
        out.add(
            SetupStep(
                "Varian cepat (${OllamaCmds.FAST_THREADS} thread)", fast in installedModels,
                "Model yang sama dengan jumlah thread dibatasi; di HP uji ±2,7 kali lebih cepat daripada bawaan Ollama, " +
                    "hasil HP lain bisa berbeda. Tidak memakai ruang tambahan. Setelah dibuat, model ini dipilih otomatis.",
                listOf("Buat varian cepat dari $base" to {
                    setupRun("Membuat varian cepat", createCmd, again) { ok -> if (ok && prov == p) useFastModel(fast) }
                })
            )
        )
    }
    return out
}

/** Pilih varian cepat sebagai model aktif dan masukkan ke daftar pilihan model (daftar hasil uji tidak menangkapnya sendiri). */
internal fun MainActivity.useFastModel(fast: String) {
    prefs.edit().putString("p_${prov}_model", fast).apply()
    loadProbed()?.let { (good, busy) -> if (fast !in good) saveProbed(good + fast, busy) }
    updateModelLabel()
    toast("Model diganti ke $fast")
}
