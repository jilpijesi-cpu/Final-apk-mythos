package com.build.mythos

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

// Login Google (OAuth 2.0 untuk aplikasi Android, PKCE S256, tanpa client secret) untuk konektor email.
// Murni Kotlin/JVM (tanpa android.*), jadi bisa diuji. Token dipakai lewat IMAP/SMTP dengan SASL XOAUTH2,
// jadi alat email_daftar, email_baca, dan email_kirim tetap sama; hanya cara masuknya yang berbeda.
//
// Syarat sisi Google Cloud: Client ID bertipe Android yang terikat ke package name + SHA-1 kunci penandatangan,
// dan scope https://mail.google.com/ ada di layar persetujuan OAuth. Redirect-nya "<package>:/oauth2redirect".

internal class GoogleSession(val state: String, val verifier: String, val createdAt: Long)

internal class GoogleTokens(val accessToken: String, val refreshToken: String, val expiresAtMs: Long, val email: String)

internal object GoogleOAuth {
    const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.send"
    const val REDIRECT_PATH = "/oauth2redirect"
    const val SESSION_TTL_MS = 10 * 60 * 1000L
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
    private val rng = SecureRandom()

    fun redirectUri(packageName: String) = "$packageName:$REDIRECT_PATH"

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes); rng.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun challengeOf(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun newSession(now: Long = System.currentTimeMillis()) = GoogleSession(randomUrlSafe(24), randomUrlSafe(48), now)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun authUrl(clientId: String, packageName: String, session: GoogleSession, loginHint: String? = null): String {
        if (clientId.isBlank()) throw ConnectorException("Client ID Google belum diisi (mythosGoogleClientId di gradle.properties)")
        val hint = if (!loginHint.isNullOrBlank() && parseAddress(loginHint) != null) "&login_hint=${enc(loginHint.trim())}" else ""
        return "$AUTH_URL?client_id=${enc(clientId.trim())}&redirect_uri=${enc(redirectUri(packageName))}&response_type=code" +
            "&scope=${enc("$SCOPE email")}&access_type=offline&prompt=consent%20select_account" +
            "&code_challenge=${enc(challengeOf(session.verifier))}&code_challenge_method=S256&state=${enc(session.state)}$hint"
    }

    /** Hasil penguraian tautan balik: kode otorisasi, atau pesan galat. */
    class Redirect(val code: String?, val error: String?)

    /** Hanya "<package>:/oauth2redirect" yang diterima; skema sama dengan path lain diabaikan (null). */
    fun parseRedirect(scheme: String?, host: String?, path: String?, query: Map<String, String>, packageName: String, session: GoogleSession?, now: Long = System.currentTimeMillis()): Redirect? {
        if (!scheme.equals(packageName, ignoreCase = true) || !host.isNullOrEmpty() || path != REDIRECT_PATH) return null
        if (session == null) return Redirect(null, "Tidak ada proses login yang sedang berjalan. Mulai lagi dari Pengaturan.")
        if (now - session.createdAt > SESSION_TTL_MS) return Redirect(null, "Login kedaluwarsa. Mulai lagi.")
        val st = query["state"]
        if (st == null || !MessageDigest.isEqual(st.toByteArray(), session.state.toByteArray())) return Redirect(null, "Tanda keamanan (state) tidak cocok; login dibatalkan.")
        query["error"]?.let { return Redirect(null, if (it == "access_denied") "Izin Google ditolak." else "Google menolak login ($it).") }
        val code = query["code"]?.takeIf { it.isNotBlank() } ?: return Redirect(null, "Kode otorisasi tidak ada di tautan balik.")
        return Redirect(code, null)
    }

    private fun form(vararg kv: Pair<String, String>) = kv.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

    private fun postForm(body: String, packageName: String): JSONObject {
        val r = CxHttp.requestForm(TOKEN_URL, body)
        val j = try { JSONObject(r.body) } catch (e: JSONException) { throw ConnectorException("Balasan Google bukan JSON (HTTP ${r.code})") }
        if (r.code !in 200..299) {
            val err = j.optString("error")
            val hint = when (err) {
                "invalid_grant" -> "Akses Google sudah dicabut atau kedaluwarsa. Masuk dengan Google lagi."
                "invalid_client", "unauthorized_client" -> "Client ID ditolak Google untuk package $packageName. Pastikan Client ID tipe Android dan SHA-1 keystore penandatangan aplikasi ini cocok."
                "redirect_uri_mismatch" -> "Redirect tidak cocok untuk package $packageName. Pastikan Client ID tipe Android terdaftar untuk package dan SHA-1 keystore penandatangan aplikasi ini."
                else -> err.ifBlank { "HTTP ${r.code}" }
            }
            throw ConnectorException("Login Google gagal: $hint")
        }
        return j
    }

    private fun tokensFrom(j: JSONObject, oldRefresh: String, now: Long): GoogleTokens {
        val access = j.optString("access_token").ifBlank { throw ConnectorException("Google tidak mengirim access token") }
        val refresh = j.optString("refresh_token").ifBlank { oldRefresh }
        return GoogleTokens(access, refresh, now + j.optLong("expires_in", 3600L) * 1000L, "")
    }

    fun exchange(clientId: String, packageName: String, code: String, verifier: String, now: Long = System.currentTimeMillis()): GoogleTokens {
        val t = tokensFrom(postForm(form("client_id" to clientId.trim(), "code" to code, "code_verifier" to verifier,
            "grant_type" to "authorization_code", "redirect_uri" to redirectUri(packageName)), packageName), "", now)
        if (t.refreshToken.isBlank()) throw ConnectorException("Google tidak mengirim refresh token. Cabut akses aplikasi di akun Google lalu coba lagi.")
        return GoogleTokens(t.accessToken, t.refreshToken, t.expiresAtMs, fetchEmail(t.accessToken))
    }

    fun refresh(clientId: String, packageName: String, refreshToken: String, now: Long = System.currentTimeMillis()): GoogleTokens =
        tokensFrom(postForm(form("client_id" to clientId.trim(), "refresh_token" to refreshToken, "grant_type" to "refresh_token"), packageName), refreshToken, now)

    fun fetchEmail(accessToken: String): String {
        val r = CxHttp.request("GET", USERINFO_URL, mapOf("Authorization" to "Bearer $accessToken"), null)
        if (r.code !in 200..299) throw ConnectorException("Tidak bisa membaca alamat akun Google (HTTP ${r.code})")
        val email = try { JSONObject(r.body).optString("email") } catch (e: JSONException) { "" }
        return parseAddress(email) ?: throw ConnectorException("Google tidak mengirim alamat email akun")
    }

    /** Untai awal SASL XOAUTH2 (sebelum Base64). */
    fun xoauth2(user: String, accessToken: String): String = "user=$user\u0001auth=Bearer $accessToken\u0001\u0001"

    fun xoauth2B64(user: String, accessToken: String): String =
        Base64.getEncoder().encodeToString(xoauth2(user, accessToken).toByteArray(Charsets.UTF_8))
}
