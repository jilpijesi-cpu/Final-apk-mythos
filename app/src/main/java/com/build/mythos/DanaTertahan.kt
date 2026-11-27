package com.build.mythos

import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONObject

// Pendamping kasus dana/saldo yang tertahan atau pending di aplikasi atau perusahaan (khusus Indonesia).
// Logika murni tanpa android.* (diuji di DanaTertahanTest). Sisi Android: DanaTertahanIntegration.kt.
//
// Batas fitur (sengaja): agen MENDAMPINGI, bukan menembus. Ia mencatat kasus, menyusun draf surat, menunjukkan jenjang
// eskalasi, dan mengingatkan tenggat. Ia TIDAK menerima PIN/OTP/kata sandi, TIDAK masuk ke akun siapa pun, TIDAK
// menggerakkan uang, TIDAK membuat klaim atau bukti yang tidak ada, dan TIDAK memakai cari_leakosint untuk kasus ini.
// Kanal resmi (nomor, tautan, tenggat hukum) bisa berubah, jadi TIDAK ditanam di sini: agen wajib memverifikasinya
// lewat cari_web ke situs resmi sebelum menyampaikannya.

enum class JenisKasus(val kode: String, val label: String) {
    KYC("kyc", "verifikasi identitas (KYC) belum selesai"),
    DUGAAN_FRAUD("fraud", "ditahan karena dugaan penipuan atau aktivitas mencurigakan"),
    SENGKETA("sengketa", "sengketa transaksi atau pengembalian dana"),
    AKUN_DIBEKUKAN("bekukan", "akun dibekukan atau dibatasi"),
    PENARIKAN_TERTUNDA("tunda", "penarikan atau pencairan tertunda"),
    LAINNYA("lainnya", "alasan lain atau belum jelas");

    companion object {
        fun parse(s: String): JenisKasus? {
            val k = s.trim().lowercase()
            return entries.firstOrNull { it.kode == k || it.name.lowercase() == k }
        }
    }
}

enum class StatusKasus(val kode: String, val label: String) {
    BARU("baru", "baru dicatat"),
    DIAJUKAN_CS("cs", "sudah diajukan ke layanan pelanggan"),
    TIKET_FORMAL("tiket", "pengaduan formal diajukan"),
    ESKALASI_REGULATOR("regulator", "dieskalasi ke regulator"),
    SELESAI("selesai", "dana sudah cair atau kasus selesai"),
    DITUTUP("tutup", "ditutup tanpa hasil");

    val aktif: Boolean get() = this != SELESAI && this != DITUTUP

    companion object {
        fun parse(s: String): StatusKasus? {
            val k = s.trim().lowercase()
            return entries.firstOrNull { it.kode == k || it.name.lowercase() == k }
        }
    }
}

enum class JenisCatatan(val kode: String) {
    BUKTI("bukti"), KOMUNIKASI("komunikasi"), LANGKAH("langkah");

    companion object {
        fun parse(s: String): JenisCatatan? = entries.firstOrNull { it.kode == s.trim().lowercase() }
    }
}

enum class TahapDraf(val kode: String) {
    PENJELASAN("penjelasan"), KOMPLAIN("komplain"), REGULATOR("regulator");

    companion object {
        fun parse(s: String): TahapDraf? = entries.firstOrNull { it.kode == s.trim().lowercase() }
    }
}

data class CatatanKasus(val tanggal: String, val jenis: JenisCatatan, val isi: String)

data class KasusDana(
    val id: String,
    val platform: String,
    val jenis: JenisKasus,
    val nominal: Long,
    val tanggalTahan: String,
    val referensi: String,
    val alasan: String,
    val status: StatusKasus,
    val dibuat: String,
    val statusSejak: String,
    val catatan: List<CatatanKasus>
)

/** Hasil penyamaran: teks aman + apakah ada data sensitif yang disamarkan. */
data class HasilSamar(val teks: String, val adaSensitif: Boolean)

object DanaTertahan {
    const val MAX_KASUS = 50
    const val MAX_CATATAN = 100
    const val MAX_ISI = 1000
    const val MAX_PLATFORM = 80
    const val MAX_ALASAN = 600

    /** Saran internal aplikasi (BUKAN ketentuan hukum): hari kalender sebelum tindak lanjut bila belum ada kabar. */
    const val HARI_TINDAK_LANJUT = 7

    const val PERINGATAN_SENSITIF =
        "Jangan pernah membagikan PIN, OTP, kode verifikasi, kata sandi, CVV, atau nomor kartu lengkap kepada siapa pun, " +
            "termasuk kepada saya. Petugas resmi tidak meminta data itu. Data semacam itu sudah disamarkan di catatan kasus."

    const val PERINGATAN_PENIPUAN =
        "WASPADA PENIPUAN: pihak resmi tidak pernah meminta biaya di muka, 'pajak pencairan', atau transfer ke rekening pribadi " +
            "agar dana tertahan bisa cair. Jasa 'pencairan dana', 'recovery', atau 'hacker' yang menawarkan diri hampir pasti penipuan " +
            "yang menyasar korban dana tertahan. Hubungi hanya kanal resmi yang tertera di aplikasi atau situs resmi platform."

    // ------------------------------------------------------------ penyamaran data sensitif

    private val SAMAR = "[DISAMARKAN]"

    // Nomor kartu 13-19 digit (boleh dipisah spasi atau strip)
    private val KARTU = Regex("""\b(?:\d[ -]?){12,18}\d\b""")

    // "OTP 123456", "PIN: 1234", "kode verifikasi 8841", "CVV 123", "kata sandi: abc"
    private val SENSITIF_BERLABEL = Regex(
        """(?i)\b(otp|pin|cvv|cvc|kode\s+(?:verifikasi|otp|rahasia)|password|kata\s*sandi|sandi)\b((?:\s*(?:adalah|saya|aku|=|:|-))*\s*)[^\s,;]{3,}"""
    )

    /** Samarkan PIN/OTP/kata sandi/nomor kartu dan rahasia umum (token, kunci API). Tidak pernah dikembalikan apa adanya. */
    fun samarkan(teks: String): HasilSamar {
        var out = teks
        var ada = false
        val a = SENSITIF_BERLABEL.replace(out) { m ->
            ada = true
            m.groupValues[1] + m.groupValues[2] + SAMAR
        }
        out = a
        out = KARTU.replace(out) { m ->
            val digit = m.value.filter { it.isDigit() }
            if (digit.length in 13..19 && luhnOk(digit)) {
                ada = true
                SAMAR
            } else m.value
        }
        val r = redactSecrets(out)
        if (r != out) ada = true
        return HasilSamar(r, ada)
    }

    fun luhnOk(digits: String): Boolean {
        if (digits.length !in 13..19 || digits.any { !it.isDigit() }) return false
        var sum = 0
        var alt = false
        for (i in digits.indices.reversed()) {
            var n = digits[i] - '0'
            if (alt) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alt = !alt
        }
        return sum % 10 == 0
    }

    // ------------------------------------------------------------ deteksi pola penipuan

    private val POLA_PENIPUAN: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)biaya\s+(admin|pencairan|aktivasi|unlock|buka\s+blokir|pajak)|pajak\s+pencairan|bayar\s+dulu|transfer\s+dulu|deposit\s+(dulu|untuk\s+cair)""") to
            "diminta membayar biaya atau transfer di muka agar dana cair",
        Regex("""(?i)jasa\s+(pencairan|pengembalian|recovery)|recovery\s+(dana|saldo)|hacker|peretas|\bcalo\b|orang\s+dalam""") to
            "ditawari jasa pencairan atau pihak ketiga yang mengaku bisa 'mengembalikan' dana",
        Regex("""(?i)(minta|meminta|kirim|kirimkan|berikan)\s+(kode\s+)?(otp|pin|kode\s+verifikasi|password|kata\s*sandi)""") to
            "diminta menyerahkan OTP, PIN, atau kata sandi",
        Regex("""(?i)(cs|customer\s*service|admin)\s+.{0,20}(whatsapp|wa|telegram|dm)\s+.{0,30}(pribadi|nomor\s+baru)|akun\s+(cs|resmi)\s+palsu""") to
            "dihubungi 'CS' lewat jalur pribadi (WhatsApp/Telegram/DM) yang tidak resmi",
        Regex("""(?i)klik\s+(link|tautan)\s+(ini|berikut)|isi\s+formulir\s+.{0,20}(link|tautan)""") to
            "diarahkan ke tautan atau formulir yang tidak berasal dari aplikasi atau situs resmi"
    )

    /** Daftar tanda penipuan yang cocok pada [teks]; kosong bila tidak ada. */
    fun deteksiPenipuan(teks: String): List<String> =
        POLA_PENIPUAN.filter { (re, _) -> re.containsMatchIn(teks) }.map { it.second }.distinct()

    // ------------------------------------------------------------ parsing masukan

    /** "Rp 1.500.000", "1.500.000,50", "1500000", "1,5jt" -> rupiah bulat; null bila tak valid atau <= 0. */
    fun parseRupiah(raw: String): Long? {
        var s = raw.trim().lowercase().replace("rp", "").replace(" ", "")
        if (s.isEmpty()) return null
        var mult = 1L
        when {
            s.endsWith("jt") -> { mult = 1_000_000L; s = s.removeSuffix("jt") }
            s.endsWith("juta") -> { mult = 1_000_000L; s = s.removeSuffix("juta") }
            s.endsWith("rb") -> { mult = 1_000L; s = s.removeSuffix("rb") }
            s.endsWith("ribu") -> { mult = 1_000L; s = s.removeSuffix("ribu") }
        }
        if (s.isEmpty()) return null
        // Format Indonesia: titik = ribuan, koma = desimal. Singkatan (jt/rb) juga menerima titik desimal ("1.5jt").
        val body = if (mult > 1L) {
            if (s.count { it == ',' } > 1) return null
            val m = if (Regex("""^\d{1,3}(\.\d{3})+(,\d+)?$""").matches(s)) {
                s.replace(".", "")
            } else {
                if (s.contains(',') && s.contains('.')) return null
                s
            }
            m.replace(',', '.')
        } else {
            val parts = s.split(",")
            if (parts.size > 2) return null
            val whole = parts[0].replace(".", "")
            if (parts.size == 2) {
                if (parts[1].isEmpty() || parts[1].any { !it.isDigit() }) return null
                whole + "." + parts[1]
            } else whole
        }
        if (body.isEmpty() || body.any { !it.isDigit() && it != '.' } || body.count { it == '.' } > 1) return null
        val d = body.toBigDecimalOrNull() ?: return null
        val v = d.multiply(java.math.BigDecimal(mult)).setScale(0, java.math.RoundingMode.HALF_UP)
        if (v.signum() <= 0 || v > java.math.BigDecimal(1_000_000_000_000L)) return null
        return v.toLong()
    }

    fun formatRupiah(n: Long): String {
        val s = n.toString().reversed().chunked(3).joinToString(".").reversed()
        return "Rp$s"
    }

    /** Tanggal ISO (yyyy-MM-dd) atau dd/MM/yyyy; null bila tak valid atau di masa depan relatif [hariIni]. */
    fun parseTanggal(raw: String, hariIni: LocalDate): String? {
        val s = raw.trim()
        val d = try {
            if (Regex("""\d{4}-\d{2}-\d{2}""").matches(s)) LocalDate.parse(s)
            else {
                val m = Regex("""(\d{1,2})[/-](\d{1,2})[/-](\d{4})""").matchEntire(s) ?: return null
                LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
            }
        } catch (e: DateTimeParseException) {
            return null
        } catch (e: java.time.DateTimeException) {
            return null
        }
        return if (d.isAfter(hariIni) || d.year < 2000) null else d.toString()
    }

    // ------------------------------------------------------------ operasi kasus (murni)

    private fun bersih(s: String, maks: Int): String = samarkan(s.trim().replace(Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F]"""), "")).teks.take(maks)

    fun idBaru(ada: List<KasusDana>): String {
        val maks = ada.mapNotNull { it.id.removePrefix("D").toIntOrNull() }.maxOrNull() ?: 0
        return "D" + (maks + 1).toString().padStart(3, '0')
    }

    /** Buat kasus baru. Melempar IllegalArgumentException berisi alasan penolakan. */
    fun buat(
        platform: String, jenis: String, nominal: String, tanggalTahan: String, referensi: String, alasan: String,
        ada: List<KasusDana>, hariIni: LocalDate
    ): KasusDana {
        val p = bersih(platform, MAX_PLATFORM)
        require(p.length >= 2) { "Nama platform atau perusahaan wajib diisi." }
        val j = JenisKasus.parse(jenis)
        require(j != null) { "Jenis kasus tidak dikenal. Pilih: " + JenisKasus.entries.joinToString(" | ") { it.kode } + "." }
        val n = parseRupiah(nominal)
        require(n != null) { "Nominal tidak valid. Contoh: 1500000, Rp1.500.000, atau 1,5jt." }
        val t = parseTanggal(tanggalTahan, hariIni)
        require(t != null) { "Tanggal penahanan tidak valid atau di masa depan. Pakai yyyy-MM-dd atau dd/MM/yyyy." }
        require(ada.size < MAX_KASUS) { "Sudah $MAX_KASUS kasus tersimpan; tutup atau hapus yang lama dulu." }
        val hari = hariIni.toString()
        return KasusDana(
            id = idBaru(ada), platform = p, jenis = j, nominal = n, tanggalTahan = t,
            referensi = bersih(referensi, 80), alasan = bersih(alasan, MAX_ALASAN),
            status = StatusKasus.BARU, dibuat = hari, statusSejak = hari, catatan = emptyList()
        )
    }

    /** Tambah catatan dan/atau ubah status. Mengembalikan kasus baru + peringatan yang perlu disampaikan ke pengguna. */
    fun catat(k: KasusDana, jenis: String, isi: String, status: String, hariIni: LocalDate): Pair<KasusDana, List<String>> {
        val warn = ArrayList<String>()
        var out = k
        val isiTrim = isi.trim()
        if (isiTrim.isNotEmpty()) {
            val jc = JenisCatatan.parse(jenis)
            require(jc != null) { "Jenis catatan tidak dikenal. Pilih: bukti | komunikasi | langkah." }
            require(k.catatan.size < MAX_CATATAN) { "Catatan kasus sudah $MAX_CATATAN; tutup kasus ini atau ringkas." }
            val h = samarkan(isiTrim)
            if (h.adaSensitif) warn += PERINGATAN_SENSITIF
            warn += deteksiPenipuan(isiTrim).map { "Tanda penipuan: $it." }
            out = out.copy(catatan = out.catatan + CatatanKasus(hariIni.toString(), jc, bersih(isiTrim, MAX_ISI)))
        }
        if (status.isNotBlank()) {
            val s = StatusKasus.parse(status)
            require(s != null) { "Status tidak dikenal. Pilih: " + StatusKasus.entries.joinToString(" | ") { it.kode } + "." }
            if (s != out.status) out = out.copy(status = s, statusSejak = hariIni.toString())
        }
        require(isiTrim.isNotEmpty() || status.isNotBlank()) { "Isi catatan atau status wajib salah satu." }
        if (warn.any { it.startsWith("Tanda penipuan") }) warn += PERINGATAN_PENIPUAN
        return out to warn.distinct()
    }

    // ------------------------------------------------------------ tenggat dan langkah berikutnya

    /** Tanggal tindak lanjut yang disarankan, atau null bila kasus sudah selesai/ditutup. */
    fun tenggat(k: KasusDana): LocalDate? =
        if (!k.status.aktif) null else LocalDate.parse(k.statusSejak).plusDays(HARI_TINDAK_LANJUT.toLong())

    fun terlambat(k: KasusDana, hariIni: LocalDate): Boolean = tenggat(k)?.isBefore(hariIni) == true

    /** Langkah berikutnya sesuai status. Kanal dan tenggat resmi WAJIB diverifikasi lewat cari_web (bisa berubah). */
    fun langkahBerikut(k: KasusDana): String = when (k.status) {
        StatusKasus.BARU ->
            "Hubungi layanan pelanggan resmi platform lewat aplikasi atau situs resminya, simpan nomor tiket, lalu catat sebagai komunikasi. " +
                "Minta alasan penahanan tertulis dan dokumen yang diperlukan. Draf: kasus_dana_draf tahap=penjelasan."
        StatusKasus.DIAJUKAN_CS ->
            "Bila belum ada jawaban jelas, ajukan pengaduan formal (email atau formulir pengaduan resmi) dengan nomor tiket sebelumnya. " +
                "Draf: kasus_dana_draf tahap=komplain."
        StatusKasus.TIKET_FORMAL ->
            "Bila tetap tidak ada penyelesaian, eskalasi ke otoritas yang sesuai jenis penyedia (lihat jenjangRegulator). " +
                "Lampirkan bukti dan riwayat komunikasi. Draf: kasus_dana_draf tahap=regulator."
        StatusKasus.ESKALASI_REGULATOR ->
            "Pantau balasan regulator dan platform; catat setiap balasan sebagai komunikasi. Pertimbangkan konsultasi lembaga bantuan hukum bila nominal besar."
        StatusKasus.SELESAI, StatusKasus.DITUTUP -> "Tidak ada langkah lagi. Simpan arsip kasus."
    }

    /**
     * Jenjang regulator menurut jenis penyedia. Hanya NAMA lembaga; nomor, tautan, dan tata cara WAJIB dicari ulang lewat
     * cari_web dari situs resmi lembaga itu (kanal sering berubah), jangan dari ingatan.
     */
    fun jenjangRegulator(): List<String> = listOf(
        "Dompet digital, uang elektronik, dan penyelenggara pembayaran: Bank Indonesia (layanan konsumen BI)",
        "Bank, fintech pinjaman/pendanaan, asuransi, sekuritas, dan sektor jasa keuangan lain: OJK (layanan konsumen OJK)",
        "Aset kripto: OJK (pengawasan beralih dari Bappebti; verifikasi status terkini)",
        "Marketplace dan toko online (dana pembeli/penjual): platform dulu, lalu direktorat perlindungan konsumen Kementerian Perdagangan dan BPKN",
        "Layanan publik yang tidak ditanggapi: Ombudsman RI",
        "Sengketa konsumen: BPSK atau lembaga alternatif penyelesaian sengketa sektor jasa keuangan (LAPS SJK), bila relevan"
    )

    // ------------------------------------------------------------ tampilan

    fun ringkas(k: KasusDana, hariIni: LocalDate): String {
        val t = tenggat(k)
        val tg = when {
            t == null -> ""
            t.isBefore(hariIni) -> " | TINDAK LANJUT TERLAMBAT sejak $t"
            else -> " | tindak lanjut $t"
        }
        return "${k.id} | ${k.platform} | ${formatRupiah(k.nominal)} | ${k.status.label}$tg"
    }

    fun linimasa(k: KasusDana): String = buildString {
        appendLine("Kasus ${k.id}: ${k.platform}")
        appendLine("Nominal: ${formatRupiah(k.nominal)} | Jenis: ${k.jenis.label}")
        appendLine("Mulai ditahan: ${k.tanggalTahan} | Dicatat: ${k.dibuat}")
        if (k.referensi.isNotBlank()) appendLine("Referensi/tiket: ${k.referensi}")
        if (k.alasan.isNotBlank()) appendLine("Alasan dari pihak platform (menurut pengguna): ${k.alasan}")
        appendLine("Status: ${k.status.label} (sejak ${k.statusSejak})")
        if (k.catatan.isEmpty()) appendLine("Belum ada catatan.")
        else for (c in k.catatan) appendLine("- ${c.tanggal} [${c.jenis.kode}] ${c.isi}")
    }.trimEnd()

    // ------------------------------------------------------------ draf surat

    private fun nilai(s: String, label: String): String = if (s.isBlank()) "[ISI: $label]" else s

    /**
     * Draf surat berbahasa Indonesia dari FAKTA yang tersimpan saja. Fakta yang belum ada diberi penanda [ISI: ...];
     * tidak ada yang dikarang. Pengguna wajib memeriksa sebelum mengirim.
     */
    fun draf(k: KasusDana, tahap: TahapDraf, namaPengguna: String, kontak: String, hariIni: LocalDate): String {
        val nama = nilai(namaPengguna.trim(), "nama lengkap sesuai akun")
        val hub = nilai(kontak.trim(), "email atau nomor HP terdaftar")
        val ref = if (k.referensi.isBlank()) "[ISI: nomor tiket atau referensi bila ada]" else k.referensi
        val komunikasi = k.catatan.filter { it.jenis == JenisCatatan.KOMUNIKASI }
        val bukti = k.catatan.filter { it.jenis == JenisCatatan.BUKTI }
        val riwayat = if (komunikasi.isEmpty()) "Belum ada balasan tertulis yang saya terima." else
            komunikasi.joinToString("\n") { "- ${it.tanggal}: ${it.isi}" }
        val lampiran = if (bukti.isEmpty()) "- [ISI: daftar bukti yang dilampirkan, mis. tangkapan layar saldo dan riwayat transaksi]"
        else bukti.joinToString("\n") { "- ${it.isi}" }
        val pokok = "Dana saya sebesar ${formatRupiah(k.nominal)} di ${k.platform} tertahan sejak ${k.tanggalTahan} " +
            "(kategori: ${k.jenis.label})." + if (k.alasan.isNotBlank()) " Alasan yang disampaikan kepada saya: ${k.alasan}." else
            " Hingga kini saya belum menerima alasan yang jelas dan tertulis."

        return when (tahap) {
            TahapDraf.PENJELASAN -> """
Perihal: Permintaan penjelasan atas dana yang tertahan (Ref: $ref)

Yth. Tim Layanan Pelanggan ${k.platform},

Saya $nama, pengguna terdaftar ($hub). $pokok

Mohon dengan hormat:
1. Penjelasan tertulis mengenai alasan penahanan dana tersebut.
2. Daftar dokumen atau verifikasi yang masih saya perlu penuhi, bila ada.
3. Perkiraan waktu penyelesaian dan nomor tiket untuk tindak lanjut.

Saya siap melengkapi data melalui kanal resmi. Terima kasih atas bantuannya.

Hormat saya,
$nama
${hariIni}
""".trimIndent()

            TahapDraf.KOMPLAIN -> """
Perihal: Pengaduan resmi atas dana yang tertahan (Ref: $ref)

Yth. Unit Penanganan Pengaduan ${k.platform},

Saya $nama ($hub) mengajukan pengaduan resmi. $pokok

Riwayat komunikasi:
$riwayat

Permintaan saya:
1. Penyelesaian atau pencairan dana, atau penjelasan tertulis beserta dasar penahanannya.
2. Tanggapan tertulis dalam waktu yang wajar. Bila belum ada penyelesaian, saya akan melanjutkan ke otoritas pengawas yang berwenang.

Bukti terlampir:
$lampiran

Hormat saya,
$nama
${hariIni}
""".trimIndent()

            TahapDraf.REGULATOR -> """
Perihal: Pengaduan konsumen: dana tertahan di ${k.platform} (Ref: $ref)

Kepada Yth. [ISI: nama lembaga pengawas yang sesuai jenis penyedia, setelah diverifikasi dari situs resminya],

Saya $nama ($hub) mengadukan ${k.platform} atas dana saya yang tertahan. $pokok

Upaya penyelesaian yang telah saya tempuh:
$riwayat

Belum ada penyelesaian yang memadai. Mohon bantuan mediasi atau tindak lanjut sesuai kewenangan.

Lampiran:
$lampiran

Saya menyatakan data di atas sesuai fakta yang saya alami.

Hormat saya,
$nama
${hariIni}
""".trimIndent()
        }
    }

    // ------------------------------------------------------------ serialisasi

    fun toJson(list: List<KasusDana>): String {
        val a = JSONArray()
        for (k in list) {
            val cs = JSONArray()
            for (c in k.catatan) cs.put(JSONObject().put("t", c.tanggal).put("j", c.jenis.kode).put("i", c.isi))
            a.put(
                JSONObject().put("id", k.id).put("platform", k.platform).put("jenis", k.jenis.kode).put("nominal", k.nominal)
                    .put("tahan", k.tanggalTahan).put("ref", k.referensi).put("alasan", k.alasan).put("status", k.status.kode)
                    .put("dibuat", k.dibuat).put("sejak", k.statusSejak).put("catatan", cs)
            )
        }
        return a.toString()
    }

    fun parse(s: String): List<KasusDana> = try {
        val a = JSONArray(if (s.isBlank()) "[]" else s)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val jenis = JenisKasus.parse(o.optString("jenis")) ?: return@mapNotNull null
            val status = StatusKasus.parse(o.optString("status")) ?: return@mapNotNull null
            val id = o.optString("id")
            if (!Regex("""D\d{3,}""").matches(id)) return@mapNotNull null
            val dibuat = o.optString("dibuat")
            val sejak = o.optString("sejak").ifBlank { dibuat }
            if (runCatching { LocalDate.parse(sejak) }.isFailure || runCatching { LocalDate.parse(dibuat) }.isFailure) return@mapNotNull null
            val cs = o.optJSONArray("catatan")
            val catatan = if (cs == null) emptyList() else (0 until cs.length()).mapNotNull { j ->
                val c = cs.optJSONObject(j) ?: return@mapNotNull null
                val jc = JenisCatatan.parse(c.optString("j")) ?: return@mapNotNull null
                CatatanKasus(c.optString("t"), jc, c.optString("i"))
            }
            KasusDana(
                id, o.optString("platform"), jenis, o.optLong("nominal"), o.optString("tahan"), o.optString("ref"),
                o.optString("alasan"), status, dibuat, sejak, catatan
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
