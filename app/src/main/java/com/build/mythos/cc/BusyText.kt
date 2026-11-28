package com.build.mythos.cc

// Kalimat status untuk saat server AI sedang padat, antre (429), atau jaringan naik-turun. Percobaan ulang tetap berjalan
// otomatis di latar belakang; pengguna hanya melihat satu kalimat ramah di baris live, tanpa kode galat dan tanpa hitung mundur.
// Murni Kotlin (tanpa Android), diuji di BusyTextTest.

enum class BusyKind { BUSY, RATE, NETWORK, SWITCH, ALL_BUSY }

private val LINES: Map<BusyKind, List<String>> = mapOf(
    BusyKind.BUSY to listOf(
        "Jalur AI sedang padat, mencari jalur yang lebih lancar…",
        "Lalu lintas server sedang ramai, sebentar lagi lanjut…",
        "Masih antre di jalur AI, pekerjaan lanjut otomatis…"
    ),
    BusyKind.RATE to listOf(
        "Antre sebentar agar AI tetap lancar menjawab…",
        "Memberi napas sejenak untuk server AI, lalu lanjut…"
    ),
    BusyKind.NETWORK to listOf(
        "Sinyal ke server sedang naik-turun, menyambung ulang…",
        "Menyambung ulang ke server AI di latar belakang…"
    ),
    BusyKind.SWITCH to listOf("Berpindah ke jalur AI yang lebih lega…"),
    BusyKind.ALL_BUSY to listOf("Semua jalur AI sedang ramai, menunggu giliran lalu lanjut…")
)

/** Satu kalimat status untuk [kind]; [n] (percobaan ke berapa) memutar variasi agar terasa hidup. */
fun busyLine(kind: BusyKind, n: Int = 0): String {
    val l = LINES.getValue(kind)
    return l[Math.floorMod(n, l.size)]
}

/** Jenis keadaan dari kode HTTP (-1 = tidak ada) dan pesan galat. */
fun busyKindOf(code: Int, message: String?): BusyKind {
    val m = (message ?: "").lowercase()
    return when {
        code == 429 || m.contains("rate limit") || m.contains("too many requests") || m.contains("quota") -> BusyKind.RATE
        code >= 500 || m.contains("overloaded") || m.contains("high demand") || m.contains("unavailable") -> BusyKind.BUSY
        code <= 0 || m.contains("jaringan") || m.contains("timeout") || m.contains("unexpected end") -> BusyKind.NETWORK
        else -> BusyKind.BUSY
    }
}
