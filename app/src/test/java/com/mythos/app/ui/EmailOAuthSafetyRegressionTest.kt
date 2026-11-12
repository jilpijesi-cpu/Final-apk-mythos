package com.mythos.app.ui

import com.mythos.app.agent.ComposeEmail
import com.mythos.app.agent.DraftHistory
import com.mythos.app.agent.Protocol
import com.mythos.app.agent.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EmailOAuthSafetyRegressionTest {
    @Test
    fun oauthCallbackIsNotClaimedAgainAfterViewModelRecreation() {
        val persistedFingerprints = mutableSetOf<String>()
        val redirect = "com.mythos.app:/oauth2redirect?code=one-time-code&state=state-123"
        val first = OAuthCallbackGate(
            wasAttempted = { it in persistedFingerprints },
            persistAttempt = { persistedFingerprints.add(it); true },
        )

        assertEquals(OAuthCallbackGate.Claim.CLAIMED, first.claim(redirect))
        assertEquals(1, persistedFingerprints.size)
        assertFalse(persistedFingerprints.single().contains("one-time-code"))

        // A new Activity/VM after rotation or process recreation reads the saved fingerprint.
        val recreated = OAuthCallbackGate(
            wasAttempted = { it in persistedFingerprints },
            persistAttempt = { persistedFingerprints.add(it); true },
        )
        assertEquals(OAuthCallbackGate.Claim.ALREADY_CLAIMED, recreated.claim(redirect))
    }

    @Test
    fun callbackIsNotClaimedWhenDurableMarkerCannotBeWritten() {
        val gate = OAuthCallbackGate(wasAttempted = { false }, persistAttempt = { false })
        assertEquals(OAuthCallbackGate.Claim.PERSIST_FAILED, gate.claim("com.mythos.app:/oauth2redirect?code=x&state=y"))
    }

    @Test
    fun attemptedGmailDraftStaysBlockedUntilConfirmedSent() {
        val root = Files.createTempDirectory("mythos-send-attempt").toFile()
        val to = listOf("recipient@example.com")
        DraftHistory.record(root, to, emptyList(), "Subject", "Body", emptyList())

        val attempt = DraftHistory.markSendAttempted(root, to, emptyList(), "Subject", "Body", emptyList())
        assertNotNull(attempt)
        assertEquals(DraftHistory.STATUS_SEND_ATTEMPTED, attempt!!.status)
        assertEquals(DraftHistory.STATUS_SEND_ATTEMPTED, DraftHistory.list(root, attempt.key).single().status)
        assertNull(DraftHistory.markSendAttempted(root, to, emptyList(), "Subject", "Body", emptyList()))
        assertEquals(
            DraftHistory.STATUS_SEND_ATTEMPTED,
            DraftHistory.markOpened(root, to, emptyList(), "Subject", "Body", emptyList())!!.status,
        )

        assertEquals(
            DraftHistory.STATUS_SENT,
            DraftHistory.markSent(root, to, emptyList(), "Subject", "Body", emptyList())!!.status,
        )
        assertNull(DraftHistory.markSendAttempted(root, to, emptyList(), "Subject", "Body", emptyList()))
    }

    @Test
    fun interruptedEmailCardStatusAndReconciliationNoteSurviveChatReload() {
        val store = ChatStore(Files.createTempDirectory("mythos-email-card").toFile())
        val note = "Hasil tidak diketahui; periksa Gmail > Terkirim. Tidak dicoba ulang otomatis."
        val draft = EmailDraftItem(
            id = 1,
            to = listOf("recipient@example.com"),
            cc = emptyList(),
            subject = "Subject",
            body = "Body",
            attachments = emptyList(),
            sending = true,
            sent = false,
            note = note,
        )
        store.save(SavedChat(ChatMeta("chat-1", "email", "/project/chat-1", 1L), listOf(UserItem(0, "email"), draft), emptyList()))

        val restored = store.load("chat-1")!!.items[1] as EmailDraftItem
        assertTrue(restored.sending)
        assertFalse(restored.sent)
        assertEquals(note, restored.note)
    }

    @Test
    fun composeEmailPromptSeparatesGmailFromEmailAppFallback() {
        val prompt = Protocol.systemPrompt(ToolRegistry.default(false, withDevice = true, withGmail = true), false)
        assertTrue(prompt.contains("sends directly through Gmail API"))
        assertTrue(prompt.contains("the user must press Send there"))
        assertTrue(prompt.contains("reconcile manually"))
        assertTrue(prompt.contains("never automatically retry"))
        assertTrue(ComposeEmail().doc.contains("check Gmail > Sent"))
    }
}
