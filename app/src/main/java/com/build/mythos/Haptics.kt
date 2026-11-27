package com.build.mythos

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/*
 * Getaran halus untuk aksi utama. Aturannya: aksi utama (kirim, hentikan, salin, setuju/tolak, buka menu, mikrofon)
 * mendapat getaran ringan; ketukan biasa dan pengguliran tidak. Semuanya mengikuti pengaturan "umpan balik sentuhan"
 * sistem, jadi pengguna yang mematikannya di Pengaturan HP tidak merasakan apa-apa.
 */

/** Ketukan ringan untuk tombol biasa. */
internal fun View.hapticTap() {
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}

/** Aksi berhasil / dikonfirmasi (kirim, salin, setuju). Android 11+ punya pola khusus; di bawahnya sama dengan [hapticTap]. */
internal fun View.hapticConfirm() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
}

/** Aksi ditolak / dibatalkan (tolak). Android 11+ punya pola khusus; di bawahnya sama dengan [hapticTap]. */
internal fun View.hapticReject() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.VIRTUAL_KEY)
}
