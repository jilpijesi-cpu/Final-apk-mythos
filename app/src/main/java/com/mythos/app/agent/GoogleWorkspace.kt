package com.mythos.app.agent

import com.mythos.app.BuildConfig

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.temporal.ChronoField
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Jenis layanan Google Workspace yang dapat dihubungkan atau diputuskan secara terpisah
 * dengan prinsip izin akses minimum (*least-privilege scopes*).
 */
enum class WorkspaceServiceKind(
    val id: String,
    val label: String,
    val scopes: List<String>,
    val description: String,
) {
    GMAIL(
        id = "gmail",
        label = "Gmail",
        scopes = listOf(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.compose",
        ),
        description = "Baca email terkait kasus & buat/kirim draf dengan persetujuan eksplisit",
    ),
    CALENDAR(
        id = "calendar",
        label = "Google Calendar",
        scopes = listOf(
            "https://www.googleapis.com/auth/calendar.events",
        ),
        description = "Sinkronisasi jadwal tenggat SLA & pengingat tindak lanjut",
    ),
    DRIVE(
        id = "drive",
        label = "Google Drive",
        scopes = listOf(
            "https://www.googleapis.com/auth/drive.file",
        ),
        description = "Kelola folder & berkas PDF bukti yang dibuat oleh aplikasi ini saja",
    ),
}

/**
 * Representasi satu akun Google pada sistem multi-akun MYTHOS V6.
 * Seluruh token disembunyikan pada [toString] dan disimpan terenkripsi dengan [com.mythos.app.SecretBox].
 * [gmailGranted]/[calendarGranted]/[driveGranted] mencatat layanan yang izinnya sungguh diminta saat OAuth,
 * terpisah dari sakelar pemakaian [gmailConnected]/[calendarConnected]/[driveConnected].
 */
data class GoogleAccount(
    val id: String = UUID.randomUUID().toString(),
    val email: String,
    val displayName: String = "",
    val accessToken: String,
    val refreshToken: String = "",
    val clientId: String = GoogleWorkspaceConfig.DEFAULT_OAUTH_CLIENT_ID,
    val gmailConnected: Boolean = true,
    val calendarConnected: Boolean = true,
    val driveConnected: Boolean = true,
    val gmailGranted: Boolean = gmailConnected,
    val calendarGranted: Boolean = calendarConnected,
    val driveGranted: Boolean = driveConnected,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val hasCredentials: Boolean
        get() = accessToken.isNotBlank() || refreshToken.isNotBlank()

    fun isServiceConnected(kind: WorkspaceServiceKind): Boolean = when (kind) {
        WorkspaceServiceKind.GMAIL -> gmailConnected
        WorkspaceServiceKind.CALENDAR -> calendarConnected
        WorkspaceServiceKind.DRIVE -> driveConnected
    }

    /** Apakah layanan ini sudah pernah dimintakan izin OAuth saat login terakhir akun ini. */
    fun isServiceGranted(kind: WorkspaceServiceKind): Boolean = when (kind) {
        WorkspaceServiceKind.GMAIL -> gmailGranted
        WorkspaceServiceKind.CALENDAR -> calendarGranted
        WorkspaceServiceKind.DRIVE -> driveGranted
    }

    fun withServiceConnected(kind: WorkspaceServiceKind, connected: Boolean): GoogleAccount = when (kind) {
        WorkspaceServiceKind.GMAIL -> copy(gmailConnected = connected)
        WorkspaceServiceKind.CALENDAR -> copy(calendarConnected = connected)
        WorkspaceServiceKind.DRIVE -> copy(driveConnected = connected)
    }

    fun toWorkspaceConfig(
        globalGmailEnabled: Boolean = true,
        globalCalendarEnabled: Boolean = true,
        globalDriveEnabled: Boolean = true,
    ): GoogleWorkspaceConfig? {
        if (!hasCredentials) return null
        return GoogleWorkspaceConfig(
            accessToken = accessToken.trim(),
            refreshToken = refreshToken.trim(),
            clientId = clientId.trim().ifEmpty { GoogleWorkspaceConfig.DEFAULT_OAUTH_CLIENT_ID },
            gmailEnabled = gmailConnected && globalGmailEnabled,
            calendarEnabled = calendarConnected && globalCalendarEnabled,
            driveEnabled = driveConnected && globalDriveEnabled,
            accountEmail = email.trim(),
        )
    }

    /** Jangan pernah menampilkan token akses/refresh pada log atau string debug. */
    override fun toString(): String =
        "GoogleAccount(id=$id, email=$email, displayName=$displayName, accessToken=${if (accessToken.isBlank()) "" else "***"}, " +
            "refreshToken=${if (refreshToken.isBlank()) "" else "***"}, gmail=$gmailConnected, calendar=$calendarConnected, drive=$driveConnected)"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("email", email)
        .put("displayName", displayName)
        .put("accessToken", accessToken)
        .put("refreshToken", refreshToken)
        .put("clientId", clientId)
        .put("gmailConnected", gmailConnected)
        .put("calendarConnected", calendarConnected)
        .put("driveConnected", driveConnected)
        .put("gmailGranted", gmailGranted)
        .put("calendarGranted", calendarGranted)
        .put("driveGranted", driveGranted)
        .put("addedAt", addedAt)

    companion object {
        fun fromJson(obj: JSONObject): GoogleAccount? {
            val acc = obj.optString("accessToken").trim()
            val ref = obj.optString("refreshToken").trim()
            if (acc.isEmpty() && ref.isEmpty()) return null
            val email = obj.optString("email").trim().ifEmpty { "Akun Google" }
            val id = obj.optString("id").trim().ifEmpty { email.lowercase() }
            val gmailConn = obj.optBoolean("gmailConnected", true)
            val calConn = obj.optBoolean("calendarConnected", true)
            val driveConn = obj.optBoolean("driveConnected", true)
            return GoogleAccount(
                id = id,
                email = email,
                displayName = obj.optString("displayName").trim(),
                accessToken = acc,
                refreshToken = ref,
                clientId = obj.optString("clientId").trim().ifEmpty { GoogleWorkspaceConfig.DEFAULT_OAUTH_CLIENT_ID },
                gmailConnected = gmailConn,
                calendarConnected = calConn,
                driveConnected = driveConn,
                gmailGranted = obj.optBoolean("gmailGranted", gmailConn),
                calendarGranted = obj.optBoolean("calendarGranted", calConn),
                driveGranted = obj.optBoolean("driveGranted", driveConn),
                addedAt = obj.optLong("addedAt", 0L),
            )
        }

        fun listToJson(accounts: List<GoogleAccount>): String {
            val arr = JSONArray()
            accounts.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String): List<GoogleAccount> {
            if (raw.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        fromJson(item)?.let { add(it) }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}

/**
 * Hasil uji koneksi integrasi eksternal (AI Provider, Gmail, Calendar, Drive, GitHub, Web Search).
 * Pesan detail disanitasi dengan [SensitiveDataGuard.redactSensitiveCredentials] agar rahasia tidak pernah tampil.
 */
data class IntegrationCheckResult(
    val service: String,
    val ok: Boolean,
    val detail: String,
    val latencyMs: Long = 0L,
    val accountIdentity: String = "",
) {
    val safeDetail: String = SensitiveDataGuard.redactSensitiveCredentials(detail)

    override fun toString(): String =
        "IntegrationCheckResult(service=$service, ok=$ok, latencyMs=$latencyMs, accountIdentity=$accountIdentity, detail=$safeDetail)"
}

/**
 * Konfigurasi OAuth 2.0 Google Workspace dengan prinsip least-privilege:
 * - `gmail.readonly`   : mencari & membaca email terkait transaksi/tiket yang diizinkan pengguna.
 * - `gmail.compose`    : membuat draf atau mengirim email dengan persetujuan eksplisit pengguna.
 * - `calendar.events`  : membaca/membuat jadwal tenggat SLA dan pengingat tindak lanjut.
 * - `drive.file`       : hanya mengelola berkas/folder bukti yang dibuat oleh aplikasi ini.
 */
data class GoogleWorkspaceConfig(
    val accessToken: String,
    val refreshToken: String = "",
    val clientId: String = DEFAULT_OAUTH_CLIENT_ID,
    val gmailEnabled: Boolean = true,
    val calendarEnabled: Boolean = true,
    val driveEnabled: Boolean = true,
    val accountEmail: String = "",
) {
    val isConfigured: Boolean
        get() = (accessToken.isNotBlank() || refreshToken.isNotBlank()) &&
            (gmailEnabled || calendarEnabled || driveEnabled)

    override fun toString(): String =
        "GoogleWorkspaceConfig(accountEmail=$accountEmail, accessToken=${if (accessToken.isBlank()) "" else "***"}, " +
            "refreshToken=${if (refreshToken.isBlank()) "" else "***"}, clientId=$clientId, " +
            "gmailEnabled=$gmailEnabled, calendarEnabled=$calendarEnabled, driveEnabled=$driveEnabled)"

    companion object {
        const val DEFAULT_REDIRECT_URI = "com.mythos.app:/oauth2redirect"

        /** Client ID Android yang dipilih sesuai varian debug/release. */
        val DEFAULT_OAUTH_CLIENT_ID: String = BuildConfig.GOOGLE_OAUTH_CLIENT_ID

        val SCOPES = listOf(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.compose",
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/drive.file",
        )

        /** Kembalikan daftar scope minimum hanya untuk layanan yang dipilih pengguna. */
        fun scopesFor(
            gmail: Boolean = true,
            calendar: Boolean = true,
            drive: Boolean = true,
        ): List<String> = buildList {
            if (gmail) addAll(WorkspaceServiceKind.GMAIL.scopes)
            if (calendar) addAll(WorkspaceServiceKind.CALENDAR.scopes)
            if (drive) addAll(WorkspaceServiceKind.DRIVE.scopes)
        }

        fun parse(
            accessToken: String,
            refreshToken: String = "",
            clientId: String = DEFAULT_OAUTH_CLIENT_ID,
            gmailEnabled: Boolean = true,
            calendarEnabled: Boolean = true,
            driveEnabled: Boolean = true,
            accountEmail: String = "",
        ): GoogleWorkspaceConfig? {
            val acc = accessToken.trim()
            val ref = refreshToken.trim()
            if (acc.isEmpty() && ref.isEmpty()) return null
            if (!gmailEnabled && !calendarEnabled && !driveEnabled) return null
            val cid = clientId.trim().ifEmpty { DEFAULT_OAUTH_CLIENT_ID }
            return GoogleWorkspaceConfig(
                accessToken = acc,
                refreshToken = ref,
                clientId = cid,
                gmailEnabled = gmailEnabled,
                calendarEnabled = calendarEnabled,
                driveEnabled = driveEnabled,
                accountEmail = accountEmail.trim(),
            )
        }

        /** Buat pasangan PKCE (code_verifier, code_challenge S256) untuk OAuth 2.0 Android/Installed App. */
        fun generatePkce(): Pair<String, String> {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val enc = Base64.getUrlEncoder().withoutPadding()
            val verifier = enc.encodeToString(bytes)
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            val challenge = enc.encodeToString(digest)
            return verifier to challenge
        }

        /** Sesi PKCE kedaluwarsa setelah 10 menit; login Google normal selesai dalam hitungan detik. */
        const val PKCE_SESSION_TTL_MS = 10 * 60 * 1000L
        const val OAUTH_REDIRECT_PATH = "/oauth2redirect"
        private const val OAUTH_SCHEME = "com.mythos.app"

        /** Nilai acak `state` OAuth (anti-CSRF): mengikat tautan balik ke login yang benar-benar dimulai pengguna. */
        fun generateState(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** Hanya `com.mythos.app:/oauth2redirect` (tanpa host) yang dianggap tautan balik OAuth; skema yang sama dengan path lain ditolak. */
        fun isOAuthRedirect(scheme: String?, host: String?, path: String?): Boolean =
            scheme.equals(OAUTH_SCHEME, ignoreCase = true) && host.isNullOrEmpty() && path == OAUTH_REDIRECT_PATH

        /** Ambil parameter `state` dari URL redirect yang ditempel; null bila tidak ada (mis. kode mentah). */
        fun extractState(input: String): String? {
            val m = Regex("""[?&]state=([^&#\s]+)""").find(input.trim()) ?: return null
            return runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(m.groupValues[1])
        }

        /** Perbandingan waktu-konstan; state yang diharapkan kosong tidak pernah cocok. */
        fun stateMatches(expected: String, actual: String?): Boolean {
            if (expected.isEmpty() || actual == null) return false
            return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
        }

        /** Pesan galat bila tautan balik tidak boleh diproses; null = boleh lanjut menukar kode. */
        fun redirectRejection(session: com.mythos.app.PkceOAuthSession?, uriState: String?, now: Long = System.currentTimeMillis()): String? = when {
            session == null || session.isExpired(now) ->
                "Sesi login Google tidak ditemukan atau sudah kedaluwarsa. Mulai ulang dari Pengaturan > Google Workspace."
            session.state.isEmpty() -> "Sesi login lama tanpa parameter state. Mulai ulang login Google."
            !stateMatches(session.state, uriState) -> "Parameter state tautan balik tidak cocok dengan login yang sedang berjalan; tautan diabaikan."
            else -> null
        }

        /**
         * PATCH7: satu-satunya pintu mulai login Google (dipakai layar Pengaturan DAN tombol Konektor).
         * Bila semua layanan mati, ketiganya diminta. Mengembalikan sesi PKCE (simpan dulu!) + URL otorisasi.
         */
        fun newLogin(clientId: String, gmail: Boolean, calendar: Boolean, drive: Boolean): Pair<com.mythos.app.PkceOAuthSession, String> {
            val none = !gmail && !calendar && !drive
            val g = gmail || none
            val c = calendar || none
            val d = drive || none
            val cid = clientId.trim().ifEmpty { DEFAULT_OAUTH_CLIENT_ID }
            val (verifier, challenge) = generatePkce()
            val st = generateState()
            val session = com.mythos.app.PkceOAuthSession(
                codeVerifier = verifier,
                clientId = cid,
                redirectUri = DEFAULT_REDIRECT_URI,
                gmailRequested = g,
                calendarRequested = c,
                driveRequested = d,
                state = st,
            )
            val url = buildAuthorizationUrl(
                clientId = cid,
                redirectUri = DEFAULT_REDIRECT_URI,
                codeChallenge = challenge,
                scopes = scopesFor(gmail = g, calendar = c, drive = d),
                state = st,
            )
            return session to url
        }

        /** PATCH7: ubah galat mentah Google menjadi penyebab + langkah perbaikan; rahasia tetap disamarkan. */
        fun describeFailure(code: Int, body: String): String {
            val raw = SensitiveDataGuard.redactSensitiveCredentials(body).take(400)
            val low = raw.lowercase()
            val hint: String? = when {
                "invalid_grant" in low ->
                    "Izin Google kedaluwarsa atau dicabut (proyek Google Cloud berstatus Testing: refresh token hanya berlaku 7 hari). Tekan Hubungkan lagi."
                "invalid_client" in low || "unauthorized_client" in low ->
                    "Client ID tidak cocok dengan APK ini (package/SHA-1 beda). Isi Client ID Google Cloud milik Anda di Pengaturan > Google > Lanjutan."
                "access_denied" in low ->
                    "Akun ini belum terdaftar sebagai test user di Google Cloud (OAuth consent screen), atau persetujuan ditolak."
                "accessnotconfigured" in low || "has not been used" in low || "api has not been enabled" in low ->
                    "API Gmail/Calendar/Drive belum diaktifkan di proyek Google Cloud Anda."
                "insufficientpermissions" in low || ("insufficient" in low && "scope" in low) ->
                    "Izin untuk layanan ini belum diberikan. Tekan Tambah akun lalu setujui semua izin."
                code == 401 -> "Sesi Google habis. Tekan Hubungkan lagi."
                code == 403 -> "Akses ditolak Google (izin layanan belum diberikan)."
                code == 429 -> "Terlalu banyak permintaan ke Google; coba lagi sebentar lagi."
                else -> null
            }
            return if (hint != null) "$hint [HTTP $code]"
            else "HTTP $code: $raw"
        }

        /** Bangun URL otorisasi OAuth 2.0 Google dengan scope minimum dan PKCE S256. */
        fun buildAuthorizationUrl(
            clientId: String = DEFAULT_OAUTH_CLIENT_ID,
            redirectUri: String = "http://localhost",
            codeChallenge: String? = null,
            scopes: List<String> = SCOPES,
            loginHint: String? = null,
            forceConsent: Boolean = true,
            state: String? = null,
        ): String {
            val activeScopes = scopes.ifEmpty { SCOPES }
            val scopeParam = URLEncoder.encode(activeScopes.joinToString(" "), "UTF-8")
            val cid = URLEncoder.encode(clientId.trim().ifEmpty { DEFAULT_OAUTH_CLIENT_ID }, "UTF-8")
            val redir = URLEncoder.encode(redirectUri, "UTF-8")
            val pkce = if (!codeChallenge.isNullOrBlank()) {
                "&code_challenge=${URLEncoder.encode(codeChallenge, "UTF-8")}&code_challenge_method=S256"
            } else ""
            val hint = if (!loginHint.isNullOrBlank()) {
                "&login_hint=${URLEncoder.encode(loginHint.trim(), "UTF-8")}"
            } else ""
            // PATCH7: consent WAJIB. Google hanya mengirim refresh_token saat layar persetujuan tampil; tanpa itu akun
            // hanya punya access token ~1 jam lalu Gmail mati sendiri.
            val prompt = if (forceConsent) "consent%20select_account" else "select_account"
            val stateParam = if (!state.isNullOrBlank()) "&state=${URLEncoder.encode(state, "UTF-8")}" else ""
            return "https://accounts.google.com/o/oauth2/v2/auth?client_id=$cid&redirect_uri=$redir&response_type=code&scope=$scopeParam&access_type=offline&prompt=$prompt$pkce$hint$stateParam"
        }

        /**
         * Ekstrak `code` otorisasi baik dari URL redirect penuh (`com.mythos.app:/oauth2redirect?code=4/...` atau
         * `http://localhost/?code=4/...&scope=...`) maupun kode otorisasi mentah yang ditempel pengguna.
         */
        fun extractAuthorizationCode(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return ""
            val codeMatch = Regex("""[?&]code=([^&#\s]+)""").find(trimmed)
            if (codeMatch != null) {
                return runCatching { URLDecoder.decode(codeMatch.groupValues[1], "UTF-8") }.getOrDefault(codeMatch.groupValues[1])
            }
            return runCatching { URLDecoder.decode(trimmed, "UTF-8") }.getOrDefault(trimmed)
        }
    }
}

private const val MAX_GMAIL_ATTACHMENT_BYTES = 18L * 1024 * 1024

interface GoogleWorkspaceService {
    val gmailEnabled: Boolean get() = true
    val calendarEnabled: Boolean get() = true
    val driveEnabled: Boolean get() = true

    suspend fun gmailSearchRead(projectRoot: File, query: String, maxResults: Int, downloadAttachments: Boolean): BuildOutcome
    suspend fun gmailDraftOrSend(
        projectRoot: File,
        action: String,
        to: List<String>,
        cc: List<String>,
        subject: String,
        body: String,
        attachments: List<String>,
    ): BuildOutcome
    suspend fun calendarSync(
        action: String,
        title: String,
        description: String,
        startIso: String,
        endIso: String,
        reminderMinutes: Int,
        maxResults: Int,
    ): BuildOutcome
    suspend fun driveVault(
        projectRoot: File,
        action: String,
        filePath: String,
        folderId: String,
        folderName: String,
    ): BuildOutcome
}

class HttpGoogleWorkspaceService(
    private val cfg: GoogleWorkspaceConfig,
    private val gmailApiBase: String = "https://gmail.googleapis.com/gmail/v1/users/me",
    private val calendarApiBase: String = "https://www.googleapis.com/calendar/v3/calendars/primary",
    private val driveApiBase: String = "https://www.googleapis.com/drive/v3",
    private val driveUploadBase: String = "https://www.googleapis.com/upload/drive/v3",
    private val oauthTokenUrl: String = "https://oauth2.googleapis.com/token",
    private val onTokenRefreshed: ((String) -> Unit)? = null,
) : GoogleWorkspaceService {

    override val gmailEnabled: Boolean get() = cfg.gmailEnabled
    override val calendarEnabled: Boolean get() = cfg.calendarEnabled
    override val driveEnabled: Boolean get() = cfg.driveEnabled

    @Volatile
    private var currentAccessToken: String = cfg.accessToken.trim()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private suspend fun ensureToken(): String = withContext(Dispatchers.IO) {
        if (currentAccessToken.isNotEmpty()) return@withContext currentAccessToken
        refreshAccessToken() ?: throw IllegalStateException("Token OAuth Google belum tersedia atau gagal diperbarui.")
    }

    internal fun refreshAccessToken(): String? {
        if (cfg.refreshToken.isBlank()) return null
        val form = "client_id=" + URLEncoder.encode(cfg.clientId, "UTF-8") +
            "&grant_type=refresh_token" +
            "&refresh_token=" + URLEncoder.encode(cfg.refreshToken, "UTF-8")
        val req = Request.Builder()
            .url(oauthTokenUrl)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) return null
            val tok = runCatching { JSONObject(txt).optString("access_token") }.getOrNull()?.trim().orEmpty()
            if (tok.isNotEmpty()) {
                currentAccessToken = tok
                runCatching { onTokenRefreshed?.invoke(tok) }
                return tok
            }
            return null
        }
    }

    private fun executeWithAuth(builder: (String) -> Request): Pair<Int, String> {
        var token = currentAccessToken
        if (token.isEmpty()) {
            token = refreshAccessToken() ?: return 401 to "Token OAuth Google belum diisi atau kedaluwarsa. Buka Pengaturan > Google Workspace."
        }
        http.newCall(builder(token)).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401 && cfg.refreshToken.isNotBlank()) {
                val refreshed = refreshAccessToken()
                if (refreshed != null) {
                    http.newCall(builder(refreshed)).execute().use { r2 ->
                        return r2.code to r2.body?.string().orEmpty()
                    }
                }
            }
            return r.code to body
        }
    }

    /**
     * Uji koneksi nyata ke masing-masing layanan Google Workspace (Gmail, Calendar, Drive) secara terpisah.
     */
    suspend fun testServiceConnection(kind: WorkspaceServiceKind): IntegrationCheckResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        try {
            when (kind) {
                WorkspaceServiceKind.GMAIL -> {
                    if (!cfg.gmailEnabled) {
                        return@withContext IntegrationCheckResult("Gmail", false, "Diputuskan oleh pengguna di Pengaturan Integrasi", 0L, cfg.accountEmail)
                    }
                    ensureToken()
                    val (code, body) = executeWithAuth { tok ->
                        Request.Builder()
                            .url("$gmailApiBase/profile")
                            .header("Authorization", "Bearer $tok")
                            .get()
                            .build()
                    }
                    val elapsed = System.currentTimeMillis() - t0
                    if (code in 200..299) {
                        val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                        val email = json.optString("emailAddress").ifBlank { cfg.accountEmail }
                        val total = json.optInt("messagesTotal", -1)
                        val msgInfo = if (total >= 0) " ($total pesan)" else ""
                        IntegrationCheckResult("Gmail", true, "Terhubung sebagai $email$msgInfo", elapsed, email)
                    } else {
                        IntegrationCheckResult("Gmail", false, GoogleWorkspaceConfig.describeFailure(code, body), elapsed, cfg.accountEmail)
                    }
                }

                WorkspaceServiceKind.CALENDAR -> {
                    if (!cfg.calendarEnabled) {
                        return@withContext IntegrationCheckResult("Google Calendar", false, "Diputuskan oleh pengguna di Pengaturan Integrasi", 0L, cfg.accountEmail)
                    }
                    ensureToken()
                    val nowIso = URLEncoder.encode(java.time.Instant.now().toString(), "UTF-8")
                    val (code, body) = executeWithAuth { tok ->
                        Request.Builder()
                            .url("$calendarApiBase/events?timeMin=$nowIso&maxResults=1&singleEvents=true")
                            .header("Authorization", "Bearer $tok")
                            .get()
                            .build()
                    }
                    val elapsed = System.currentTimeMillis() - t0
                    if (code in 200..299) {
                        val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                        val calSummary = json.optString("summary").ifBlank { cfg.accountEmail.ifBlank { "Kalender Utama" } }
                        IntegrationCheckResult("Google Calendar", true, "Terhubung ke kalender \"$calSummary\"", elapsed, calSummary)
                    } else {
                        IntegrationCheckResult("Google Calendar", false, GoogleWorkspaceConfig.describeFailure(code, body), elapsed, cfg.accountEmail)
                    }
                }

                WorkspaceServiceKind.DRIVE -> {
                    if (!cfg.driveEnabled) {
                        return@withContext IntegrationCheckResult("Google Drive", false, "Diputuskan oleh pengguna di Pengaturan Integrasi", 0L, cfg.accountEmail)
                    }
                    ensureToken()
                    val (code, body) = executeWithAuth { tok ->
                        Request.Builder()
                            .url("$driveApiBase/files?pageSize=1&fields=files(id,name)")
                            .header("Authorization", "Bearer $tok")
                            .get()
                            .build()
                    }
                    val elapsed = System.currentTimeMillis() - t0
                    if (code in 200..299) {
                        val files = runCatching { JSONObject(body).optJSONArray("files")?.length() ?: 0 }.getOrDefault(0)
                        IntegrationCheckResult(
                            "Google Drive",
                            true,
                            "Terhubung (scope minimum drive.file aktif, $files berkas terlihat)",
                            elapsed,
                            cfg.accountEmail,
                        )
                    } else {
                        IntegrationCheckResult("Google Drive", false, GoogleWorkspaceConfig.describeFailure(code, body), elapsed, cfg.accountEmail)
                    }
                }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t0
            IntegrationCheckResult(kind.label, false, cleanError(e.message ?: "Gagal menghubungkan"), elapsed, cfg.accountEmail)
        }
    }

    /** Ambil profil email akun dari layanan yang diizinkan (Gmail, Drive, atau Calendar) tanpa pernah mencatat token. */
    suspend fun fetchAccountEmail(): String = withContext(Dispatchers.IO) {
        try {
            ensureToken()
            if (cfg.gmailEnabled) {
                val (code, body) = executeWithAuth { tok ->
                    Request.Builder()
                        .url("$gmailApiBase/profile")
                        .header("Authorization", "Bearer $tok")
                        .get()
                        .build()
                }
                if (code in 200..299) {
                    val email = runCatching { JSONObject(body).optString("emailAddress") }.getOrDefault("").trim()
                    if (email.isNotEmpty()) return@withContext email
                }
            }
            if (cfg.driveEnabled) {
                val (dCode, dBody) = executeWithAuth { tok ->
                    Request.Builder()
                        .url("$driveApiBase/about?fields=user(emailAddress,displayName)")
                        .header("Authorization", "Bearer $tok")
                        .get()
                        .build()
                }
                if (dCode in 200..299) {
                    val userObj = runCatching { JSONObject(dBody).optJSONObject("user") }.getOrNull()
                    val email = userObj?.optString("emailAddress")?.trim().orEmpty()
                    if (email.isNotEmpty()) return@withContext email
                }
            }
            if (cfg.calendarEnabled) {
                val calendarProfileUrl = if (calendarApiBase.endsWith("/calendar")) {
                    "$calendarApiBase/calendars/primary"
                } else {
                    calendarApiBase
                }
                val (cCode, cBody) = executeWithAuth { tok ->
                    Request.Builder()
                        .url(calendarProfileUrl)
                        .header("Authorization", "Bearer $tok")
                        .get()
                        .build()
                }
                if (cCode in 200..299) {
                    val obj = runCatching { JSONObject(cBody) }.getOrNull()
                    val email = obj?.optString("id")?.trim()?.takeIf { it.contains("@") }
                        ?: obj?.optString("summary")?.trim()?.takeIf { it.contains("@") }
                        ?: ""
                    if (email.isNotEmpty()) return@withContext email
                }
            }
            cfg.accountEmail
        } catch (_: Exception) {
            cfg.accountEmail
        }
    }

    override suspend fun gmailSearchRead(
        projectRoot: File,
        query: String,
        maxResults: Int,
        downloadAttachments: Boolean,
    ): BuildOutcome = withContext(Dispatchers.IO) {
        if (!cfg.gmailEnabled) {
            return@withContext BuildOutcome(
                false,
                "Layanan Gmail sedang diputuskan untuk akun ini. Aktifkan sakelar Gmail di Pengaturan > Google Workspace."
            )
        }
        try {
            ensureToken()
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val limit = maxResults.coerceIn(1, 15)
            val (code, listBody) = executeWithAuth { tok ->
                Request.Builder()
                    .url("$gmailApiBase/messages?q=$q&maxResults=$limit")
                    .header("Authorization", "Bearer $tok")
                    .get()
                    .build()
            }
            if (code !in 200..299) {
                return@withContext BuildOutcome(false, "Gagal mencari email Gmail: " + GoogleWorkspaceConfig.describeFailure(code, listBody))
            }
            val messages = JSONObject(listBody).optJSONArray("messages") ?: JSONArray()
            if (messages.length() == 0) {
                return@withContext BuildOutcome(true, "Tidak ditemukan email untuk kueri: $query")
            }
            val sb = StringBuilder()
            sb.appendLine("Ditemukan ${messages.length()} email untuk kueri \"$query\":")
            val sandbox = ProjectSandbox(projectRoot)
            for (i in 0 until messages.length()) {
                val msgId = messages.optJSONObject(i)?.optString("id").orEmpty()
                if (msgId.isEmpty()) continue
                val (mCode, mBody) = executeWithAuth { tok ->
                    Request.Builder()
                        .url("$gmailApiBase/messages/$msgId?format=full")
                        .header("Authorization", "Bearer $tok")
                        .get()
                        .build()
                }
                if (mCode !in 200..299) continue
                val msgJson = JSONObject(mBody)
                val payload = msgJson.optJSONObject("payload") ?: JSONObject()
                val headers = payload.optJSONArray("headers") ?: JSONArray()
                fun header(name: String): String {
                    for (h in 0 until headers.length()) {
                        val obj = headers.optJSONObject(h) ?: continue
                        if (obj.optString("name").equals(name, ignoreCase = true)) {
                            return obj.optString("value")
                        }
                    }
                    return ""
                }
                val from = SensitiveDataGuard.sanitizeExternalContent(header("From"))
                val subject = SensitiveDataGuard.sanitizeExternalContent(header("Subject"))
                val date = SensitiveDataGuard.sanitizeExternalContent(header("Date"))
                val snippet = SensitiveDataGuard.sanitizeExternalContent(msgJson.optString("snippet"))
                val plainBody = SensitiveDataGuard.sanitizeExternalContent(extractPlainText(payload).ifBlank { snippet })
                val savedFiles = if (downloadAttachments) {
                    downloadEmailAttachments(sandbox, msgId, payload)
                } else emptyList()

                sb.appendLine()
                sb.appendLine("--- Email #${i + 1} (ID: $msgId) ---")
                sb.appendLine("Dari   : $from")
                sb.appendLine("Tanggal: $date")
                sb.appendLine("Subjek : $subject")
                if (savedFiles.isNotEmpty()) {
                    sb.appendLine("Lampiran diunduh ke proyek: ${savedFiles.joinToString(", ")}")
                }
                sb.appendLine("Isi    :")
                sb.appendLine(clip(plainBody.trim(), 2_500))
            }
            BuildOutcome(true, sb.toString().trimEnd())
        } catch (e: Exception) {
            BuildOutcome(false, "Gagal membaca Gmail: ${cleanError(e.message.orEmpty())}")
        }
    }

    private fun extractPlainText(part: JSONObject): String {
        val mime = part.optString("mimeType").lowercase()
        val bodyData = part.optJSONObject("body")?.optString("data").orEmpty()
        if (mime == "text/plain" && bodyData.isNotEmpty()) {
            return runCatching {
                String(Base64.getUrlDecoder().decode(bodyData), Charsets.UTF_8)
            }.getOrDefault("")
        }
        val parts = part.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val sub = parts.optJSONObject(i) ?: continue
            val t = extractPlainText(sub)
            if (t.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(t)
            }
        }
        return sb.toString()
    }

    internal fun downloadEmailAttachments(sandbox: ProjectSandbox, msgId: String, payload: JSONObject): List<String> {
        val saved = ArrayList<String>()
        val usedNames = HashSet<String>()
        fun visit(part: JSONObject) {
            val filename = part.optString("filename").trim()
            val attId = part.optJSONObject("body")?.optString("attachmentId").orEmpty()
            if (filename.isNotEmpty() && attId.isNotEmpty() && filename.none { it.isISOControl() }) {
                val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(60).ifBlank { "attachment" }
                val (code, body) = executeWithAuth { tok ->
                    Request.Builder()
                        .url("$gmailApiBase/messages/$msgId/attachments/$attId")
                        .header("Authorization", "Bearer $tok")
                        .get()
                        .build()
                }
                if (code in 200..299) {
                    val dataB64 = runCatching { JSONObject(body).optString("data") }.getOrNull().orEmpty()
                    if (dataB64.isNotEmpty()) {
                        val bytes = runCatching { Base64.getUrlDecoder().decode(dataB64) }.getOrNull()
                        if (bytes != null && bytes.size <= MAX_GMAIL_ATTACHMENT_BYTES) {
                            val base = "uploads/gmail/${msgId.takeLast(8)}_$safeName"
                            var rel = base
                            var suffix = 2
                            while (rel in usedNames || sandbox.resolve(rel).exists()) {
                                val dot = base.lastIndexOf('.')
                                rel = if (dot > base.lastIndexOf('/')) {
                                    base.substring(0, dot) + "_$suffix" + base.substring(dot)
                                } else "$base _$suffix"
                                suffix++
                            }
                            val outFile = sandbox.resolve(rel)
                            outFile.parentFile?.mkdirs()
                            outFile.writeBytes(bytes)
                            usedNames.add(rel)
                            saved.add(rel)
                        }
                    }
                }
            }
            val nested = part.optJSONArray("parts") ?: return
            for (i in 0 until nested.length()) nested.optJSONObject(i)?.let(::visit)
        }
        visit(payload)
        return saved
    }

    override suspend fun gmailDraftOrSend(
        projectRoot: File,
        action: String,
        to: List<String>,
        cc: List<String>,
        subject: String,
        body: String,
        attachments: List<String>,
    ): BuildOutcome = withContext(Dispatchers.IO) {
        if (!cfg.gmailEnabled) {
            return@withContext BuildOutcome(
                false,
                "Layanan Gmail sedang diputuskan untuk akun ini. Aktifkan sakelar Gmail di Pengaturan > Google Workspace."
            )
        }
        try {
            ensureToken()
            val sandbox = ProjectSandbox(projectRoot)
            val files = attachments.map { rel ->
                val f = sandbox.resolve(rel)
                if (!f.isFile) return@withContext BuildOutcome(false, "Berkas lampiran tidak ditemukan: $rel")
                f
            }
            val rawMime = buildMimeMessage(to, cc, subject, body, files)
            val encodedMime = Base64.getUrlEncoder().withoutPadding().encodeToString(rawMime.toByteArray(Charsets.UTF_8))

            val isSend = action.equals("send", ignoreCase = true)
            val endpoint = if (isSend) "$gmailApiBase/messages/send" else "$gmailApiBase/drafts"
            val payload = if (isSend) {
                JSONObject().put("raw", encodedMime)
            } else {
                JSONObject().put("message", JSONObject().put("raw", encodedMime))
            }

            val (code, respBody) = executeWithAuth { tok ->
                Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $tok")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            if (code !in 200..299) {
                return@withContext BuildOutcome(false, "Gagal ${if (isSend) "mengirim email" else "membuat draf"} Gmail ($code): ${cleanError(respBody)}")
            }
            val respJson = runCatching { JSONObject(respBody) }.getOrDefault(JSONObject())
            val id = respJson.optString("id")
            if (isSend) {
                BuildOutcome(true, "Email berhasil dikirim melalui Gmail API (ID Pesan: $id) kepada ${to.joinToString(", ")} dengan subjek \"$subject\".")
            } else {
                BuildOutcome(true, "Draf email berhasil disimpan di Gmail pengguna (ID Draf: $id) untuk ${to.joinToString(", ")} dengan subjek \"$subject\". Pengguna dapat meninjaunya di Gmail.")
            }
        } catch (e: Exception) {
            BuildOutcome(false, "Gagal memproses Gmail: ${cleanError(e.message.orEmpty())}")
        }
    }

    internal fun buildMimeMessage(
        to: List<String>,
        cc: List<String>,
        subject: String,
        body: String,
        attachments: List<File>,
    ): String {
        require(to.isNotEmpty()) { "Minimal satu alamat email tujuan diperlukan." }
        (to + cc).forEach { require(isValidGmailAddress(it)) { "Alamat email Gmail tidak valid." } }
        require(subject.none { it.isISOControl() }) { "Subjek tidak boleh berisi karakter kontrol atau CR/LF." }
        attachments.forEach { file ->
            require(file.name.none { it.isISOControl() }) { "Nama file lampiran tidak boleh berisi karakter kontrol atau CR/LF." }
        }
        require(attachments.sumOf { it.length() } <= MAX_GMAIL_ATTACHMENT_BYTES) {
            "Total lampiran Gmail melebihi batas 18 MB."
        }
        val attachmentBytes = attachments.map { it.readBytes() }
        require(attachmentBytes.sumOf { it.size.toLong() } <= MAX_GMAIL_ATTACHMENT_BYTES) {
            "Total lampiran Gmail melebihi batas 18 MB."
        }
        val encodedSubject = encodeMimeSubject(subject)
        if (attachments.isEmpty()) {
            return buildString {
                append("To: ${to.joinToString(", ")}\r\n")
                if (cc.isNotEmpty()) append("Cc: ${cc.joinToString(", ")}\r\n")
                append("Subject: $encodedSubject\r\n")
                append("MIME-Version: 1.0\r\n")
                append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
                append("Content-Transfer-Encoding: base64\r\n\r\n")
                append(mimeBase64(body.toByteArray(Charsets.UTF_8)))
            }
        }
        val boundary = "mythos_part_${System.currentTimeMillis()}"
        return buildString {
            append("To: ${to.joinToString(", ")}\r\n")
            if (cc.isNotEmpty()) append("Cc: ${cc.joinToString(", ")}\r\n")
            append("Subject: $encodedSubject\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
            append("--$boundary\r\n")
            append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(mimeBase64(body.toByteArray(Charsets.UTF_8))).append("\r\n")
            for ((index, f) in attachments.withIndex()) {
                val b64 = mimeBase64(attachmentBytes[index])
                val mime = if (f.extension.equals("pdf", true)) "application/pdf" else "application/octet-stream"
                val fallbackName = f.name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120).ifBlank { "attachment" }
                val encodedName = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20").replace("%7E", "~")
                append("--$boundary\r\n")
                append("Content-Type: $mime\r\n")
                append("Content-Disposition: attachment; filename=\"$fallbackName\"; filename*=UTF-8''$encodedName\r\n")
                append("Content-Transfer-Encoding: base64\r\n\r\n")
                append(b64).append("\r\n")
            }
            append("--$boundary--")
        }
    }

    private fun isValidGmailAddress(address: String): Boolean {
        if (address.isBlank() || address != address.trim() || address.any { it.isISOControl() }) return false
        val at = address.indexOf('@')
        if (at <= 0 || at != address.lastIndexOf('@') || at == address.lastIndex) return false
        val local = address.substring(0, at)
        val domain = address.substring(at + 1)
        if (local.length > 64 || domain.length > 253 || local.startsWith('.') || local.endsWith('.') || ".." in local) return false
        if (!local.matches(Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+"))) return false
        val labels = domain.split('.')
        return labels.size >= 2 && labels.all { label ->
            label.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))
        }
    }

    private fun encodeMimeSubject(subject: String): String {
        if (subject.all { it.code in 0x20..0x7e }) return subject
        val words = ArrayList<String>()
        val chunk = StringBuilder()
        var byteCount = 0
        var i = 0
        while (i < subject.length) {
            val cp = subject.codePointAt(i)
            val piece = String(Character.toChars(cp))
            val bytes = piece.toByteArray(Charsets.UTF_8)
            if (byteCount + bytes.size > 45 && chunk.isNotEmpty()) {
                words.add("=?UTF-8?B?${Base64.getEncoder().encodeToString(chunk.toString().toByteArray(Charsets.UTF_8))}?=")
                chunk.setLength(0)
                byteCount = 0
            }
            chunk.append(piece)
            byteCount += bytes.size
            i += Character.charCount(cp)
        }
        if (chunk.isNotEmpty()) words.add("=?UTF-8?B?${Base64.getEncoder().encodeToString(chunk.toString().toByteArray(Charsets.UTF_8))}?=")
        return words.joinToString("\r\n ")
    }

    private fun mimeBase64(bytes: ByteArray): String =
        Base64.getMimeEncoder(76, "\r\n".toByteArray(Charsets.US_ASCII)).encodeToString(bytes)

    private fun parseRfc3339Offset(value: String): OffsetDateTime {
        require(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})$").matches(value)) {
            "Waktu harus menggunakan format RFC3339 dengan zona waktu, misalnya 2026-11-20T09:00:00+07:00."
        }
        return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    private fun formatRfc3339(value: OffsetDateTime): String =
        value.format(
            java.time.format.DateTimeFormatterBuilder()
                .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
                .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                .appendOffsetId()
                .toFormatter()
        )

    override suspend fun calendarSync(
        action: String,
        title: String,
        description: String,
        startIso: String,
        endIso: String,
        reminderMinutes: Int,
        maxResults: Int,
    ): BuildOutcome = withContext(Dispatchers.IO) {
        if (!cfg.calendarEnabled) {
            return@withContext BuildOutcome(
                false,
                "Layanan Google Calendar sedang diputuskan untuk akun ini. Aktifkan sakelar Google Calendar di Pengaturan > Google Workspace."
            )
        }
        try {
            ensureToken()
            when (action.lowercase().trim()) {
                "list_upcoming" -> {
                    val nowIso = URLEncoder.encode(java.time.Instant.now().toString(), "UTF-8")
                    val limit = maxResults.coerceIn(1, 20)
                    val (code, body) = executeWithAuth { tok ->
                        Request.Builder()
                            .url("$calendarApiBase/events?timeMin=$nowIso&maxResults=$limit&singleEvents=true&orderBy=startTime")
                            .header("Authorization", "Bearer $tok")
                            .get()
                            .build()
                    }
                    if (code !in 200..299) {
                        return@withContext BuildOutcome(false, "Gagal mengambil jadwal Google Calendar ($code): ${cleanError(body)}")
                    }
                    val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
                    if (items.length() == 0) return@withContext BuildOutcome(true, "Tidak ada jadwal mendatang di Google Calendar.")
                    val sb = StringBuilder("Jadwal mendatang di Google Calendar (${items.length()}):\n")
                    for (i in 0 until items.length()) {
                        val ev = items.optJSONObject(i) ?: continue
                        val sum = SensitiveDataGuard.sanitizeExternalContent(ev.optString("summary", "(tanpa judul)"))
                        val start = ev.optJSONObject("start")?.let { it.optString("dateTime").ifBlank { it.optString("date") } }.orEmpty()
                        sb.appendLine("- [$start] $sum (ID: ${ev.optString("id")})")
                    }
                    BuildOutcome(true, sb.toString().trimEnd())
                }
                "create_deadline" -> {
                    val start = parseRfc3339Offset(startIso)
                    val end = if (endIso.isBlank()) start.plusHours(1) else parseRfc3339Offset(endIso)
                    require(end.isAfter(start)) { "Waktu akhir Calendar harus setelah waktu mulai." }
                    val eventObj = JSONObject()
                        .put("summary", title)
                        .put("description", description)
                        .put("start", JSONObject().put("dateTime", formatRfc3339(start)))
                        .put("end", JSONObject().put("dateTime", formatRfc3339(end)))
                        .put(
                            "reminders",
                            JSONObject()
                                .put("useDefault", false)
                                .put(
                                    "overrides",
                                    JSONArray().put(
                                        JSONObject().put("method", "popup").put("minutes", reminderMinutes.coerceIn(5, 10_080))
                                    )
                                )
                        )
                    val (code, body) = executeWithAuth { tok ->
                        Request.Builder()
                            .url("$calendarApiBase/events")
                            .header("Authorization", "Bearer $tok")
                            .post(eventObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                            .build()
                    }
                    if (code !in 200..299) {
                        return@withContext BuildOutcome(false, "Gagal membuat jadwal Google Calendar ($code): ${cleanError(body)}")
                    }
                    val created = JSONObject(body)
                    BuildOutcome(
                        true,
                        "Jadwal tenggat berhasil ditambahkan ke Google Calendar: \"$title\" pada $startIso (ID: ${created.optString("id")}, tautan: ${created.optString("htmlLink")})."
                    )
                }
                else -> BuildOutcome(false, "Action GoogleCalendarSync tidak dikenal: $action (gunakan list_upcoming atau create_deadline)")
            }
        } catch (e: Exception) {
            BuildOutcome(false, "Gagal mengakses Google Calendar: ${cleanError(e.message.orEmpty())}")
        }
    }

    override suspend fun driveVault(
        projectRoot: File,
        action: String,
        filePath: String,
        folderId: String,
        folderName: String,
    ): BuildOutcome = withContext(Dispatchers.IO) {
        if (!cfg.driveEnabled) {
            return@withContext BuildOutcome(
                false,
                "Layanan Google Drive sedang diputuskan untuk akun ini. Aktifkan sakelar Google Drive di Pengaturan > Google Workspace."
            )
        }
        try {
            ensureToken()
            when (action.lowercase().trim()) {
                "list_files" -> {
                    val (code, body) = executeWithAuth { tok ->
                        Request.Builder()
                            .url("$driveApiBase/files?pageSize=20&fields=files(id,name,mimeType,webViewLink,modifiedTime)")
                            .header("Authorization", "Bearer $tok")
                            .get()
                            .build()
                    }
                    if (code !in 200..299) {
                        return@withContext BuildOutcome(false, "Gagal membaca daftar berkas Google Drive ($code): ${cleanError(body)}")
                    }
                    val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
                    if (files.length() == 0) return@withContext BuildOutcome(true, "Belum ada berkas yang dikelola Mythos di Google Drive (scope drive.file).")
                    val sb = StringBuilder("Berkas Mythos di Google Drive (${files.length()}):\n")
                    for (i in 0 until files.length()) {
                        val f = files.optJSONObject(i) ?: continue
                        val safeName = SensitiveDataGuard.sanitizeExternalContent(f.optString("name"))
                        sb.appendLine("- $safeName (ID: ${f.optString("id")}, tipe: ${f.optString("mimeType")}) ${f.optString("webViewLink")}")
                    }
                    BuildOutcome(true, sb.toString().trimEnd())
                }
                "create_folder" -> {
                    val meta = JSONObject()
                        .put("name", folderName.ifBlank { "Kasus Mythos" })
                        .put("mimeType", "application/vnd.google-apps.folder")
                    val (code, body) = executeWithAuth { tok ->
                        Request.Builder()
                            .url("$driveApiBase/files?fields=id,name,webViewLink")
                            .header("Authorization", "Bearer $tok")
                            .post(meta.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                            .build()
                    }
                    if (code !in 200..299) {
                        return@withContext BuildOutcome(false, "Gagal membuat folder Google Drive ($code): ${cleanError(body)}")
                    }
                    val created = JSONObject(body)
                    BuildOutcome(
                        true,
                        "Folder Google Drive berhasil dibuat: \"${created.optString("name")}\" (folder_id: ${created.optString("id")}) ${created.optString("webViewLink")}"
                    )
                }
                "upload_file" -> {
                    val sandbox = ProjectSandbox(projectRoot)
                    val f = sandbox.resolve(filePath)
                    if (!f.isFile) return@withContext BuildOutcome(false, "Berkas tidak ditemukan di proyek: $filePath")
                    if (f.length() > 15 * 1024 * 1024) return@withContext BuildOutcome(false, "Berkas terlalu besar (>15 MB) untuk diunggah.")

                    val meta = JSONObject().put("name", f.name)
                    if (folderId.isNotBlank()) meta.put("parents", JSONArray().put(folderId.trim()))
                    val mime = when (f.extension.lowercase()) {
                        "pdf" -> "application/pdf"
                        "md", "txt" -> "text/plain"
                        "json" -> "application/json"
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        else -> "application/octet-stream"
                    }
                    val boundary = "mythos_drive_${System.currentTimeMillis()}"
                    val prefix = buildString {
                        append("--$boundary\r\n")
                        append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                        append(meta.toString()).append("\r\n")
                        append("--$boundary\r\n")
                        append("Content-Type: $mime\r\n\r\n")
                    }.toByteArray(Charsets.UTF_8)
                    val suffix = "\r\n--$boundary--".toByteArray(Charsets.UTF_8)
                    val fileBytes = f.readBytes()
                    val fullBody = ByteArray(prefix.size + fileBytes.size + suffix.size)
                    System.arraycopy(prefix, 0, fullBody, 0, prefix.size)
                    System.arraycopy(fileBytes, 0, fullBody, prefix.size, fileBytes.size)
                    System.arraycopy(suffix, 0, fullBody, prefix.size + fileBytes.size, suffix.size)

                    val (code, body) = executeWithAuth { tok ->
                        Request.Builder()
                            .url("$driveUploadBase/files?uploadType=multipart&fields=id,name,webViewLink")
                            .header("Authorization", "Bearer $tok")
                            .post(fullBody.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
                            .build()
                    }
                    if (code !in 200..299) {
                        return@withContext BuildOutcome(false, "Gagal mengunggah berkas ke Google Drive ($code): ${cleanError(body)}")
                    }
                    val uploaded = JSONObject(body)
                    BuildOutcome(
                        true,
                        "Berkas ${f.name} berhasil dicadangkan ke Google Drive (ID: ${uploaded.optString("id")}, tautan: ${uploaded.optString("webViewLink")})."
                    )
                }
                else -> BuildOutcome(false, "Action GoogleDriveCaseVault tidak dikenal: $action (gunakan list_files, create_folder, atau upload_file)")
            }
        } catch (e: Exception) {
            BuildOutcome(false, "Gagal mengakses Google Drive: ${cleanError(e.message.orEmpty())}")
        }
    }

    private fun cleanError(raw: String): String =
        SensitiveDataGuard.redactSensitiveCredentials(raw).take(400)

    companion object {
        /**
         * Tukar `authorization_code` OAuth 2.0 + `code_verifier` PKCE S256 menjadi `access_token` dan `refresh_token`,
         * lalu ambil identitas email akun secara otomatis tanpa mencatat token ke log.
         */
        suspend fun exchangeCodeWithPkce(
            clientId: String,
            rawCodeOrUrl: String,
            codeVerifier: String,
            redirectUri: String = "http://localhost",
            gmailConnected: Boolean = true,
            calendarConnected: Boolean = true,
            driveConnected: Boolean = true,
            tokenUrl: String = "https://oauth2.googleapis.com/token",
            gmailApiBase: String = "https://gmail.googleapis.com/gmail/v1/users/me",
            driveApiBase: String = "https://www.googleapis.com/drive/v3",
            calendarApiBase: String = "https://www.googleapis.com/calendar/v3/calendars/primary",
        ): Result<GoogleAccount> = withContext(Dispatchers.IO) {
            runCatching {
                val code = GoogleWorkspaceConfig.extractAuthorizationCode(rawCodeOrUrl)
                require(code.isNotBlank()) { "Kode otorisasi OAuth 2.0 kosong." }
                val cid = clientId.trim().ifEmpty { GoogleWorkspaceConfig.DEFAULT_OAUTH_CLIENT_ID }
                require(cid.isNotBlank()) { "OAuth 2.0 Client ID belum diisi." }
                require(codeVerifier.isNotBlank()) { "PKCE code_verifier tidak ditemukan. Mulai ulang izin OAuth dari aplikasi." }

                val form = buildString {
                    append("client_id=").append(URLEncoder.encode(cid, "UTF-8"))
                    append("&grant_type=authorization_code")
                    append("&code=").append(URLEncoder.encode(code, "UTF-8"))
                    append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
                    append("&code_verifier=").append(URLEncoder.encode(codeVerifier.trim(), "UTF-8"))
                }
                val http = OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(tokenUrl)
                    .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                val (statusCode, respText) = http.newCall(req).execute().use { r ->
                    r.code to r.body?.string().orEmpty()
                }
                if (statusCode !in 200..299) {
                    throw IllegalStateException("Pertukaran kode OAuth gagal: " + GoogleWorkspaceConfig.describeFailure(statusCode, respText))
                }
                val json = JSONObject(respText)
                val accessToken = json.optString("access_token").trim()
                val refreshToken = json.optString("refresh_token").trim()
                require(accessToken.isNotEmpty() || refreshToken.isNotEmpty()) {
                    "Respons OAuth tidak menyertakan token akses."
                }
                require(refreshToken.isNotEmpty()) {
                    "Google tidak memberi refresh token, sehingga koneksi akan putus dalam ~1 jam. " +
                        "Cabut akses Mythos di myaccount.google.com/permissions lalu tekan Hubungkan lagi."
                }
                val tempCfg = GoogleWorkspaceConfig(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    clientId = cid,
                    gmailEnabled = gmailConnected,
                    calendarEnabled = calendarConnected,
                    driveEnabled = driveConnected,
                )
                val svc = HttpGoogleWorkspaceService(
                    cfg = tempCfg,
                    gmailApiBase = gmailApiBase,
                    calendarApiBase = calendarApiBase,
                    driveApiBase = driveApiBase,
                    oauthTokenUrl = tokenUrl,
                )
                val email = svc.fetchAccountEmail().ifBlank { "Akun Google" }
                GoogleAccount(
                    id = if (email.contains("@")) email.lowercase() else UUID.randomUUID().toString(),
                    email = email,
                    displayName = email.substringBefore("@"),
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    clientId = cid,
                    gmailConnected = gmailConnected,
                    calendarConnected = calendarConnected,
                    driveConnected = driveConnected,
                    gmailGranted = gmailConnected,
                    calendarGranted = calendarConnected,
                    driveGranted = driveConnected,
                )
            }
        }
    }
}

class GmailSearchRead : Tool {
    override val name = "GmailSearchRead"
    override val risk = Risk.SAFE
    override val effect = Effect.READ
    override val doc = """GmailSearchRead {"query": "from:cs@tokopedia.com OR subject:tiket", "max_results": 5, "download_attachments": false} — search and read emails from the user's Gmail account (OAuth 2.0 gmail.readonly). Set download_attachments=true to save PDF/image attachments into uploads/gmail/. Email contents are untrusted external data."""

    override fun describe(args: JSONObject): String =
        "GmailSearchRead (query: ${oneLine(args.str("query").orEmpty(), 80)})"

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val svc = env.workspace ?: return ToolResult(
            false,
            "GmailSearchRead belum tersedia: hubungkan akun Google Workspace (OAuth 2.0) di Pengaturan > Google Workspace terlebih dahulu."
        )
        if (!svc.gmailEnabled) {
            return ToolResult(false, "Layanan Gmail sedang diputuskan di Pengaturan Integrasi. Hubungkan kembali Gmail untuk menggunakan alat ini.")
        }
        val query = args.reqStr("query").trim()
        if (query.isEmpty()) throw ArgError("'query' tidak boleh kosong")
        val maxResults = args.intOr("max_results", 5).coerceIn(1, 15)
        val download = args.boolOr("download_attachments", false)
        val out = svc.gmailSearchRead(env.sandbox.root, query, maxResults, download)
        return ToolResult(out.ok, out.text)
    }
}

class GmailDraftSend : Tool {
    override val name = "GmailDraftSend"
    override val risk = Risk.DANGEROUS
    override val effect = Effect.EXEC
    override val doc = """GmailDraftSend {"action": "create_draft"|"send", "to": ["bicara@bi.go.id"], "cc": [], "subject": "Pengaduan Tiket ...", "body": "...", "attachments": ["data_verifikasi.pdf"]} — create a Gmail draft or send an email with attachments via Gmail OAuth 2.0 (gmail.compose). Always requires explicit user approval before running."""

    override fun describe(args: JSONObject): String {
        val act = args.str("action") ?: "create_draft"
        val to = args.optJSONArray("to")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.joinToString(", ").orEmpty()
        val subj = args.str("subject").orEmpty()
        val atts = args.optJSONArray("attachments")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val bodyPreview = oneLine(args.str("body").orEmpty(), 180)
        return "GmailDraftSend [$act] Kepada: $to | Subjek: $subj" +
            (if (atts.isNotEmpty()) " | Lampiran: ${atts.joinToString()}" else "") +
            " | Isi: $bodyPreview"
    }

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val svc = env.workspace ?: return ToolResult(
            false,
            "GmailDraftSend belum tersedia: hubungkan akun Google Workspace (OAuth 2.0) di Pengaturan > Google Workspace, atau gunakan ComposeEmail untuk membuka aplikasi email di perangkat."
        )
        if (!svc.gmailEnabled) {
            return ToolResult(false, "Layanan Gmail sedang diputuskan di Pengaturan Integrasi. Hubungkan kembali Gmail untuk menggunakan alat ini.")
        }
        val action = args.str("action")?.trim()?.lowercase().orEmpty().ifEmpty { "create_draft" }
        if (action !in setOf("create_draft", "send")) {
            throw ArgError("'action' harus 'create_draft' atau 'send'")
        }
        val toArr = args.optJSONArray("to")
        val to = if (toArr != null) {
            (0 until toArr.length()).map { toArr.optString(it).trim() }.filter { it.isNotEmpty() }
        } else {
            listOfNotNull(args.str("to")?.trim()?.takeIf { it.isNotEmpty() })
        }
        if (to.isEmpty()) throw ArgError("'to' wajib berisi minimal satu alamat email tujuan")
        val ccArr = args.optJSONArray("cc")
        val cc = if (ccArr != null) {
            (0 until ccArr.length()).map { ccArr.optString(it).trim() }.filter { it.isNotEmpty() }
        } else emptyList()
        val subject = args.reqStr("subject").trim()
        val body = args.reqStr("body")
        val attArr = args.optJSONArray("attachments")
        val attachments = if (attArr != null) {
            (0 until attArr.length()).map { attArr.optString(it).trim() }.filter { it.isNotEmpty() }
        } else emptyList()

        val out = svc.gmailDraftOrSend(env.sandbox.root, action, to, cc, subject, body, attachments)
        return ToolResult(out.ok, out.text)
    }
}

class GoogleCalendarSync : Tool {
    override val name = "GoogleCalendarSync"
    override val risk = Risk.WRITE
    override val effect = Effect.EXEC
    override val doc = """GoogleCalendarSync {"action": "list_upcoming"|"create_deadline", "title": "Batas SLA Tiket ...", "description": "...", "start_iso": "2026-11-20T09:00:00+07:00", "end_iso": "2026-11-20T10:00:00+07:00", "reminder_minutes": 1440} — list upcoming Google Calendar events or create a synced SLA deadline event with popup reminder (OAuth 2.0 calendar.events)."""

    override fun describe(args: JSONObject): String =
        "GoogleCalendarSync (${args.str("action") ?: "create_deadline"}: ${oneLine(args.str("title").orEmpty(), 80)} ${args.str("start_iso").orEmpty()})"

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val svc = env.workspace ?: return ToolResult(
            false,
            "GoogleCalendarSync belum tersedia: hubungkan akun Google Workspace (OAuth 2.0) di Pengaturan > Google Workspace, atau gunakan SetReminder / SetDeadlineNotice."
        )
        if (!svc.calendarEnabled) {
            return ToolResult(false, "Layanan Google Calendar sedang diputuskan di Pengaturan Integrasi. Hubungkan kembali Calendar untuk menggunakan alat ini.")
        }
        val action = args.str("action")?.trim()?.lowercase().orEmpty().ifEmpty { "create_deadline" }
        val title = args.str("title")?.trim().orEmpty()
        val desc = args.str("description")?.trim().orEmpty()
        val startIso = args.str("start_iso")?.trim().orEmpty()
        val endIso = args.str("end_iso")?.trim().orEmpty()
        if (action == "create_deadline" && (title.isEmpty() || startIso.isEmpty())) {
            throw ArgError("'title' dan 'start_iso' (format ISO-8601 seperti 2026-11-20T09:00:00+07:00) wajib diisi untuk create_deadline")
        }
        val reminder = args.intOr("reminder_minutes", 1440)
        val maxResults = args.intOr("max_results", 10)
        val out = svc.calendarSync(action, title, desc, startIso, endIso, reminder, maxResults)
        return ToolResult(out.ok, out.text)
    }
}

class GoogleDriveCaseVault : Tool {
    override val name = "GoogleDriveCaseVault"
    override val risk = Risk.WRITE
    override val effect = Effect.EXEC
    override val doc = """GoogleDriveCaseVault {"action": "list_files"|"create_folder"|"upload_file", "file_path": "data_verifikasi.pdf", "folder_name": "Kasus Flip 730470", "folder_id": ""} — manage case folders and upload generated PDF bundles or KASUS.md to Google Drive using least-privilege drive.file scope."""

    override fun describe(args: JSONObject): String =
        "GoogleDriveCaseVault (${args.str("action") ?: "upload_file"}: ${args.str("file_path") ?: args.str("folder_name") ?: ""})"

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        val svc = env.workspace ?: return ToolResult(
            false,
            "GoogleDriveCaseVault belum tersedia: hubungkan akun Google Workspace (OAuth 2.0) di Pengaturan > Google Workspace terlebih dahulu."
        )
        if (!svc.driveEnabled) {
            return ToolResult(false, "Layanan Google Drive sedang diputuskan di Pengaturan Integrasi. Hubungkan kembali Drive untuk menggunakan alat ini.")
        }
        val action = args.reqStr("action").trim().lowercase()
        val filePath = args.str("file_path")?.trim().orEmpty()
        val folderId = args.str("folder_id")?.trim().orEmpty()
        val folderName = args.str("folder_name")?.trim().orEmpty()
        if (action == "upload_file" && filePath.isEmpty()) {
            throw ArgError("'file_path' wajib diisi untuk upload_file")
        }
        val out = svc.driveVault(env.sandbox.root, action, filePath, folderId, folderName)
        return ToolResult(out.ok, out.text)
    }
}
