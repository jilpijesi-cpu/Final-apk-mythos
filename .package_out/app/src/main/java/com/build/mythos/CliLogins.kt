package com.build.mythos

// Daftar CLI berlangganan dan pemeriksaan autentikasinya.
// Menambah CLI baru = menambah SATU baris di CLI_LOGINS: preset penyedia, tombol login, dan cek status ikut otomatis.
// Murni Kotlin tanpa Android, bisa di-unit-test (CliLoginTest).

internal class CliLogin(
    val name: String,     // nama di daftar penyedia (Pengaturan)
    val account: String,  // akun untuk login (dipakai di teks tombol dan status)
    val bin: String,      // nama perintah CLI
    val install: String,  // perintah pasang kalau belum ada
    val login: String,    // perintah login (dijalankan di sesi Termux yang terlihat)
    val check: String,    // perintah cek autentikasi: exit 0 = sudah terautentikasi
    val run: String,      // templat perintah chat; {prompt} diganti pesan pengguna
    val tip: String = "", // petunjuk singkat saat tombol login diketuk
    val ready: String = "" // pemeriksaan CLI yang benar-benar siap dipakai; kosong = command -v
)


/** Muat sumber environment umum Termux agar API key tersedia pada sesi RUN_COMMAND non-interaktif. */
internal fun geminiEnvironmentSetupCommand(): String =
    "if [ -z \"\${GEMINI_API_KEY:-}\" ] && [ -r \"\$HOME/.gemini/.env\" ]; then set -a; . \"\$HOME/.gemini/.env\" >/dev/null 2>&1; set +a; fi; " +
        "if [ -z \"\${GEMINI_API_KEY:-}\" ] && [ -r \"\$HOME/.bashrc\" ]; then . \"\$HOME/.bashrc\" >/dev/null 2>&1 || true; fi; " +
        "if [ -z \"\${GEMINI_API_KEY:-}\" ] && [ -r \"\$HOME/.profile\" ]; then . \"\$HOME/.profile\" >/dev/null 2>&1 || true; fi"

/** Gemini CLI mendukung login Google OAuth maupun API key melalui environment/.gemini/.env. */
internal fun geminiAuthCheckCommand(): String =
    "test -s ~/.gemini/oauth_creds.json || ( ${geminiEnvironmentSetupCommand()}; [ -n \"\${GEMINI_API_KEY:-}\" ] )"

/** Hilangkan redirect stderr dari konfigurasi lama agar error Gemini tidak tertutup. */
internal fun normalizeGeminiCommandTemplate(template: String): String =
    template.replace(Regex("\\s+2>/dev/null\\b"), "")

private const val NODE = "pkg install -y nodejs && "

/** Versi CLI npm yang sudah dicoba berjalan di perangkat. Naikkan hanya setelah diuji (bukan otomatis ke "latest"). */
internal const val GEMINI_CLI_VERSION = "0.71.1"

/** Versi Codex dari repositori Termux (pkg) yang diuji. Tidak dipatok di perintah pasang: repositori hanya menyimpan versi terbaru, jadi "codex=versi" akan gagal begitu repositori naik. */
internal const val CODEX_TESTED_VERSION = "0.172.0"

internal val CLI_LOGINS = listOf(
    CliLogin(
        "Codex (ChatGPT)", "ChatGPT", "codex",
        // Gunakan paket codex dari repositori Termux. Paket npm upstream gagal di Android
        // karena dependensi binary Linux ARM64 tidak tersedia; hindari wrapper CLI tiruan.
        // Mythos memakai protokol app-server untuk menerima delta teks langsung; Node.js diperlukan
        // hanya sebagai pembaca JSONL, bukan sebagai penyedia/model AI.
        "pkg update && pkg install -y nodejs codex",
        "codex login",
        "codex login status",
        // Template umum dipertahankan untuk kompatibilitas; cliChat memakai app-server untuk Codex.
        "codex exec --skip-git-repo-check {prompt}",
        "Menggunakan paket Codex dari repositori Termux (diuji: $CODEX_TESTED_VERSION) dan app-server untuk streaming langsung.",
        "dpkg-query -W -f='\${Status}' codex 2>/dev/null | grep -q 'install ok installed' && " +
            "command -v codex >/dev/null 2>&1 && command -v node >/dev/null 2>&1 && " +
            "codex --version >/dev/null 2>&1 && codex app-server --help >/dev/null 2>&1"
    ),
    CliLogin(
        "Gemini CLI (Google)", "Google", "gemini",
        NODE + "npm install -g @google/gemini-cli@$GEMINI_CLI_VERSION", "gemini",
        geminiAuthCheckCommand(),
        // Jangan buang stderr: pesan error autentikasi/jaringan harus terlihat di chat dan laporan.
        "gemini -p {prompt}",
        "Login dengan Google atau pilih Use Gemini API key, lalu kembali ke aplikasi"
    ),
    CliLogin(
        "Claude Code (Claude.ai)", "Claude.ai", "claude",
        NODE + "npm install -g @anthropic-ai/claude-code", "claude",
        "test -s ~/.claude/.credentials.json -o -s ~/.claude/credentials.json",
        "claude -p {prompt} 2>/dev/null",
        "Ikuti login di browser, lalu keluar dari Claude setelah berhasil"
    )
)

internal fun cliLoginOf(name: String): CliLogin? = CLI_LOGINS.firstOrNull { it.name == name }

// ---------------------------------------------------------------- pemeriksaan login sebelum chat (murni, diuji di CliPreflightTest)

/** Hasil pemeriksaan CLI. UNKNOWN = Termux belum menjawab (timeout atau proses dibunuh); jangan dianggap "belum login". */
internal enum class CliStatus { OK, NOT_READY, NOT_LOGGED_IN, UNKNOWN }

/**
 * Baca keluaran termux(): true = perintah sukses, false = gagal dengan jawaban pasti, null = tidak pasti.
 * Timeout (exit=124), SIGKILL (137), SIGTERM (143), dan galat pemanggilan Termux ("ERR:", "Gagal memanggil", "Tidak ada hasil")
 * bukan bukti bahwa pengguna belum masuk; sebelumnya semuanya dianggap "belum masuk" sehingga chat gagal bila Termux sedang dingin.
 */
internal fun preflightVerdict(result: String): Boolean? {
    val first = result.lineSequence().firstOrNull().orEmpty().trim()
    val m = Regex("^exit=(-?\\d+)\\b(.*)$").find(first) ?: return null
    val code = m.groupValues[1].toIntOrNull() ?: return null
    if (code == 124 || code == 137 || code == 143 || m.groupValues[2].contains("Timeout", ignoreCase = true)) return null
    return code == 0
}

/** Ingat CLI yang baru terbukti siap dan login, supaya tiap pesan tidak membayar dua panggilan Termux tambahan. */
internal class CliAuthCache(private val ttlMs: Long = 10 * 60_000L, private val now: () -> Long) {
    private val okAt = HashMap<String, Long>()
    @Synchronized fun isFresh(name: String): Boolean = okAt[name]?.let { now() - it < ttlMs } ?: false
    @Synchronized fun markOk(name: String) { okAt[name] = now() }
    @Synchronized fun invalidate(name: String) { okAt.remove(name) }
}
