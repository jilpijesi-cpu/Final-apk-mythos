package com.build.mythos

import org.json.JSONArray
import org.json.JSONObject

// Deklarasi alat bawaan (skema function-call). Dipecah dari MainActivity.kt: isi tidak berubah.
// Hanya deklarasi alat aplikasi yang dijembatani ke mesin cc (CcBridge.kt: konektor, dana tertahan, berangkas).
// Alat file/shell/build/web/gambar milik agen API ada di paket cc; deklarasi jalur lama (run_command, write_file, build_apk, dst.) sudah dihapus.

/** Satu deklarasi alat: semua parameter bertipe string dan wajib. */
internal fun decl(name: String, desc: String, vararg params: Pair<String, String>): JSONObject =
    declOpt(name, desc, emptySet(), *params)

/** Seperti [decl], tetapi parameter yang namanya ada di [optional] tidak masuk daftar "required". */
internal fun declOpt(name: String, desc: String, optional: Set<String>, vararg params: Pair<String, String>): JSONObject {
    val props = JSONObject()
    val req = JSONArray()
    for ((n, d) in params) {
        props.put(n, JSONObject().put("type", "string").put("description", d))
        if (n !in optional) req.put(n)
    }
    return JSONObject().put("name", name).put("description", desc)
        .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", req))
}

/** Daftar deklarasi alat aplikasi untuk jembatan cc. [desk] = petunjuk isi parameter "deskripsi" yang dipakai bersama oleh tiap alat. */
internal fun buildBaseTools(desk: String): JSONArray =
    JSONArray().put(
        JSONObject().put(
            "functionDeclarations",
            JSONArray()
                .put(
                    decl(
                        "berangkas_baca",
                        "Baca BERANGKAS aplikasi (hanya baca; isinya ikut APK dan berganti sendiri saat aplikasi di-upgrade): aturan kerja, skrip, panduan, templat. jalur kosong atau . = daftar isi; folder = daftar isinya; berkas = isinya. Mulai dari INDEKS.md dan ATURAN.md; buka hanya yang perlu.",
                        "deskripsi" to desk, "jalur" to "Jalur relatif di dalam berangkas, mis. INDEKS.md atau skrip/kita-cek.sh; kosongkan untuk daftar isi"
                    )
                )
                .put(
                    decl(
                        "berangkas_salin",
                        "Salin berkas atau folder dari BERANGKAS ke Termux (~/.mythos/berangkas) supaya bisa dijalankan atau disalin lagi. Salinan dibuat ulang dari aplikasi setiap dipanggil (tidak pernah basi); sumbernya tidak bisa diubah. Tidak tersedia di mode hanya baca.",
                        "deskripsi" to desk, "sumber" to "Jalur relatif di dalam berangkas (berkas atau folder), mis. skrip/kita-cek.sh; kosongkan untuk menyalin semuanya"
                    )
                )
                .also { arr -> for (d in danaToolDecls(desk)) arr.put(d) } // pendamping dana tertahan (DanaTertahanIntegration.kt)
        )
    )
