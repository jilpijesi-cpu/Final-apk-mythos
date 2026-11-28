package com.build.mythos

/**
 * Skala ukuran teks (sp) tunggal untuk seluruh aplikasi. Pakai konstanta ini, jangan menulis angka langsung,
 * supaya tampilan konsisten (tidak ada selisih setengah sp yang tak terlihat tapi membuat tampilan terasa berantakan).
 * Ada tes yang menggagalkan build bila `textSize = <angka>` muncul lagi di kode.
 */
internal object Typo {
    const val CAPTION = 12f   // keterangan, chip, catatan kecil
    const val SMALL = 13f     // teks bantu, label baris
    const val BODY_S = 14f    // isi padat: pil model, sel tabel
    const val BODY = 15f      // isi utama, judul baris
    const val BODY_L = 16f    // isi nyaman, dialog
    const val TITLE_S = 17f   // baris status live
    const val TITLE = 18f     // judul bagian
    const val DISPLAY = 20f   // judul besar, tanda panah
    const val GLYPH = 22f     // simbol/emoji besar
    const val WORDMARK = 27f  // nama aplikasi di drawer
    const val NAV_GLYPH = 30f // tanda kembali "‹"
    const val HERO = 32f      // sapaan layar kosong

    /** Semua ukuran, menaik; dipakai tes untuk menjaga skala tetap rapi. */
    val ALL = floatArrayOf(CAPTION, SMALL, BODY_S, BODY, BODY_L, TITLE_S, TITLE, DISPLAY, GLYPH, WORDMARK, NAV_GLYPH, HERO)
}
