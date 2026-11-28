package com.build.mythos

/**
 * Pengatur kecepatan munculnya teks balasan (efek ketik). Murni Kotlin supaya bisa diuji sebagai tes JVM.
 *
 * Dulu hanya 1 atau 2 karakter per 26 ms: jawaban 2.000 karakter butuh sekitar 26 detik walau sudah diterima penuh,
 * sehingga layar terasa tertinggal jauh dari model. Kini langkah menyesuaikan jumlah teks yang tertinggal, tetap pelan agar ketikan terlihat.
 */
internal object StreamPacing {
    /** Jeda antar langkah (ms). Makin besar makin pelan. Dengan 30 ms dan 1 karakter per langkah: sekitar 33 karakter/detik. */
    const val FRAME_MS = 30L

    /** Jumlah karakter yang ditampilkan pada satu langkah bila masih tertinggal [backlog] karakter. */
    fun step(backlog: Int): Int = when {
        backlog <= 0 -> 0
        backlog <= 60 -> 1                          // dekat dengan model: ketikan pelan, huruf demi huruf terlihat jelas
        else -> (backlog / 30).coerceIn(2, 24)      // tertinggal jauh: mengejar bertahap supaya jawaban panjang tidak berlarut
    }

    /** Titik potong [n] yang tidak memecah pasangan surrogate (emoji, aksara langka) di tengah. */
    fun safeCut(text: String, n: Int): Int {
        val c = n.coerceIn(0, text.length)
        return if (c in 1 until text.length && Character.isHighSurrogate(text[c - 1]) && Character.isLowSurrogate(text[c])) c + 1 else c
    }
}
