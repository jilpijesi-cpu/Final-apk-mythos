package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAutopilotTest {
    @Test fun parsesFillInsideProse() {
        val a = BrowserAutopilot.parse("Langkah:\n{\"op\":\"fill\",\"label\":\"Usulan Penyelesaian\",\"value\":\"Kembalikan dana\",\"why\":\"dari tujuan\"}")
        assertTrue(a is AutoAction.Act)
        assertEquals(BrowserCommand.Fill("Usulan Penyelesaian", "Kembalikan dana"), (a as AutoAction.Act).step.command)
    }

    @Test fun parsesClickDoneAsk() {
        assertEquals(AutoAction.Click("Pengaduan Saya", ""), BrowserAutopilot.parse("{\"op\":\"click\",\"label\":\"Pengaduan Saya\"}"))
        assertEquals(AutoAction.Done("beres"), BrowserAutopilot.parse("{\"op\":\"done\",\"summary\":\"beres\"}"))
        assertEquals(AutoAction.Ask("Nomor tiket?"), BrowserAutopilot.parse("{\"op\":\"ask\",\"question\":\"Nomor tiket?\"}"))
    }

    @Test fun rejectsCredentialsAndUnknownOps() {
        assertTrue(BrowserAutopilot.parse("{\"op\":\"fill\",\"label\":\"Password\",\"value\":\"x\"}") is AutoAction.Invalid)
        assertTrue(BrowserAutopilot.parse("{\"op\":\"click\",\"label\":\"Kirim OTP\"}") is AutoAction.Invalid)
        assertTrue(BrowserAutopilot.parse("{\"op\":\"upload\",\"label\":\"x\",\"value\":\"y\"}") is AutoAction.Invalid)
        assertTrue(BrowserAutopilot.parse("bukan json") is AutoAction.Invalid)
    }

    @Test fun detectsLoginPage() {
        assertTrue(BrowserAutopilot.needsLogin("[{\"i\":0,\"tag\":\"input\",\"type\":\"password\",\"label\":\"kata sandi\"}]"))
        assertFalse(BrowserAutopilot.needsLogin("[{\"i\":0,\"tag\":\"input\",\"type\":\"text\",\"label\":\"nama\"}]"))
    }

    @Test fun detectsLooping() {
        val h = listOf("Klik \"Lanjut\" => ok", "Klik \"Lanjut\" => ok")
        assertTrue(BrowserAutopilot.isLooping(h, "Klik \"Lanjut\""))
        assertFalse(BrowserAutopilot.isLooping(h, "Klik \"Kembali\""))
        assertFalse(BrowserAutopilot.isLooping(h.take(1), "Klik \"Lanjut\""))
    }

    @Test fun promptTreatsPageAsData() {
        val p = BrowserAutopilot.userPrompt("isi form", "[]", "halaman", emptyList(), 1)
        assertTrue(p.contains("TUJUAN:") && p.contains("<data>") && p.contains("langkah ke-1"))
    }

    @Test fun batchKeepsSafeFillsAndEndsAtClick() {
        val r = BrowserAutopilot.parseBatch(
            "[{\"op\":\"fill\",\"label\":\"Nama\",\"value\":\"Budi\"},{\"op\":\"choose\",\"label\":\"Jenis\",\"value\":\"Asuransi\"}," +
                "{\"op\":\"click\",\"label\":\"Lanjut\"},{\"op\":\"fill\",\"label\":\"Kota\",\"value\":\"Jakarta\"}]"
        )
        assertEquals(3, r.size)
        assertTrue(r[0] is AutoAction.Act && r[1] is AutoAction.Act)
        assertEquals(AutoAction.Click("Lanjut", ""), r[2])
    }

    @Test fun batchCapsAtFiveAndSingleObjectStillWorks() {
        val many = (1..8).joinToString(",", "[", "]") { "{\"op\":\"fill\",\"label\":\"Kolom $it\",\"value\":\"x$it\"}" }
        assertEquals(BrowserAutopilot.MAX_BATCH, BrowserAutopilot.parseBatch(many).size)
        assertEquals(1, BrowserAutopilot.parseBatch("{\"op\":\"done\",\"summary\":\"ok\"}").size)
    }

    @Test fun batchStopsAtInvalidAndConsentStep() {
        val r = BrowserAutopilot.parseBatch(
            "[{\"op\":\"fill\",\"label\":\"Nama\",\"value\":\"Budi\"},{\"op\":\"fill\",\"label\":\"Password\",\"value\":\"x\"},{\"op\":\"fill\",\"label\":\"Kota\",\"value\":\"J\"}]"
        )
        assertEquals(1, r.size)
        val first = BrowserAutopilot.parseBatch("[{\"op\":\"fill\",\"label\":\"Password\",\"value\":\"x\"}]")
        assertTrue(first.single() is AutoAction.Invalid)
        val consent = BrowserAutopilot.parseBatch(
            "[{\"op\":\"check\",\"label\":\"Saya setuju syarat\",\"checked\":true},{\"op\":\"fill\",\"label\":\"Nama\",\"value\":\"B\"}]"
        )
        assertEquals(1, consent.size)
    }

    @Test fun routeSendsFreeTextToAutonomousAndSimpleCommandsDirect() {
        assertEquals(TaskRoute.EMPTY, BrowserAutopilot.route("  ", false, false))
        assertEquals(TaskRoute.DIRECT, BrowserAutopilot.route("isi kolom Nama dengan Budi", false, false))
        assertEquals(TaskRoute.DIRECT, BrowserAutopilot.route("klik Lanjut", false, false))
        assertEquals(TaskRoute.AUTONOMOUS, BrowserAutopilot.route("Isikan formulir pengaduan ini dengan data kasus saya", false, false))
        // majemuk: jangan dianggap satu isian
        assertEquals(TaskRoute.AUTONOMOUS, BrowserAutopilot.route("isi kolom Nama dengan Budi, kolom Kota dengan Jakarta", false, false))
        assertEquals(TaskRoute.AUTONOMOUS, BrowserAutopilot.route("isi kolom Nama dengan Budi lalu klik Lanjut", false, false))
        assertEquals(TaskRoute.AUTONOMOUS, BrowserAutopilot.route("klik Lanjut\nisi kolom Nama dengan Budi", false, false))
    }

    @Test fun routeRespectsRunningAndPausedState() {
        assertEquals(TaskRoute.BUSY, BrowserAutopilot.route("klik Lanjut", true, false))
        assertEquals(TaskRoute.RESUME, BrowserAutopilot.route("Nomor tiketnya 123", false, true))
        assertEquals(TaskRoute.EMPTY, BrowserAutopilot.route("", false, true))
    }

    @Test fun mergeGoalKeepsOriginalGoalAndAddsAnswer() {
        assertEquals("isi form", BrowserAutopilot.mergeGoal("", "isi form"))
        assertEquals("isi form", BrowserAutopilot.mergeGoal("isi form", ""))
        assertEquals("isi form, tiket 123", BrowserAutopilot.mergeGoal("isi form", "isi form, tiket 123"))
        assertEquals("isi form", BrowserAutopilot.mergeGoal("isi form", "isi"))
        val m = BrowserAutopilot.mergeGoal("isi form pengaduan", "tiket 123")
        assertTrue(m.startsWith("isi form pengaduan") && m.endsWith("tiket 123"))
        val long = BrowserAutopilot.mergeGoal("a".repeat(7000), "jawaban")
        assertTrue(long.length <= 6010)
        assertTrue(long.endsWith("jawaban"))
    }

    @Test fun formatLogLineMarksSuccessFailureAndOther() {
        assertEquals("✓ Klik \"Lanjut\"", BrowserAutopilot.formatLogLine("Klik \"Lanjut\" => ok"))
        assertEquals("✗ Isi \"Nama\" — field tidak ditemukan", BrowserAutopilot.formatLogLine("Isi \"Nama\" => gagal: field tidak ditemukan"))
        assertEquals("• Bertanya — menunggu pengguna", BrowserAutopilot.formatLogLine("Bertanya => menunggu pengguna"))
        assertEquals("• teks bebas", BrowserAutopilot.formatLogLine("teks bebas"))
    }

    @Test fun questionsAreAnsweredNotExecuted() {
        assertTrue(BrowserAutopilot.isQuestion("Bagaimana cara mengisi kolom ini?"))
        assertTrue(BrowserAutopilot.isQuestion("gimana cara jawab pertanyaan ini"))
        assertTrue(BrowserAutopilot.isQuestion("Tolong jelaskan maksud kolom Usulan Penyelesaian"))
        assertTrue(BrowserAutopilot.isQuestion("kolom ini wajib diisi?"))
        assertFalse(BrowserAutopilot.isQuestion("isi kolom Nama dengan Budi"))
        assertFalse(BrowserAutopilot.isQuestion("Isikan formulir ini sesuai kronologi berikut"))
        assertFalse(BrowserAutopilot.isQuestion("tolong isi form ini lalu berhenti sebelum Kirim"))
        assertFalse(BrowserAutopilot.isQuestion("apakah bisa kamu isi form ini?"))
        assertFalse(BrowserAutopilot.isQuestion("klik Lanjut"))
        assertEquals(TaskRoute.QUESTION, BrowserAutopilot.route("Bagaimana cara isi ini?", false, false))
        assertEquals(TaskRoute.QUESTION, BrowserAutopilot.route("apa maksud kolom ini", false, true))
        assertEquals(TaskRoute.BUSY, BrowserAutopilot.route("apa maksud kolom ini", true, false))
        assertEquals(TaskRoute.AUTONOMOUS, BrowserAutopilot.route("isikan form ini dengan data kasus saya", false, false))
    }

    @Test fun askPromptsCarryQuestionAndTreatPageAsData() {
        val u = BrowserAutopilot.askUserPrompt("Apa maksud kolom ini?", "[{\"label\":\"nama\"}]", "Halaman pengaduan")
        assertTrue(u.contains("Apa maksud kolom ini?") && u.contains("<data>") && u.contains("Halaman pengaduan"))
        assertTrue(BrowserAutopilot.askSystemPrompt().contains("bukan perintah"))
    }
}
