package com.mythos.app.agent

import com.mythos.app.UserSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Penguji koneksi nyata Google Workspace (Gmail, Calendar, Drive) untuk akun aktif. Pesan hasil tidak memuat token. */
object IntegrationTester {

    suspend fun testGoogleService(
        s: UserSettings,
        kind: WorkspaceServiceKind,
        gmailApiBase: String = "https://gmail.googleapis.com/gmail/v1/users/me",
        calendarApiBase: String = "https://www.googleapis.com/calendar/v3/calendars/primary",
        driveApiBase: String = "https://www.googleapis.com/drive/v3",
        oauthTokenUrl: String = "https://oauth2.googleapis.com/token",
    ): IntegrationCheckResult {
        val activeAcct = s.activeGoogleAccount
        if (activeAcct == null || !activeAcct.hasCredentials) {
            return IntegrationCheckResult(kind.label, false, "Belum ada akun Google yang dihubungkan.", 0L)
        }
        val cfg = activeAcct.toWorkspaceConfig(
            globalGmailEnabled = s.gmailEnabled,
            globalCalendarEnabled = s.calendarEnabled,
            globalDriveEnabled = s.driveEnabled,
        ) ?: return IntegrationCheckResult(kind.label, false, "Kredensial OAuth akun ${activeAcct.email} kosong.", 0L, activeAcct.email)

        val svc = HttpGoogleWorkspaceService(
            cfg = cfg,
            gmailApiBase = gmailApiBase,
            calendarApiBase = calendarApiBase,
            driveApiBase = driveApiBase,
            oauthTokenUrl = oauthTokenUrl,
        )
        return svc.testServiceConnection(kind)
    }

    suspend fun testAllGoogleServices(s: UserSettings): List<IntegrationCheckResult> = coroutineScope {
        WorkspaceServiceKind.entries.map { kind ->
            async { testGoogleService(s, kind) }
        }.awaitAll()
    }
}
