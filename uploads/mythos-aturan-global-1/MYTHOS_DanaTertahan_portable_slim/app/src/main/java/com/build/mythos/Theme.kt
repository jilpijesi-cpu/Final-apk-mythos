package com.build.mythos

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager

// Mode gelap: mengikuti pengaturan tema sistem. Warna ada di Palette (getter yang bergantung pada Palette.dark);
// berkas ini hanya menentukan nilai awalnya dan hanya menyentuh Android lewat Configuration.

/** True bila [uiMode] (Configuration.uiMode) menyatakan mode malam. Murni, bisa diuji tanpa Android. */
internal fun isNightMode(uiMode: Int): Boolean =
    (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Atur [Palette.dark] dari konfigurasi sistem. Panggil sebelum membangun tampilan. Mengembalikan true bila nilainya berubah. */
internal fun applyTheme(context: Context): Boolean {
    val night = isNightMode(context.resources.configuration.uiMode)
    val changed = Palette.dark != night
    Palette.dark = night
    return changed
}

/**
 * Mode keyboard "resize" untuk jendela dialog. Konstanta SOFT_INPUT_ADJUST_RESIZE deprecated sejak API 30, tetapi
 * app ini tidak edge-to-edge sehingga perilakunya tetap benar; deprecation disembunyikan di satu tempat ini saja.
 */
@Suppress("DEPRECATION")
internal fun Window.softInputResize() = setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

/** Bendera system UI untuk status bar (dan bar navigasi bila [navLight]): ikon gelap di tema terang, bawaan (ikon terang) di tema gelap. */
@Suppress("DEPRECATION") // dipakai hanya di Android 10 ke bawah lewat systemUiVisibility
internal fun barFlags(navLight: Boolean): Int {
    if (Palette.dark) return 0
    var f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    if (navLight) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    return f
}

/**
 * Atur ikon status bar (dan bar navigasi bila [navLight]) sesuai tema. Android 11+ memakai WindowInsetsController;
 * di bawahnya tetap systemUiVisibility lewat [barFlags] karena tidak ada pengganti tanpa library tambahan.
 * Hasilnya sama dengan menetapkan `decorView.systemUiVisibility = barFlags(navLight)`.
 */
internal fun Window.applyBarAppearance(navLight: Boolean) {
    // Menyentuh decorView lebih dulu memasang DecorView. Tanpa ini, insetsController melempar NPE bila dipanggil
    // di onCreate sebelum setContentView (terjadi di Android 15).
    val decor = decorView
    if (Build.VERSION.SDK_INT >= 30) {
        val controller = try { insetsController } catch (e: Exception) { null }
        if (controller != null) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            var value = 0
            if (!Palette.dark) {
                value = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                if (navLight) value = value or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            }
            controller.setSystemBarsAppearance(value, mask)
            return
        }
    }
    @Suppress("DEPRECATION")
    decor.systemUiVisibility = barFlags(navLight)
}
