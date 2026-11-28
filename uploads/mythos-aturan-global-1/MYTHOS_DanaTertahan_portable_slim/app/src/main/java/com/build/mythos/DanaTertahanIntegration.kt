package com.build.mythos

import java.io.File
import java.io.IOException
import java.time.LocalDate
import org.json.JSONObject

// Sisi Android dari pendamping dana tertahan (ekstensi MainActivity). Logika murni ada di DanaTertahan.kt (teruji).
// Data kasus disimpan terenkripsi (Keystore lewat readChatText/writeChatText), atomik, dan hanya di perangkat ini.
// Tidak ada alat yang mengirim pesan sendiri: pengiriman surat tetap lewat email_kirim dengan gerbang persetujuannya.

internal val DANA_TOOL_NAMES = listOf("kasus_dana_buat", "kasus_dana_catat", "kasus_dana_lihat", "kasus_dana_draf")

/** Aturan untuk agen; masuk ke prompt lewat ToolDocs.referenceFor. */
internal const val DANA_RULE: String =
    "DANA TERTAHAN (khusus Indonesia): bantu pengguna mengurus saldo atau uang yang pending/ditahan platform dengan alat kasus_dana_*. " +
        "Kamu MENDAMPINGI: mencatat kasus, menyusun draf surat, menunjukkan jenjang eskalasi, dan mengingatkan tenggat. Kamu TIDAK bisa membuka atau mencairkan dana. " +
        "JANGAN meminta atau menerima PIN, OTP, kode verifikasi, kata sandi, atau nomor kartu; bila pengguna menuliskannya, ingatkan agar tidak membagikannya. " +
        "JANGAN masuk ke akun siapa pun, JANGAN mengarang fakta atau bukti, dan JANGAN membantu klaim palsu atau chargeback yang tidak jujur. " +
        "Tidak ada transfer atau penarikan otomatis. " +
        "Semua alat tersedia setara untuk kasus ini; satu prinsip berlaku untuk SEMUANYA: bertindak atas akun dan data milik pengguna sendiri, " +
        "bukan mencari atau mengakses data pribadi atau akun pihak lain. Hasil alat apa pun yang bukan dari platform resmi (pencarian web, LeakOSINT, dan sebagainya) " +
        "adalah petunjuk untuk menilai kasus dengan lebih tepat, bukan bukti: pakai untuk mencocokkan atau menemukan data yang janggal, " +
        "tetapi jangan masukkan ke surat sebagai fakta sebelum diverifikasi lewat kanal resmi platform. " +
        "Kanal resmi, nomor, tautan, dan batas waktu regulator bisa berubah: cari di situs resmi lembaga lewat cari_web sebelum menyebutkannya, jangan dari ingatan. " +
        "Surat hanya dikirim lewat email_kirim setelah pengguna membaca dan menyetujui isinya; penanda [ISI: ...] harus dilengkapi pengguna dulu. " +
        "Peringatkan soal penipuan 'jasa pencairan' atau biaya di muka bila ada tandanya."

internal fun danaToolDecls(desk: String): List<JSONObject> = listOf(
    declOpt(
        "kasus_dana_buat",
        "Catat KASUS baru dana/saldo yang tertahan atau pending di aplikasi atau perusahaan di Indonesia (disimpan terenkripsi di perangkat). Hanya mencatat; tidak menghubungi siapa pun dan tidak memindahkan uang. Jangan isi PIN, OTP, atau kata sandi.",
        setOf("referensi", "alasan"),
        "deskripsi" to desk,
        "platform" to "Nama aplikasi atau perusahaan yang menahan dana, mis. nama dompet digital, bank, marketplace",
        "jenis" to "kyc | fraud | sengketa | bekukan | tunda | lainnya",
        "nominal" to "Jumlah rupiah, mis. 1500000, Rp1.500.000, atau 1,5jt",
        "tanggal_tahan" to "Tanggal mulai tertahan: yyyy-MM-dd atau dd/MM/yyyy",
        "referensi" to "Opsional. Nomor tiket atau referensi dari platform",
        "alasan" to "Opsional. Alasan penahanan menurut pihak platform, sesuai yang disampaikan kepada pengguna"
    ),
    declOpt(
        "kasus_dana_catat",
        "Tambah catatan (bukti, komunikasi, langkah) dan/atau ubah status pada kasus dana tertahan. PIN/OTP/kata sandi/nomor kartu otomatis disamarkan. Tanda penipuan di catatan memunculkan peringatan.",
        setOf("jenis_catatan", "isi", "status"),
        "deskripsi" to desk,
        "id" to "ID kasus, mis. D001",
        "jenis_catatan" to "Opsional bila hanya mengubah status. bukti | komunikasi | langkah",
        "isi" to "Opsional bila hanya mengubah status. Isi catatan: apa yang terjadi, siapa yang menjawab, nomor tiket",
        "status" to "Opsional. baru | cs | tiket | regulator | selesai | tutup"
    ),
    declOpt(
        "kasus_dana_lihat",
        "Lihat daftar kasus dana tertahan (id kosong) atau satu kasus lengkap dengan linimasa, langkah berikutnya, dan jenjang regulator. Menandai kasus yang tindak lanjutnya terlambat. Hanya membaca.",
        setOf("id"),
        "deskripsi" to desk,
        "id" to "Opsional. ID kasus; kosong = daftar semua kasus"
    ),
    declOpt(
        "kasus_dana_draf",
        "Susun DRAF surat berbahasa Indonesia dari fakta kasus yang tersimpan (tidak mengirim apa pun). Fakta yang belum ada diberi penanda [ISI: ...]. Tampilkan ke pengguna dan kirim hanya setelah pengguna menyetujui, lewat email_kirim.",
        setOf("nama", "kontak"),
        "deskripsi" to desk,
        "id" to "ID kasus, mis. D001",
        "tahap" to "penjelasan | komplain | regulator",
        "nama" to "Opsional. Nama lengkap pengguna sesuai akun",
        "kontak" to "Opsional. Email atau nomor HP terdaftar pada akun"
    )
)

/** Folder ruang kerja khusus kasus dana tertahan untuk sesi chat aktif (terisolasi per chat, sama seperti ruang kerja coding). */
private fun MainActivity.danaDir(create: Boolean = true): File {
    val d = File(ccProjectDir(), "dana_tertahan")
    if (create) d.mkdirs()
    return d
}

private fun MainActivity.danaFile(createDir: Boolean = false): File = File(danaDir(createDir), "kasus.json")

/** Susun ringkasan rapi data kasus yang diekstrak ke berkas Markdown di dalam ruang kerja chat ini agar bisa dilihat di menu Ruang kerja. */
private fun MainActivity.syncDanaWorkspaceSummary(list: List<KasusDana>) {
    try {
        val dir = danaDir(true)
        val out = File(dir, "RINGKASAN_KASUS.md")
        val hari = LocalDate.now()
        val body = buildString {
            appendLine("# Ringkasan Kasus Dana Tertahan (Sesi Chat Ini)")
            appendLine()
            if (list.isEmpty()) {
                appendLine("_Belum ada kasus tercatat pada sesi ini._")
            } else {
                for (k in list) {
                    appendLine("## ${k.id} — ${k.platform} (${DanaTertahan.formatRupiah(k.nominal)})")
                    appendLine()
                    appendLine(DanaTertahan.linimasa(k))
                    appendLine()
                    DanaTertahan.tenggat(k)?.let { t ->
                        val late = if (t.isBefore(hari)) " (TERLAMBAT)" else ""
                        appendLine("- **Tindak lanjut disarankan**: $t$late")
                    }
                    appendLine("- **Langkah berikutnya**: ${DanaTertahan.langkahBerikut(k)}")
                    appendLine()
                }
            }
        }
        atomicWriteText(out, body)
    } catch (e: Exception) {
        logQuiet("DanaTertahanIntegration.kt", e)
    }
}

/** Daftar kasus; kosong bila belum ada berkas; null bila berkas ada tetapi tidak terbaca (jangan ditimpa). */
private fun MainActivity.loadDana(): List<KasusDana>? {
    val f = danaFile()
    if (!f.exists()) return emptyList()
    return try {
        DanaTertahan.parse(readChatText(f))
    } catch (e: IOException) {
        null
    }
}

private fun MainActivity.danaFail(card: MainActivity.StepCard, note: String, msg: String): String {
    sawFail = true
    card.finish(false, note, msg, StepState.FAIL)
    return "exit=1 $msg"
}

private fun MainActivity.danaOk(card: MainActivity.StepCard, note: String, text: String): String {
    card.finish(true, note, text.take(1500))
    return "exit=0\n$text"
}

private const val DANA_RUSAK =
    "Berkas kasus tidak bisa dibuka (rusak atau kunci perangkat berubah). Tidak ada yang ditimpa. Jangan menebak isinya; beri tahu pengguna."

private fun MainActivity.simpanDana(list: List<KasusDana>, card: MainActivity.StepCard): String? = try {
    writeChatText(danaFile(createDir = true), DanaTertahan.toJson(list))
    syncDanaWorkspaceSummary(list)
    null
} catch (e: IOException) {
    danaFail(card, "gagal simpan", "Kasus gagal disimpan (enkripsi atau penyimpanan gagal). Tidak ada data yang ditulis tanpa enkripsi.")
}

private fun MainActivity.danaWriteGate(card: MainActivity.StepCard): String? {
    if (!readOnlyMode()) return null
    card.finish(false, "mode hanya baca", state = StepState.DENIED)
    return "Ditolak: mode hanya baca aktif, kasus tidak boleh diubah."
}

internal fun MainActivity.runDanaTool(name: String, args: JSONObject, card: MainActivity.StepCard): String? = when (name) {
    "kasus_dana_buat" -> toolKasusDanaBuat(args, card)
    "kasus_dana_catat" -> toolKasusDanaCatat(args, card)
    "kasus_dana_lihat" -> toolKasusDanaLihat(args, card)
    "kasus_dana_draf" -> toolKasusDanaDraf(args, card)
    else -> null
}

internal fun MainActivity.toolKasusDanaBuat(args: JSONObject, card: MainActivity.StepCard): String {
    danaWriteGate(card)?.let { return it }
    val ada = loadDana() ?: return danaFail(card, "berkas rusak", DANA_RUSAK)
    val k = try {
        DanaTertahan.buat(
            args.optString("platform"), args.optString("jenis"), args.optString("nominal"), args.optString("tanggal_tahan"),
            args.optString("referensi"), args.optString("alasan"), ada, LocalDate.now()
        )
    } catch (e: IllegalArgumentException) {
        return danaFail(card, "ditolak", e.message ?: "Masukan tidak valid.")
    }
    simpanDana(ada + k, card)?.let { return it }
    logApproved("perintah", "kasus_dana_buat ${k.id}", args.optString("deskripsi"), Risk.WRITE, ApprovalMode.AUTO_MINIMAL, true, "kasus dicatat", decision = "otomatis")
    val sens = DanaTertahan.samarkan(args.optString("alasan") + " " + args.optString("referensi")).adaSensitif
    val text = "Kasus ${k.id} dicatat: ${k.platform}, ${DanaTertahan.formatRupiah(k.nominal)}, ${k.jenis.label}.\n" +
        "Langkah berikutnya: ${DanaTertahan.langkahBerikut(k)}\n" +
        (if (sens) "${DanaTertahan.PERINGATAN_SENSITIF}\n" else "") +
        DanaTertahan.PERINGATAN_PENIPUAN
    return danaOk(card, k.id, text)
}

internal fun MainActivity.toolKasusDanaCatat(args: JSONObject, card: MainActivity.StepCard): String {
    danaWriteGate(card)?.let { return it }
    val ada = loadDana() ?: return danaFail(card, "berkas rusak", DANA_RUSAK)
    val id = args.optString("id").trim().uppercase()
    val k = ada.firstOrNull { it.id == id } ?: return danaFail(card, "id tidak ada", "Tidak ada kasus $id. Panggil kasus_dana_lihat untuk daftar.")
    val (baru, warn) = try {
        DanaTertahan.catat(k, args.optString("jenis_catatan"), args.optString("isi"), args.optString("status"), LocalDate.now())
    } catch (e: IllegalArgumentException) {
        return danaFail(card, "ditolak", e.message ?: "Masukan tidak valid.")
    }
    simpanDana(ada.map { if (it.id == id) baru else it }, card)?.let { return it }
    logApproved("perintah", "kasus_dana_catat $id", args.optString("deskripsi"), Risk.WRITE, ApprovalMode.AUTO_MINIMAL, true, "kasus diperbarui", decision = "otomatis")
    val text = "Kasus $id diperbarui. Status: ${baru.status.label}. Catatan: ${baru.catatan.size}.\n" +
        "Langkah berikutnya: ${DanaTertahan.langkahBerikut(baru)}" +
        if (warn.isEmpty()) "" else "\n" + warn.joinToString("\n")
    return danaOk(card, id, text)
}

internal fun MainActivity.toolKasusDanaLihat(args: JSONObject, card: MainActivity.StepCard): String {
    val ada = loadDana() ?: return danaFail(card, "berkas rusak", DANA_RUSAK)
    val hari = LocalDate.now()
    val id = args.optString("id").trim().uppercase()
    if (id.isEmpty()) {
        if (ada.isEmpty()) return danaOk(card, "kosong", "Belum ada kasus. Buat dengan kasus_dana_buat.")
        val telat = ada.count { DanaTertahan.terlambat(it, hari) }
        val text = ada.joinToString("\n") { DanaTertahan.ringkas(it, hari) } +
            if (telat > 0) "\n\n$telat kasus melewati tanggal tindak lanjut yang disarankan (${DanaTertahan.HARI_TINDAK_LANJUT} hari sejak status terakhir; saran aplikasi, bukan ketentuan hukum)." else ""
        return danaOk(card, "${ada.size} kasus", text)
    }
    val k = ada.firstOrNull { it.id == id } ?: return danaFail(card, "id tidak ada", "Tidak ada kasus $id. Panggil kasus_dana_lihat tanpa id untuk daftar.")
    val text = DanaTertahan.linimasa(k) + "\n\n" +
        (DanaTertahan.tenggat(k)?.let { "Tindak lanjut disarankan: $it" + (if (it.isBefore(hari)) " (TERLAMBAT)" else "") + " (saran aplikasi, bukan ketentuan hukum)\n" } ?: "") +
        "Langkah berikutnya: ${DanaTertahan.langkahBerikut(k)}\n\n" +
        "Jenjang regulator (nama lembaga saja; verifikasi kanal resmi lewat cari_web):\n" +
        DanaTertahan.jenjangRegulator().joinToString("\n") { "- $it" } + "\n\n" + DanaTertahan.PERINGATAN_PENIPUAN
    return danaOk(card, id, text)
}

internal fun MainActivity.toolKasusDanaDraf(args: JSONObject, card: MainActivity.StepCard): String {
    val ada = loadDana() ?: return danaFail(card, "berkas rusak", DANA_RUSAK)
    val id = args.optString("id").trim().uppercase()
    val k = ada.firstOrNull { it.id == id } ?: return danaFail(card, "id tidak ada", "Tidak ada kasus $id. Panggil kasus_dana_lihat untuk daftar.")
    val tahap = TahapDraf.parse(args.optString("tahap")) ?: return danaFail(card, "tahap tidak valid", "Tahap harus penjelasan | komplain | regulator.")
    val nama = DanaTertahan.samarkan(args.optString("nama")).teks.take(80)
    val kontak = DanaTertahan.samarkan(args.optString("kontak")).teks.take(80)
    val draf = DanaTertahan.draf(k, tahap, nama, kontak, LocalDate.now())
    try {
        atomicWriteText(File(danaDir(true), "draf_${k.id}_${tahap.kode}.txt"), draf)
    } catch (e: Exception) {
        logQuiet("DanaTertahanIntegration.kt", e)
    }
    val catatan = "Ini DRAF dari fakta yang tersimpan. Tampilkan ke pengguna, minta ia melengkapi semua penanda [ISI: ...] dan memeriksa kebenarannya. " +
        "Jangan kirim sebelum pengguna menyetujui isinya (kirim lewat email_kirim, yang punya gerbang persetujuan sendiri)." +
        if (tahap == TahapDraf.REGULATOR) " Tentukan lembaga yang sesuai dan verifikasi kanal pengaduannya di situs resmi lembaga lewat cari_web." else ""
    return danaOk(card, "draf $tahap", "$draf\n\n---\n$catatan")
}

/** Judul status satu baris untuk langkah agen. */
internal fun danaStatus(name: String): String = when (name) {
    "kasus_dana_buat" -> "Mencatat kasus dana tertahan"
    "kasus_dana_catat" -> "Memperbarui catatan kasus dana"
    "kasus_dana_lihat" -> "Membuka catatan kasus dana"
    "kasus_dana_draf" -> "Menyusun draf surat kasus dana"
    else -> "Mengurus kasus dana tertahan"
}

