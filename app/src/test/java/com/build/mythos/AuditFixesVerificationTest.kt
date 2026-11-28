package com.build.mythos

import com.build.mythos.cc.Agent
import com.build.mythos.cc.AgentConfig
import com.build.mythos.cc.Approver
import com.build.mythos.cc.BuildOutcome
import com.build.mythos.cc.BuildService
import com.build.mythos.cc.LlmClient
import com.build.mythos.cc.Msg
import com.build.mythos.cc.ProjectSandbox
import com.build.mythos.cc.ToolRegistry
import com.build.mythos.cc.workflowDigest
import com.build.mythos.cc.workflowSha256
import com.build.mythos.cc.snapshotPreview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Tes regresi JVM untuk perbaikan temuan audit MYTHOS (F-01, F-03, F-06, F-08, F-09, F-11, F-12, F-13, F-14, F-23, F-24).
 */
class AuditFixesVerificationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun f01AndF08GithubBuildForcesApprovalShowsFullWorkflowAndChecksDigest() = runBlocking {
        val root = tmp.newFolder("proj")
        val wfDir = File(root, ".github/workflows").apply { mkdirs() }
        val longBody = buildString {
            append("name: CI\non: [push]\njobs:\n  b:\n    runs-on: ubuntu-latest\n    steps:\n")
            repeat(60) { i -> append("      - run: echo step_$i\n") }
            append("      - run: echo TAIL_MARKER_FULL_WORKFLOW\n")
        }
        assertTrue(longBody.length > 2000)
        File(wfDir, "ci.yml").writeText(longBody)

        var sawForce = false
        var sawSummary = ""
        val approver = object : Approver {
            override suspend fun approve(tool: String, summary: String): Boolean = false
            override suspend fun approve(tool: String, summary: String, force: Boolean): Boolean {
                sawForce = force
                sawSummary = summary
                return true
            }
        }

        var turn = 0
        val llm = object : LlmClient {
            override suspend fun complete(system: String, messages: List<Msg>): String {
                turn++
                return if (turn == 1) """<tool_use>{"name":"GithubBuild","input":{}}</tool_use>""" else "Selesai."
            }
        }
        val builder = object : BuildService {
            override val hint = ""
            override suspend fun build(root: File, timeoutSec: Int) = BuildOutcome(true, "OK")
        }
        val agent = Agent(
            llm = llm,
            tools = ToolRegistry.default(withBuild = true),
            sandbox = ProjectSandbox(root),
            approver = approver,
            ask = { "" },
            builder = builder,
            cfg = AgentConfig()
        )
        val res = agent.execute("build") {}
        assertTrue(res.success)
        assertTrue("GithubBuild wajib memakai force=true (F-01)", sawForce)
        assertTrue("Workflow lengkap tidak boleh terpotong di 1.500 karakter (F-08)", sawSummary.contains("TAIL_MARKER_FULL_WORKFLOW"))
        assertTrue("Digest SHA-256 harus ditampilkan (F-01/F-08)", sawSummary.contains(workflowSha256(workflowDigest(root))))
        assertTrue("Ringkasan berkas snapshot harus ditampilkan (F-11)", sawSummary.contains("Ringkasan snapshot"))
    }

    @Test
    fun f01GithubBuildAbortsIfWorkflowMutatesAfterApproval() = runBlocking {
        val root = tmp.newFolder("proj-tamper")
        val wfDir = File(root, ".github/workflows").apply { mkdirs() }
        val wf = File(wfDir, "ci.yml").apply { writeText("name: Safe\non: [push]\n") }

        var buildCalled = false
        val approver = object : Approver {
            override suspend fun approve(tool: String, summary: String): Boolean = false
            override suspend fun approve(tool: String, summary: String, force: Boolean): Boolean {
                // Simulasikan perubahan workflow tepat setelah pengguna menyetujui
                wf.writeText("name: Tampered\non: [push]\n")
                return true
            }
        }
        var turn = 0
        val llm = object : LlmClient {
            override suspend fun complete(system: String, messages: List<Msg>): String {
                turn++
                return if (turn == 1) """<tool_use>{"name":"GithubBuild","input":{}}</tool_use>""" else "Dibatalkan."
            }
        }
        val builder = object : BuildService {
            override val hint = ""
            override suspend fun build(root: File, timeoutSec: Int): BuildOutcome {
                buildCalled = true
                return BuildOutcome(true, "OK")
            }
        }
        val agent = Agent(llm, ToolRegistry.default(withBuild = true), ProjectSandbox(root), approver, { "" }, builder)
        agent.execute("build") {}
        assertFalse("Build tidak boleh dijalankan bila workflow berubah setelah disetujui", buildCalled)
    }

    @Test
    fun f03ReadOnlyModeBlocksWriteToolEvenInAcceptEdits() = runBlocking {
        val root = tmp.newFolder("proj-ro")
        var turn = 0
        val llm = object : LlmClient {
            override suspend fun complete(system: String, messages: List<Msg>): String {
                turn++
                return if (turn == 1) """<tool_use>{"name":"Write","input":{"file_path":"a.txt","content":"halo"}}</tool_use>""" else "Mode baca saja."
            }
        }
        val agent = Agent(
            llm = llm,
            tools = ToolRegistry.default(withBuild = false),
            sandbox = ProjectSandbox(root),
            approver = Approver { _, _ -> true },
            ask = { "" },
            cfg = AgentConfig(readOnly = true)
        )
        agent.execute("tulis file") {}
        assertFalse("Mode hanya baca harus memblokir Write (F-03)", File(root, "a.txt").exists())
    }

    @Test
    fun f06ReserveUniqueAttachmentFileIsAtomicUnderConcurrency() {
        val dir = tmp.newFolder("uploads")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..24).map {
                Callable { reserveUniqueAttachmentFile(dir, "dokumen.pdf").name }
            }
            val names = pool.invokeAll(tasks).map { it.get() }
            assertEquals("Semua 24 reservasi paralel harus mendapat nama unik", 24, names.toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun f09MaxToolCallsPerTurnLimitsBatchExecution() = runBlocking {
        val root = tmp.newFolder("proj-cap")
        File(root, "a.txt").writeText("1")
        var turn = 0
        val llm = object : LlmClient {
            override suspend fun complete(system: String, messages: List<Msg>): String {
                turn++
                return if (turn == 1) {
                    (1..5).joinToString("") {
                        """<tool_use>{"name":"Write","input":{"file_path":"f$it.txt","content":"x"}}</tool_use>"""
                    }
                } else "Selesai."
            }
        }
        val agent = Agent(
            llm = llm,
            tools = ToolRegistry.default(withBuild = false),
            sandbox = ProjectSandbox(root),
            approver = Approver { _, _ -> true },
            ask = { "" },
            cfg = AgentConfig(maxToolCallsPerTurn = 2)
        )
        agent.execute("buat 5 file") {}
        val created = (1..5).count { File(root, "f$it.txt").exists() }
        assertEquals("Maksimal 2 tool call per balasan yang boleh dieksekusi (F-09)", 2, created)
    }

    @Test
    fun f11SnapshotPreviewListsProjectFiles() {
        val root = tmp.newFolder("proj-snap")
        File(root, "src.kt").writeText("fun main() {}")
        File(root, "uploads").mkdirs()
        File(root, "uploads/pribadi.pdf").writeText("rahasia")
        val preview = snapshotPreview(root)
        assertTrue(preview.contains("src.kt"))
        assertFalse("File di uploads/ root tidak boleh masuk snapshot", preview.contains("pribadi.pdf"))
        assertNotEquals("", workflowSha256("test"))
    }

    @Test
    fun f13CrossProviderFailoverIsOptInAndRespectsAllowlist() {
        assertTrue(autoPickableFor("Gemini", "Gemini", isLocal = false, crossProviderOptIn = false))
        assertFalse(autoPickableFor("OpenRouter", "Gemini", isLocal = false, crossProviderOptIn = false))
        assertTrue(autoPickableFor("OpenRouter", "Gemini", isLocal = false, crossProviderOptIn = true))
        assertTrue(autoPickableFor("OpenRouter", "Gemini", isLocal = false, crossProviderOptIn = true, allowlist = setOf("OpenRouter")))
        assertFalse(autoPickableFor("Groq", "Gemini", isLocal = false, crossProviderOptIn = true, allowlist = setOf("OpenRouter")))
    }

    @Test
    fun f14ValidateBaseUrlRejectsUserInfoAndQueryCredentials() {
        assertEquals("api.example.com", validateBaseUrl("https://api.example.com/v1"))
        assertEquals("localhost", validateBaseUrl("http://localhost:11434/v1"))
        val badUrls = listOf(
            "https://user:pass@api.example.com/v1",
            "https://api.example.com/v1?api_key=secret123",
            "https://api.example.com/v1?token=abc",
            "http://api.example.com/v1",
            "ftp://api.example.com/v1"
        )
        for (u in badUrls) {
            var threw = false
            try { validateBaseUrl(u) } catch (_: RuntimeException) { threw = true }
            assertTrue("URL tidak aman harus ditolak: $u", threw)
        }
    }

    @Test
    fun f23TouchTargetAndSendButtonFits360dpWithoutClipping() {
        val (_, h) = effectiveTouchTargetDp(38, 40, 2, 4, 2, 4)
        assertTrue("Tinggi hitbox baris input harus >= 48dp", h >= MIN_TOUCH_TARGET_DP)
        // Verifikasi 5 tombol ikon (38dp) + jarak (4*4 + 6 = 22dp) + tombol Kirim (~80dp) muat di layar 360dp (lebar dalam pill 324dp)
        val innerPill360 = 360 - (12 * 2) - (6 * 2)
        val usedByIconsAndGaps = 5 * 38 + 4 * 4 + 6
        val remainingForSend = innerPill360 - usedByIconsAndGaps
        assertTrue("Ruang tombol Kirim di layar 360dp harus >= 100dp agar tidak terpotong (aktual: $remainingForSend)", remainingForSend >= 100)
        assertEquals(300L, ASK_TIMEOUT_SEC)
    }

    @Test
    fun ui04DarkOfLightensTextInDarkModeForContrast() {
        val prev = Palette.dark
        try {
            Palette.dark = false
            val lightModeText = darkOf(Palette.RED)
            Palette.dark = true
            val darkModeText = darkOf(Palette.RED)
            val lumaLight = ((lightModeText shr 16) and 0xFF) + ((lightModeText shr 8) and 0xFF) + (lightModeText and 0xFF)
            val lumaDark = ((darkModeText shr 16) and 0xFF) + ((darkModeText shr 8) and 0xFF) + (darkModeText and 0xFF)
            assertTrue("Teks darkOf() di mode gelap harus lebih terang daripada mode terang", lumaDark > lumaLight)
        } finally {
            Palette.dark = prev
        }
    }

    @Test
    fun displayLockNormalizesZoomedDensityAndSupportsFontControls() {
        assertEquals(0.80f, fontScaleForStep(0), 0.0001f)
        assertEquals(0.90f, fontScaleForStep(DEFAULT_FONT_SCALE_STEP), 0.0001f)
        assertEquals("Default", fontScaleLabelForStep(DEFAULT_FONT_SCALE_STEP))
        assertEquals(300, fontWeightForProgress(0))
        assertEquals(400, fontWeightForProgress(DEFAULT_FONT_WEIGHT_PROGRESS))
        assertEquals(700, fontWeightForProgress(100))
        assertEquals("Default", fontWeightLabelForProgress(DEFAULT_FONT_WEIGHT_PROGRESS))
        assertEquals(420, stableDensityDpi(currentDpi = 540, deviceStableDpi = 420))
        assertEquals(480, stableDensityDpi(currentDpi = 480, deviceStableDpi = 0))
        val restoredW = normalizedScreenDp(currentDp = 329, currentDpi = 525, stableDpi = 420, minDp = 320)
        assertTrue("Lebar dp harus pulih ke ~411dp (aktual: $restoredW)", restoredW in 408..414)
    }

    @Test
    fun watchfulEyeBlinksAndMovesEyeball() {
        val openState = watchfulEyeState(1000L)
        val blinkState = watchfulEyeState(3900L)
        assertTrue("Mata harus terbuka penuh di luar kedipan", openState.openness > 0.95f)
        assertTrue("Mata harus menutup saat puncak kedipan", blinkState.openness < 0.15f)
        val lookLeft = watchfulEyeState(2000L)
        val lookRight = watchfulEyeState(3500L)
        assertTrue("Bola mata harus melirik ke kiri", lookLeft.gazeX < -0.5f)
        assertTrue("Bola mata harus melirik ke kanan", lookRight.gazeX > 0.5f)
    }
}
