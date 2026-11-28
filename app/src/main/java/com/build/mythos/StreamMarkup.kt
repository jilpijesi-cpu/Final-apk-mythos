package com.build.mythos

// Penyamaran tanda Markdown yang BELUM ditutup selama teks mengalir. Murni Kotlin supaya bisa diuji sebagai tes JVM.
//
// Tanpa ini, "**Hasil" tampil dengan tanda bintang mentah lalu tiba-tiba berubah tebal begitu penutupnya datang,
// dan "[storage.py](/data/da" tampil sebagai kode tautan setengah jadi. Aplikasi Claude menyembunyikan penanda
// yang belum lengkap sehingga teks terlihat bersih sejak huruf pertama.

private val OPEN_LINK = Regex("""\[([^\]\n]*)\]\([^)\s]*$""")

private fun countOf(s: String, token: String): Int {
    var n = 0
    var i = s.indexOf(token)
    while (i >= 0) { n++; i = s.indexOf(token, i + token.length) }
    return n
}

/** Buang penanda yang belum berpasangan pada BARIS TERAKHIR [text]; baris sebelumnya tidak disentuh. */
internal fun hideOpenMarkup(text: String): String {
    val nl = text.lastIndexOf('\n')
    val head = text.substring(0, nl + 1)
    var line = text.substring(nl + 1)
    if (line.isEmpty()) return text
    // Tautan setengah jadi: tampilkan hanya teksnya.
    line = OPEN_LINK.replace(line) { it.groupValues[1] }
    // Tebal yang belum ditutup.
    if (countOf(line, "**") % 2 == 1) {
        val i = line.lastIndexOf("**")
        line = line.removeRange(i, i + 2)
    }
    // Kode inline yang belum ditutup.
    if (countOf(line, "`") % 2 == 1) {
        val i = line.lastIndexOf('`')
        line = line.removeRange(i, i + 1)
    }
    return head + line
}
