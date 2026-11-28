package com.build.mythos

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings

/** True bila MYTHOS sudah dipilih sebagai Aplikasi asisten digital. Null bila status tidak bisa dibaca di perangkat ini. */
internal fun MainActivity.isAssistantSelected(): Boolean? = try {
    val v = Settings.Secure.getString(contentResolver, "assistant")
    if (v == null) null else ComponentName.unflattenFromString(v)?.packageName == packageName
} catch (_: Exception) { null }

/** Penjelasan singkat + tombol ke pengaturan sistem untuk memilih MYTHOS sebagai asisten digital. */
internal fun MainActivity.showAssistSetup() {
    val status = when (isAssistantSelected()) {
        true -> "Status: MYTHOS sudah menjadi asisten digital.\n\n"
        false -> "Status: belum dipilih.\n\n"
        null -> ""
    }
    AlertDialog.Builder(this)
        .setTitle("Asisten layar")
        .setMessage(
            status +
                "MYTHOS bisa muncul sebagai panel di atas aplikasi lain, membaca isi layar yang sedang Anda buka, lalu menjelaskan atau membantu mengatasi masalahnya.\n\n" +
                "1. Tekan \"Buka pengaturan\", lalu pilih MYTHOS sebagai Aplikasi asisten digital.\n" +
                "2. Aktifkan \"Gunakan teks layar\" (dan \"Gunakan tangkapan layar\" bila ada).\n" +
                "3. Buka aplikasi apa pun, lalu tahan tombol power atau pakai gestur asisten.\n\n" +
                "Isi layar hanya dikirim saat Anda memanggil panel, ke penyedia AI yang Anda pilih di MYTHOS. Kolom kata sandi dan layar yang ditandai aman oleh aplikasi tidak dibaca."
        )
        .setNegativeButton("Tutup", null)
        .setPositiveButton("Buka pengaturan") { _, _ -> openAssistantSettings() }
        .show()
}

private fun MainActivity.openAssistantSettings() {
    val tries = listOf(
        Intent("android.settings.VOICE_INPUT_SETTINGS"),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
    for (i in tries) {
        try { startActivity(i); return } catch (_: Exception) {}
    }
}
