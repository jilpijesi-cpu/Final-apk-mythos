package com.build.mythos

/**
 * Palet berwarna: dasar netral lembut + aksen jelas per jenis aksi.
 * Semua warna aksi dipilih agar kontras di atas latar putih maupun versi pastelnya (lihat [tint]).
 * Murni Kotlin (tanpa android.*) supaya bisa diuji sebagai unit test JVM.
 */
internal object Palette {
    /** Mode gelap aktif? Diatur sekali per Activity lewat [applyTheme] sebelum tampilan dibangun. */
    @Volatile var dark = false

    /** Latar kartu / dialog / pil putih di mode terang. */
    val CARD: Int get() = if (dark) 0xFF2A2927.toInt() else 0xFFFFFFFF.toInt()

    /** Tombol gelap pekat (mis. "Chat baru"): hitam di mode terang, gading di mode gelap. */
    val STRONG: Int get() = if (dark) 0xFFECEAE5.toInt() else 0xFF1F1E1D.toInt()

    /** Garis tipis kolom input. */
    val FIELD_BORDER: Int get() = if (dark) 0xFF4A4845.toInt() else 0xFFDCDCD9.toInt()

    /** Teks petunjuk (hint) kolom input. */
    val HINT: Int get() = if (dark) 0xFF8A8882.toInt() else 0xFF2E2D2B.toInt()

    /** Warna dasar untuk campuran pastel [tint]: putih di mode terang, permukaan gelap di mode gelap. */
    val TINT_BASE: Int get() = if (dark) 0xFF242321.toInt() else 0xFFFFFFFF.toInt()

    /** Kilau teks status: dasar redup dan sorot, dibalik di mode gelap. */
    val SHIMMER_BASE: Int get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val SHIMMER_HI: Int get() = if (dark) 0xFF6F6D68.toInt() else 0xFFBDBBB5.toInt()

    // Merek
    val BRAND = 0xFFB8562F.toInt()      // oranye-tanah (tombol utama); rasio kontras 4,76:1 dengan teks putih
    val BRAND_2 = 0xFFCC6A44.toInt()    // oranye terang (pasangan gradasi)
    val BRAND_DEEP = 0xFFA6482B.toInt() // teks di atas latar pastel merek

    // Aksen semantik
    val BLUE = 0xFF3B82F6.toInt()
    val VIOLET = 0xFF8B5CF6.toInt()
    val TEAL = 0xFF0D9488.toInt()
    val GREEN = 0xFF16A34A.toInt()
    val AMBER = 0xFFF59E0B.toInt()
    val ORANGE = 0xFFF97316.toInt()
    val RED = 0xFFEF4444.toInt()
    val CYAN = 0xFF0891B2.toInt()
    val PINK = 0xFFEC4899.toInt()
    val INDIGO = 0xFF6366F1.toInt()
    val SLATE = 0xFF475569.toInt()

    // Kompatibilitas nama lama

    // Netral
    val TEXT_MAIN: Int get() = if (dark) 0xFFECEAE5.toInt() else 0xFF141413.toInt()
    val TEXT_SOFT: Int get() = if (dark) 0xFF9E9C95.toInt() else 0xFF77756F.toInt()
    val BG: Int get() = if (dark) 0xFF1A1918.toInt() else 0xFFF9F9F6.toInt()
    val SURFACE: Int get() = if (dark) 0xFF242321.toInt() else 0xFFFDFDFD.toInt()
    val BORDER: Int get() = if (dark) 0xFF3A3835.toInt() else 0xFFE5E3DE.toInt()
    val CODE_BG = 0xFF0F172A.toInt()

    // Tampilan chat gaya Gemini
    val INK: Int get() = if (dark) 0xFFECEAE5.toInt() else 0xFF141413.toInt()        // teks utama
    val INK_SOFT: Int get() = if (dark) 0xFF9E9C95.toInt() else 0xFF6B6963.toInt()   // petunjuk, teks sekunder, panah
    val ICON: Int get() = if (dark) 0xFFD6D4CE.toInt() else 0xFF353532.toInt()       // ikon di header dan kolom input
    val NAV: Int get() = if (dark) 0xFF242321.toInt() else 0xFFFDFDFD.toInt()        // bilah navigasi dan kolom input
    val BUBBLE_BORDER: Int get() = if (dark) 0xFF4A4845.toInt() else 0xFFA9A6A1.toInt() // garis tipis gelembung pengguna
    const val ORANGE_DOT = 0xFFD67658.toInt() // titik oranye "berpikir" (satu sumber untuk ClaudeDots dan Live.ORANGE)
    val CHIP: Int get() = if (dark) 0xFF33312F.toInt() else 0xFFEFEDEB.toInt()       // lingkaran tombol aksi dan pil model (abu hangat)
    val BUBBLE: Int get() = if (dark) 0xFF33312F.toInt() else 0xFFEFEDEB.toInt()     // gelembung pesan pengguna (abu hangat)
    val FIELD: Int get() = if (dark) 0xFF33312F.toInt() else 0xFFEFEDEB.toInt()      // latar kolom isian dan pil
    val DIVIDER: Int get() = if (dark) 0xFF3A3835.toInt() else 0xFFE5E3DE.toInt()    // garis pemisah halus
    val DRAWER_BG: Int get() = if (dark) 0xFF211F1E.toInt() else 0xFFF0F0EC.toInt()  // latar drawer samping
    val TILE: Int get() = if (dark) 0xFF2E2C2A.toInt() else 0xFFEDEDEA.toInt()       // petak Kamera / Foto / File di lembar tambah
    /** Biru tautan/ikon aksi di drawer: lebih gelap di mode terang, lebih cerah di mode gelap (kontras >= 4,5:1 di atas DRAWER_BG). */
    val LINK_BLUE: Int get() = if (dark) 0xFF7CA9E8.toInt() else 0xFF2B5CA8.toInt()
    // Pratinjau perubahan (diff): latar pastel + teks gelap di mode terang; latar gelap berwarna + teks pastel di mode gelap.
    val DIFF_ADD_BG: Int get() = if (dark) 0xFF14301F.toInt() else 0xFFDCFCE7.toInt()
    val DIFF_ADD_FG: Int get() = if (dark) 0xFF86EFAC.toInt() else 0xFF166534.toInt()
    val DIFF_DEL_BG: Int get() = if (dark) 0xFF3A1A1D.toInt() else 0xFFFEE2E2.toInt()
    val DIFF_DEL_FG: Int get() = if (dark) 0xFFFCA5A5.toInt() else 0xFF991B1B.toInt()

    /** Latar chat: satu warna gading hangat (rata, tanpa gradasi). */
    val SKY_COLORS: IntArray get() = IntArray(4) { BG }
    val SKY_STOPS = floatArrayOf(0f, 0.55f, 0.84f, 1f)
}

/**
 * Warna aksen langkah agen: netral untuk aksi biasa (baca, cari, tulis, ...) agar daftar aktivitas tidak ramai;
 * warna hanya untuk status: hijau = selesai, merah = hapus/berhenti, oranye = sedang berjalan.
 */
internal fun accentFor(icon: Int): Int = when (icon) {
    IconView.TRASH, IconView.STOP -> Palette.RED
    IconView.CHECK -> Palette.GREEN
    IconView.GEAR, IconView.CLOCK -> Palette.BRAND
    else -> Palette.INK_SOFT
}

/** Campur [color] dengan warna dasar tema ([Palette.TINT_BASE]; putih di mode terang): amount 0 = warna asli, 1 = dasar. Alpha tetap penuh. */
internal fun tint(color: Int, amount: Float): Int {
    val base = Palette.TINT_BASE
    fun mix(ch: Int, b: Int) = (ch + (b - ch) * amount.coerceIn(0f, 1f) + 0.5f).toInt()
    val r = mix((color shr 16) and 0xFF, (base shr 16) and 0xFF)
    val g = mix((color shr 8) and 0xFF, (base shr 8) and 0xFF)
    val b = mix(color and 0xFF, base and 0xFF)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** Warna teks yang terbaca di atas latar [bg]: putih untuk latar gelap, hampir hitam untuk latar terang. */
internal fun onColor(bg: Int): Int {
    val r = (bg shr 16) and 0xFF
    val g = (bg shr 8) and 0xFF
    val b = bg and 0xFF
    val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return if (lum > 0.62) 0xFF141413.toInt() else 0xFFFFFFFF.toInt()
}

/** Teks AI ditampilkan apa adanya: emoji dipertahankan supaya balasan terasa ramah. (Dulu emoji dibuang di sini.) */
internal fun stripIcons(s: String): String = s

private val ICON_RE = Regex("[\\u2705\\u274C\\u2753\\u26A0\\u23F3\\u231B\\uFE0F\\u200D\\x{1F300}-\\x{1FAFF}]")

// ---- Gelombang cahaya (ShimmerText): profil warna murni, dipisah supaya bisa diuji tanpa Android.

/** Campur dua warna ARGB; [f] 0 = a, 1 = b. */
internal fun mixArgb(a: Int, b: Int, f: Float): Int {
    val k = f.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val x = (a shr shift) and 0xFF
        val y = (b shr shift) and 0xFF
        return (x + (y - x) * k + 0.5f).toInt().coerceIn(0, 255)
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Jumlah titik warna sepanjang pita gelombang; makin banyak makin halus gradasinya. */
internal const val WAVE_SAMPLES = 17

/** Posisi titik warna sepanjang pita gelombang (0..1), rata. */
internal val WAVE_STOPS = FloatArray(WAVE_SAMPLES) { it / (WAVE_SAMPLES - 1f) }

private fun bell(x: Float, center: Float, width: Float): Float {
    val d = (x - center) / width
    return Math.exp(-(d * d).toDouble()).toFloat()
}

/**
 * Kekuatan sorot (0..1) di posisi [x] pada pita: inti terang tajam di 0,62, halo lebar yang lembut di sekitarnya,
 * dan gema redup di belakang (0,27). Bentuk lonceng, jadi tidak ada tepi keras.
 */
internal fun waveWeight(x: Float): Float =
    minOf(1f, bell(x, 0.62f, 0.085f) + 0.42f * bell(x, 0.50f, 0.20f) + 0.34f * bell(x, 0.27f, 0.06f))

/** Kekuatan cahaya hangat (0..1) di tepi depan pita (0,80), tepat di depan inti terang. */
internal fun waveGlowWeight(x: Float): Float {
    val edge = bell(1f, 0.80f, 0.10f)
    return maxOf(0f, (bell(x, 0.80f, 0.10f) - edge) / (1f - edge))
}

/**
 * Warna tiap titik pita: dasar di kedua ujung, lalu campuran ke [hi] menurut [waveWeight]. Bila [glow] diberikan
 * (bukan 0), tepi depan pita diberi semburat [glow] di luar inti, sehingga gelombang tampak berpendar hangat
 * sementara puncaknya tetap warna sorot murni.
 */
internal fun waveColors(base: Int, hi: Int, glow: Int = 0): IntArray = IntArray(WAVE_SAMPLES) { i ->
    val x = WAVE_STOPS[i]
    val w = waveWeight(x)
    val c = mixArgb(base, hi, w)
    if (glow == 0) c else mixArgb(c, glow, 0.6f * waveGlowWeight(x) * (1f - w))
}
