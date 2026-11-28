package com.build.mythos

import org.json.JSONObject

// Sisi Android dari berangkas: alat berangkas_baca dan berangkas_salin (ekstensi MainActivity).
// Logika murni ada di Berangkas.kt (teruji). Isi berangkas: app/src/main/assets/berangkas/ (ikut APK, hanya baca).
// Tidak ada alat yang menulis ke berangkas; berangkas_salin hanya menulis salinan sementara di Termux.

private const val BAD_PATH =
    "Jalur tidak valid: pakai jalur relatif di dalam berangkas (huruf, angka, _ . / -), tanpa '..' dan tanpa awalan /. Kosongkan atau isi . untuk daftar isi."

private fun MainActivity.berangkasFail(card: MainActivity.StepCard, note: String, msg: String): String {
    sawFail = true
    card.finish(false, note, msg, StepState.FAIL)
    return "exit=1 $msg"
}

private fun MainActivity.berangkasSums(): Map<String, String>? = try {
    Berangkas.parseSums(
        assets.open("${Berangkas.ASSET_ROOT}/${Berangkas.SUMS_FILE}").bufferedReader(Charsets.UTF_8).use { it.readText() }
    ).takeIf { it.isNotEmpty() }
} catch (e: Exception) {
    null
}

private fun MainActivity.berangkasBytes(path: String): ByteArray =
    assets.open("${Berangkas.ASSET_ROOT}/$path").use { it.readBytes() }

private const val NO_VAULT =
    "Berangkas tidak ditemukan atau SHA256SUMS kosong di aplikasi ini. Pasang ulang aplikasi; jangan menebak isinya."

/** berangkas_baca(deskripsi, jalur): daftar isi (folder) atau isi berkas. Membaca langsung dari APK, tanpa Termux. */
internal fun MainActivity.toolBerangkasBaca(args: JSONObject, card: MainActivity.StepCard): String {
    val path = Berangkas.safePath(args.optString("jalur")) ?: return berangkasFail(card, "jalur tidak valid", BAD_PATH)
    val sums = berangkasSums() ?: return berangkasFail(card, "berangkas tidak ada", NO_VAULT)
    val all = sums.keys
    if (Berangkas.filesUnder(all, path).isEmpty()) {
        val top = Berangkas.listDir(all, "").joinToString(", ")
        return berangkasFail(card, "tidak ditemukan", "Tidak ada \"$path\" di berangkas. Isi akar: $top. Baca INDEKS.md untuk daftar lengkap.")
    }
    if (path !in all) { // folder (atau akar): tampilkan daftar isi
        val names = Berangkas.listDir(all, path)
        val text = "Isi berangkas/${if (path.isEmpty()) "" else path}:\n" + names.joinToString("\n") { "- $it" } +
            "\n\nHanya baca. Salin ke Termux dengan berangkas_salin bila perlu dijalankan."
        card.finish(true, "${names.size} entri", text)
        return "exit=0\n$text"
    }
    val bytes = try {
        berangkasBytes(path)
    } catch (e: Exception) {
        return berangkasFail(card, "gagal dibaca", "Berkas $path terdaftar tetapi tidak terbaca dari aplikasi.")
    }
    Berangkas.verify(path, bytes, sums)?.let { return berangkasFail(card, "integritas gagal", it) }
    var text = String(bytes, Charsets.UTF_8)
    val cut = text.length > Berangkas.MAX_READ_CHARS
    if (cut) text = text.take(Berangkas.MAX_READ_CHARS) + "\n... (dipotong; berkas lebih panjang dari ${Berangkas.MAX_READ_CHARS} karakter, salin dengan berangkas_salin untuk membaca utuh)"
    card.finish(true, path, text.take(1500))
    return "exit=0\n$text"
}

/** Salin berangkas langsung ke folder proyek agen API (tanpa menyentuh Termux, menjaga isolasi AppWorld). */
internal fun MainActivity.toolBerangkasSalinInApp(args: JSONObject, card: MainActivity.StepCard, projectRoot: java.io.File): String {
    if (readOnlyMode()) {
        card.finish(false, "mode hanya baca", state = StepState.DENIED)
        return "Ditolak: mode hanya baca aktif. berangkas_salin menulis salinan berkas."
    }
    val path = Berangkas.safePath(args.optString("sumber")) ?: return berangkasFail(card, "jalur tidak valid", BAD_PATH)
    val sums = berangkasSums() ?: return berangkasFail(card, "berangkas tidak ada", NO_VAULT)
    val files = Berangkas.filesUnder(sums.keys, path)
    if (files.isEmpty()) return berangkasFail(card, "tidak ditemukan", "Tidak ada \"$path\" di berangkas. Gunakan berangkas_baca untuk melihat daftar isi.")
    val destBase = java.io.File(projectRoot, Berangkas.DEST_DIR).canonicalFile
    var total = 0
    var count = 0
    for (f in files) {
        val b = try { berangkasBytes(f) } catch (e: Exception) {
            return berangkasFail(card, "gagal dibaca", "Berkas $f terdaftar tetapi tidak terbaca dari aplikasi.")
        }
        Berangkas.verify(f, b, sums)?.let { return berangkasFail(card, "integritas gagal", it) }
        total += b.size
        if (total > Berangkas.MAX_COPY_BYTES) {
            return berangkasFail(card, "terlalu besar", "Salinan melebihi ${Berangkas.MAX_COPY_BYTES / 1000} KB. Salin sebagian saja.")
        }
        val out = java.io.File(destBase, f).canonicalFile
        if (!out.path.startsWith(destBase.path + java.io.File.separator)) {
            return berangkasFail(card, "jalur tidak aman", BAD_PATH)
        }
        out.parentFile?.mkdirs()
        out.writeBytes(b)
        count++
    }
    val relDest = Berangkas.DEST_DIR + if (path.isEmpty()) "" else "/$path"
    val text = "Disalin $count berkas ke $relDest ($total byte) di dalam folder proyek."
    card.finish(true, "$count berkas", text)
    return "exit=0\n$text"
}

/** berangkas_salin(deskripsi, sumber): tulis ulang salinan [sumber] ke $HOME/.mythos/berangkas di Termux (atau sandbox bila aktif). */
internal fun MainActivity.toolBerangkasSalin(args: JSONObject, card: MainActivity.StepCard): String {
    if (readOnlyMode()) {
        card.finish(false, "mode hanya baca", state = StepState.DENIED)
        return "Ditolak: mode hanya baca aktif. berangkas_salin menulis salinan di Termux."
    }
    val path = Berangkas.safePath(args.optString("sumber")) ?: return berangkasFail(card, "jalur tidak valid", BAD_PATH)
    val sums = berangkasSums() ?: return berangkasFail(card, "berangkas tidak ada", NO_VAULT)
    val files = Berangkas.filesUnder(sums.keys, path)
    if (files.isEmpty()) return berangkasFail(card, "tidak ditemukan", "Tidak ada \"$path\" di berangkas. Gunakan berangkas_baca untuk melihat daftar isi.")
    val pairs = ArrayList<Pair<String, ByteArray>>()
    var total = 0
    for (f in files) {
        val b = try {
            berangkasBytes(f)
        } catch (e: Exception) {
            return berangkasFail(card, "gagal dibaca", "Berkas $f terdaftar tetapi tidak terbaca dari aplikasi.")
        }
        Berangkas.verify(f, b, sums)?.let { return berangkasFail(card, "integritas gagal", it) }
        total += b.size
        if (total > Berangkas.MAX_COPY_BYTES) {
            return berangkasFail(card, "terlalu besar", "Salinan melebihi ${Berangkas.MAX_COPY_BYTES / 1000} KB. Salin sebagian saja (sebutkan folder atau berkas).")
        }
        pairs.add(f to b)
    }
    val cmd = Berangkas.copyCommand(pairs, path)
    val out = if (sandboxEnabled()) runInSandbox(cmd, false, null) else termux(cmd, limit = 4000, timeoutSec = 60)
    if (!Berangkas.copySucceeded(out)) {
        return berangkasFail(card, "salin gagal", "Penyalinan ke Termux gagal.\n" + out.take(1200))
    }
    val dest = "~/${Berangkas.DEST_DIR}" + if (path.isEmpty()) "" else "/$path"
    val text = "Disalin ${pairs.size} berkas ke $dest (${total} byte). " +
        "Ini salinan sementara yang dibuat ulang dari aplikasi setiap berangkas_salin dipanggil. " +
        "Jalankan atau ubah salinannya; sumber di aplikasi tetap hanya-baca." +
        if (path.endsWith(".sh")) " Jalankan: bash $dest" else ""
    card.finish(true, "${pairs.size} berkas", text)
    return "exit=0\n$text"
}

/**
 * Salin ulang SELURUH berangkas ke Termux bila versionCode aplikasi berubah sejak salinan terakhir yang berhasil.
 * Dipanggil di awal tugas (thread latar). Diam-diam gagal (mengembalikan null, penanda tidak dimajukan) bila mode hanya baca,
 * berangkas rusak, atau Termux tidak menjawab; akan dicoba lagi di tugas berikutnya.
 * Hasil: catatan untuk agen bila ini salinan ulang karena naik versi; null selain itu.
 */
internal fun MainActivity.syncBerangkasIfNeeded(): String? {
    val inst = installedVersion() ?: return null
    val last = prefs.getLong(SourceSync.PREF_SYNCED, 0L)
    if (!SourceSync.needsCopy(inst, last)) return null
    if (readOnlyMode()) return null
    val sums = berangkasSums() ?: return null
    val pairs = ArrayList<Pair<String, ByteArray>>()
    var total = 0
    for (f in Berangkas.filesUnder(sums.keys, "")) {
        val b = try {
            berangkasBytes(f)
        } catch (e: Exception) {
            return null
        }
        if (Berangkas.verify(f, b, sums) != null) return null
        total += b.size
        if (total > Berangkas.MAX_COPY_BYTES) return null
        pairs.add(f to b)
    }
    val cmd = Berangkas.copyCommand(pairs, "")
    val out = if (sandboxEnabled()) runInSandbox(cmd, false, null) else termux(cmd, limit = 4000, timeoutSec = 60)
    if (!Berangkas.copySucceeded(out)) return null
    prefs.edit().putLong(SourceSync.PREF_SYNCED, inst.code).apply()
    return SourceSync.upgradeNote(inst, last)
}
