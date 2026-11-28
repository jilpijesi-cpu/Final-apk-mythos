package com.build.mythos

// Perekat antara MainActivity dan lapisan sandbox (CommandPolicy, SandboxRunner).
// Fungsi-fungsi di sini adalah ekstensi MainActivity; logika sebenarnya ada di file murni agar bisa diuji.

internal val sandboxConfig = SandboxConfig()

/** Opt-in lewat Pengaturan. Default mati supaya perilaku lama tidak berubah. */
internal fun MainActivity.sandboxEnabled(): Boolean = prefs.getBoolean("sandbox", false)

/** Jalankan SETELAH persetujuan. Mengembalikan format lama "exit=N\n<isi>". */
internal fun MainActivity.runInSandbox(cmd: String, isBuild: Boolean, liveFile: String?): String {
    val shell = ShellExecutor { c, timeoutSec, limit, live ->
        termux(c, limit = limit, timeoutSec = timeoutSec, useWorkdir = false, liveFile = live)
    }
    val runner: CommandRunner = SandboxRunner(shell, sandboxConfig)
    // Build: batas keluaran dinaikkan (error Gradle ada di UJUNG keluaran, sedangkan pemotongan sandbox membuang ujungnya),
    // lalu hasilnya dipadatkan di sini dengan mempertahankan awal dan akhir.
    val limits = RunLimits(
        timeoutSec = if (isBuild) 1800L else RunLimits.DEFAULT_TIMEOUT_SEC,
        maxOutputBytes = if (isBuild) 90_000 else RunLimits.DEFAULT_MAX_OUTPUT_BYTES
    )
    val res = runner.run(RunRequest(cmd, limits, liveFile)).toLegacyString()
    return if (isBuild) middleTrim(res, 8000) else res
}

