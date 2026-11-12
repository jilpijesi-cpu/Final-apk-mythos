package com.mythos.app.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.mythos.app.R

/**
 * Satu pilihan "Gaya font". [family] dibuat malas (lazy) supaya pengujian JVM yang hanya memeriksa kunci tidak menyentuh API Android.
 */
class FontOption(val key: String, val label: String, make: () -> FontFamily) {
    val family: FontFamily by lazy(make)
}

@OptIn(ExperimentalTextApi::class)
private fun device(name: String): FontFamily =
    FontFamily(listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map {
        Font(DeviceFontFamilyName(name), it)
    })

/** Inter (lisensi OFL) disertakan di res/font: satu keluarga font untuk seluruh aplikasi agar tulisan tidak bercampur. */
private fun inter(): FontFamily = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * Gaya font aplikasi (Pengaturan > Tampilan > Gaya font). Dibaca dari state Compose lewat [MythosSans], jadi seluruh layar
 * berganti seketika tanpa memulai ulang aplikasi. Semuanya memakai font bawaan Android (tanpa berkas font tambahan).
 */
object FontTheme {
    val options: List<FontOption> = listOf(
        FontOption("default", "Default") { inter() },
        FontOption("sistem", "Sistem") { FontFamily.Default },
        FontOption("android", "Android (Roboto)") { FontFamily.SansSerif },
        FontOption("ringkas", "Ringkas") { device("sans-serif-condensed") },
        FontOption("tipis", "Tipis") { device("sans-serif-light") },
        FontOption("tegas", "Tegas") { device("sans-serif-medium") },
        FontOption("serif", "Serif (klasik)") { FontFamily.Serif },
        FontOption("santai", "Santai") { device("casual") },
        FontOption("tulisan", "Tulisan tangan") { FontFamily.Cursive },
        FontOption("mesin_tik", "Mesin tik (Mono)") { FontFamily.Monospace },
    )

    private val current = androidx.compose.runtime.mutableStateOf(options[0])

    val key: String get() = current.value.key
    val label: String get() = current.value.label
    val family: FontFamily get() = current.value.family

    /** Keluarga untuk teks balasan AI/judul: serif klasik hanya pada gaya Default, selain itu mengikuti pilihan pengguna. */
    val serifFamily: FontFamily get() = if (current.value.key == "default") FontFamily.Serif else current.value.family

    fun select(key: String) {
        current.value = options.firstOrNull { it.key == key } ?: options[0]
    }
}
