package com.mythos.app

import com.mythos.app.agent.GoogleAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun toStringNeverPrintsSecrets() {
        val s = UserSettings(
            provider = ProviderChoice.OPENAI,
            apiKey = "sk-super-rahasia",
            githubToken = "ghp_rahasia_banget",
            githubRepo = "me/app",
            braveKey = "BSA_rahasia",
            tavilyKey = "tvily_rahasia",
            permissionMode = com.mythos.app.agent.PermissionMode.PLAN,
        )
        val text = s.toString()
        assertFalse(text, text.contains("rahasia"))
        assertTrue(text, text.contains("me/app"))
        assertTrue(text, text.contains("permissionMode=PLAN"))
    }

    @Test
    fun permissionModeDefaultsToAcceptEditsAndCanBeChosen() {
        assertTrue(UserSettings().permissionMode == com.mythos.app.agent.PermissionMode.ACCEPT_EDITS)
        val s = UserSettings().copy(permissionMode = com.mythos.app.agent.PermissionMode.PLAN)
        assertTrue(com.mythos.app.agent.AgentConfig(permissionMode = s.permissionMode).permissionMode == com.mythos.app.agent.PermissionMode.PLAN)
    }

    @Test
    fun reconnectingAccountPreservesPreviouslyGrantedScopesAndUpdatesAccessToken() {
        val initial = GoogleAccount(
            id = "user@example.com",
            email = "user@example.com",
            accessToken = "ya29.initial",
            refreshToken = "1//refresh",
            gmailConnected = true,
            calendarConnected = false,
            driveConnected = false,
            gmailGranted = true,
            calendarGranted = false,
            driveGranted = false,
        )
        var s = UserSettings().withAddedOrUpdatedGoogleAccount(initial)
        assertTrue(s.gmailEnabled)
        assertFalse(s.calendarEnabled)

        // Login ulang dengan akun yang sama untuk menambah izin Calendar
        val addedCal = initial.copy(
            accessToken = "ya29.second",
            gmailConnected = true,
            calendarConnected = true,
            gmailGranted = false,
            calendarGranted = true,
        )
        s = s.withAddedOrUpdatedGoogleAccount(addedCal)
        val active = s.activeGoogleAccount!!
        assertTrue(active.gmailGranted)
        assertTrue(active.calendarGranted)
        assertFalse(active.driveGranted)
        assertTrue(s.calendarEnabled)

        // Pembaruan access token otomatis setelah refresh
        s = s.withUpdatedGoogleAccessToken("ya29.third", "user@example.com")
        assertEquals("ya29.third", s.activeGoogleAccount!!.accessToken)
    }
}
