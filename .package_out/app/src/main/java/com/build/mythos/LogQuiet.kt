package com.build.mythos

/**
 * Galat yang sengaja diabaikan (pembersihan, UI sekunder) tetap dicatat ke logcat agar bisa dilacak.
 * Aman di tes JVM: bila android.util.Log tidak tersedia, pencatatan diam saja.
 */
internal fun logQuiet(where: String, e: Throwable) {
    try {
        android.util.Log.w("Mythos", "$where: ${e.javaClass.simpleName}: ${redactSecrets(e.message.orEmpty())}")
    } catch (ignored: Throwable) {
    }
}
