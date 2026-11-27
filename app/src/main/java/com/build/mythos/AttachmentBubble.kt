package com.build.mythos

// Gelembung pesan pengguna yang membawa lampiran: tampilkan NAMA FILE sebagai chip, bukan kalimat otomatis dan path panjang
// ("Periksa lampiran... File terlampir di /data/data/...."). Pesan ke model TIDAK berubah (path tetap ada di riwayat, dipakai alat);
// yang diubah hanya tampilan di layar, baik saat mengirim maupun saat riwayat dibuka kembali.

internal object AttachmentBubble {
    const val DEFAULT_TEXT = "Periksa lampiran dan gunakan sebagai konteks tugas."
    private val LINE = Regex("^(File|Gambar) terlampir di \"(.+)\"$")

    class Split(val text: String, val files: List<String>)

    /** Pisahkan teks tampilan dari baris path lampiran. Gambar sudah tampil sebagai pratinjau, jadi tidak dibuat chip. */
    fun split(text: String): Split {
        val keep = ArrayList<String>()
        val files = ArrayList<String>()
        var found = false
        for (line in text.lines()) {
            val m = LINE.matchEntire(line.trim())
            if (m == null) { keep.add(line); continue }
            found = true
            if (m.groupValues[1] == "File") files.add(m.groupValues[2].substringAfterLast('/'))
        }
        if (!found) return Split(text, emptyList())
        var t = keep.joinToString("\n").trim()
        if (t == DEFAULT_TEXT) t = ""
        return Split(t, files.distinct())
    }
}
