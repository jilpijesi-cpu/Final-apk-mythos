package com.build.mythos

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Looper
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

// Login Google resmi untuk Android melalui AuthorizationClient.
// Client ID tetap bertipe Android dan terikat ke package com.build.mythos + SHA-1
// keystore penandatangan aplikasi ini. Flow ini tidak mengirim redirect_uri manual.
// Access token disimpan terenkripsi dan dipakai oleh IMAP/SMTP XOAUTH2.
internal const val GOOGLE_AUTH_REQUEST_CODE = 7341
internal const val GOOGLE_ACCOUNT_PICKER_REQUEST_CODE = 7340
private const val GOOGLE_ACCOUNT_TYPE = "com.google"
// F-12: gunakan scope Gmail paling sempit sesuai fitur (baca + kirim + profil email), bukan akses penuh mailbox.
private val GOOGLE_AUTH_SCOPES = listOf(
    "https://www.googleapis.com/auth/gmail.readonly",
    "https://www.googleapis.com/auth/gmail.send",
    "https://www.googleapis.com/auth/userinfo.email"
)

internal fun MainActivity.googleClientId(): String = BuildConfig.GOOGLE_OAUTH_CLIENT_ID

/** Memulai pemilihan akun Google sebelum otorisasi agar akun lama tidak dipakai ulang diam-diam. */
internal fun MainActivity.startGoogleLogin(hint: String? = null) {
    if (googleClientId().isBlank()) {
        toast("Client ID Google belum diisi (mythosGoogleClientId di gradle.properties)")
        return
    }
    val picker = AccountPicker.newChooseAccountIntent(
        null,
        null,
        arrayOf(GOOGLE_ACCOUNT_TYPE),
        true,
        "Pilih akun Google untuk Gmail",
        null,
        null,
        null
    )
    try {
        startActivityForResult(picker, GOOGLE_ACCOUNT_PICKER_REQUEST_CODE)
    } catch (e: Exception) {
        val msg = "Pemilih akun Google tidak tersedia di perangkat ini: ${e.message ?: e.javaClass.simpleName}"
        toast(msg)
        googleLoginDone?.invoke(msg)
    }
}

/** Melanjutkan otorisasi dengan akun yang baru dipilih dari AccountPicker. */
private fun MainActivity.authorizeGoogleAccount(account: Account) {
    val scopes = GOOGLE_AUTH_SCOPES.map(::Scope)
    val builder = AuthorizationRequest.builder().setRequestedScopes(scopes)
    builder.setAccount(account)
    Identity.getAuthorizationClient(this).authorize(builder.build())
        .addOnSuccessListener { result ->
            if (result.hasResolution()) {
                try {
                    val sender = result.pendingIntent?.intentSender
                        ?: throw IllegalStateException("PendingIntent otorisasi Google tidak tersedia")
                    startIntentSenderForResult(sender, GOOGLE_AUTH_REQUEST_CODE, null, 0, 0, 0)
                } catch (e: Exception) {
                    val msg = "Persetujuan Google tidak dapat dibuka: ${e.message ?: "error tidak diketahui"}"
                    toast(msg)
                    googleLoginDone?.invoke(msg)
                }
            } else {
                handleGoogleAuthorizationResult(result)
            }
        }
        .addOnFailureListener { e ->
            val detail = describeGoogleAuthError(e, packageName)
            toast(detail)
            googleLoginDone?.invoke(detail)
        }
}

private fun describeGoogleAuthError(e: Throwable, packageName: String): String {
    if (e is ApiException) {
        val code = e.statusCode
        val raw = e.statusMessage?.takeIf { it.isNotBlank() } ?: CommonStatusCodes.getStatusCodeString(code)
        val hint = when (code) {
            CommonStatusCodes.DEVELOPER_ERROR ->
                "Konfigurasi OAuth tidak cocok (kode 10): cek package $packageName, SHA-1 keystore, Client ID Android, atau daftar Test user di Google Cloud Console."
            CommonStatusCodes.CANCELED ->
                "Persetujuan Google dibatalkan (kode 16)."
            CommonStatusCodes.NETWORK_ERROR ->
                "Koneksi ke layanan Google gagal (kode 7). Cek internet lalu coba lagi."
            CommonStatusCodes.SIGN_IN_REQUIRED ->
                "Akun Google memerlukan login ulang (kode 4)."
            CommonStatusCodes.INTERNAL_ERROR ->
                "Galat internal Google Play Services (kode 8)."
            else -> "Otorisasi Google ditolak (kode $code: $raw)."
        }
        return hint
    }
    return "Login Google gagal untuk package $packageName: ${e.message ?: e.javaClass.simpleName}"
}

/** Dipanggil setelah AccountPicker selesai, sebelum authorize(). */
internal fun MainActivity.handleGoogleAccountPickerResult(resultCode: Int, data: Intent?): Boolean {
    if (resultCode != Activity.RESULT_OK || data == null) {
        val msg = if (resultCode == Activity.RESULT_CANCELED) "Pemilihan akun Google dibatalkan."
        else "Pemilihan akun Google gagal (resultCode=$resultCode, data=${if (data == null) "kosong" else "ada"})."
        toast(msg)
        googleLoginDone?.invoke(msg)
        return true
    }
    val name = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME).orEmpty().trim()
    val type = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE).orEmpty()
    if (name.isBlank() || type != GOOGLE_ACCOUNT_TYPE) {
        val msg = "Akun Google tidak valid; pilih akun Google lagi."
        toast(msg)
        googleLoginDone?.invoke(msg)
        return true
    }
    authorizeGoogleAccount(Account(name, type))
    return true
}

/** Dipanggil oleh MainActivity.onActivityResult setelah layar persetujuan Google selesai. */
internal fun MainActivity.handleGoogleAuthorizationResult(resultCode: Int, data: Intent?): Boolean {
    if (data == null) {
        val msg = when (resultCode) {
            Activity.RESULT_OK -> "Google mengembalikan hasil kosong (resultCode=OK, data=null)."
            Activity.RESULT_CANCELED -> "Login Google dibatalkan atau layar persetujuan ditutup tanpa data (resultCode=CANCELED, data=null). Jika Anda tidak menekan Kembali, cek Test user / scope Gmail di Google Cloud Console."
            else -> "Login Google gagal tanpa data (resultCode=$resultCode)."
        }
        toast(msg)
        googleLoginDone?.invoke(msg)
        return true
    }
    return try {
        val result = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
        if (resultCode != Activity.RESULT_OK && result.accessToken.isNullOrBlank()) {
            val msg = "Otorisasi Google tidak disetujui (resultCode=$resultCode, token kosong)."
            toast(msg)
            googleLoginDone?.invoke(msg)
            return true
        }
        handleGoogleAuthorizationResult(result)
        true
    } catch (e: Exception) {
        val msg = describeGoogleAuthError(e, packageName) + " (resultCode=$resultCode)"
        toast(msg)
        googleLoginDone?.invoke(msg)
        true
    }
}

private fun MainActivity.handleGoogleAuthorizationResult(result: AuthorizationResult) {
    val accessToken = result.accessToken.orEmpty()
    if (accessToken.isBlank()) {
        val msg = "Google tidak mengirim access token untuk package $packageName."
        toast(msg)
        googleLoginDone?.invoke(msg)
        return
    }
    toast("Menyelesaikan login Google…")
    bg {
        val msg = try {
            val email = GoogleOAuth.fetchEmail(accessToken)
            saveGoogleAccessToken(accessToken)
            prefs.edit().putString("cx_email_addr", email).putBoolean("cx_email_on", true).apply()
            removeLegacyEmailPrefs()
            val test = cxTestEmail()
            if (test.startsWith("✓")) "✓ Terhubung: $email" else "Terhubung: $email. $test"
        } catch (e: Exception) {
            "✗ " + Cx.friendly(e, listOf(accessToken))
        }
        runOnUiThread { toast(msg); googleLoginDone?.invoke(msg) }
    }
}

private fun MainActivity.saveGoogleAccessToken(accessToken: String) {
    val encrypted = Secret.encrypt(accessToken)
    if (encrypted.isFailure) throw ConnectorException("Token Google tidak bisa diamankan oleh Android Keystore; tidak disimpan.")
    prefs.edit().putString("cx_google_access", encrypted.getOrThrow())
        .putLong("cx_google_exp", System.currentTimeMillis() + 3_500_000L)
        // Penanda lama dibersihkan: AuthorizationClient tidak memakai refresh token manual.
        .remove("cx_google_refresh")
        .apply()
}

/** Hapus waktu kedaluwarsa token cache agar panggilan berikutnya memaksa pembaruan token baru. */
internal fun MainActivity.invalidateGoogleAccessToken() {
    prefs.edit().remove("cx_google_exp").apply()
}

/** Access token yang masih berlaku; token kedaluwarsa diperbarui diam-diam di thread latar. */
internal fun MainActivity.googleAccessToken(): String {
    val now = System.currentTimeMillis()
    val cached = cxSecret("cx_google_access")
    if (cached.isNotBlank() && prefs.getLong("cx_google_exp", 0L) - now > 60_000L) return cached

    if (Looper.myLooper() == Looper.getMainLooper()) {
        throw ConnectorException("Pembaruan sesi Google harus dijalankan di thread latar.")
    }
    val email = prefs.getString("cx_email_addr", "").orEmpty().trim()
    if (parseAddress(email) == null) {
        throw ConnectorException("Sesi Google kedaluwarsa. Hubungkan ulang.")
    }
    val request = AuthorizationRequest.builder()
        .setRequestedScopes(GOOGLE_AUTH_SCOPES.map(::Scope))
        .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
        .build()
    val result = try {
        Tasks.await(Identity.getAuthorizationClient(this).authorize(request), 30, TimeUnit.SECONDS)
    } catch (e: Exception) {
        throw ConnectorException("Sesi Google tidak dapat diperbarui; koneksi tetap tersimpan. ${e.message ?: "Coba lagi nanti."}")
    }
    if (result.hasResolution()) {
        throw ConnectorException("Google memerlukan persetujuan ulang. Hubungkan ulang.")
    }
    val refreshed = result.accessToken.orEmpty()
    if (refreshed.isBlank()) {
        throw ConnectorException("Google memerlukan persetujuan ulang. Hubungkan ulang.")
    }
    saveGoogleAccessToken(refreshed)
    return refreshed
}

internal fun MainActivity.googleEmailConfig(): EmailConfig? {
    val addr = prefs.getString("cx_email_addr", "").orEmpty().trim()
    if (parseAddress(addr) == null || prefs.getString("cx_google_access", "").isNullOrBlank()) return null
    val token = googleAccessToken()
    return EmailConfig(addr, addr, token, GMAIL_IMAP_HOST, 993, GMAIL_SMTP_HOST, 465)
}

private const val GMAIL_IMAP_HOST = "imap.gmail.com"
private const val GMAIL_SMTP_HOST = "smtp.gmail.com"

/** Hapus sisa mode lama (kata sandi aplikasi, server IMAP/SMTP manual). */
internal fun MainActivity.removeLegacyEmailPrefs() {
    prefs.edit().remove("cx_email_pass").remove("cx_email_user").remove("cx_email_mode")
        .remove("cx_imap_host").remove("cx_imap_port").remove("cx_smtp_host").remove("cx_smtp_port").apply()
}

internal fun MainActivity.googleSignOut() {
    prefs.edit().remove("cx_google_refresh").remove("cx_google_access").remove("cx_google_exp").remove("cx_email_addr").apply()
    removeLegacyEmailPrefs()
}

internal fun MainActivity.googleConnected(): Boolean = cxReady("email")

/** Satu tombol untuk semua layar: belum terhubung -> otorisasi Google; sudah terhubung -> putuskan. */
internal fun MainActivity.toggleGoogleEmail(onChange: () -> Unit) {
    if (googleConnected()) {
        googleSignOut()
        prefs.edit().putBoolean("cx_email_on", false).putBoolean("cx_email_auto", false).apply()
        // RevokeAccessRequest tidak tersedia di play-services-auth 21.3.0. Karena itu
        // AccountPicker selalu ditampilkan pada koneksi berikutnya; izin Google mungkin masih aktif.
        toast("Email diputuskan. Izin Google mungkin masih aktif; pilih akun saat menghubungkan lagi.")
        onChange()
        return
    }
    if (googleClientId().isBlank()) {
        toast("Client ID Google belum diisi (mythosGoogleClientId di gradle.properties)")
        return
    }
    googleLoginDone = { msg -> googleLoginDone = null; if (!googleConnected()) toast(msg); onChange() }
    startGoogleLogin(null)
}
