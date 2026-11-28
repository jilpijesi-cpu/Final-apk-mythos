package com.build.mythos

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Satu jalur alat: penyedia API bekerja lewat mesin cc (CcBridge.kt), penyedia CLI lewat cliChat (Termux).
 * Tes ini membaca sumber supaya jalur lama (loop alat + runTool + deklarasi alat Termux) tidak muncul lagi di jalur API.
 */
class ToolPathTest {
    private val dir: File? = listOf("src/main/java/com/build/mythos", "app/src/main/java/com/build/mythos", "../app/src/main/java/com/build/mythos")
        .map { File(it) }.firstOrNull { it.isDirectory }

    private fun text(name: String): String = File(dir!!, name).readText()

    @Test fun pelaksanaAlatJalurLamaSudahTidakAda() {
        assumeTrue(dir != null)
        val semua = dir!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.name to it.readText() }.toList()
        for ((nama, isi) in semua) assertFalse("$nama masih mendefinisikan runTool", Regex("""fun\s+MainActivity\.runTool\s*\(""").containsMatchIn(isi))
    }

    @Test fun agentLoopHanyaMemilihCliAtauCcDanSapaanTanpaAlat() {
        assumeTrue(dir != null)
        val loop = text("AgentLoop.kt")
        assertTrue("CLI harus ke cliChat", loop.contains("cliChat(userMsg)"))
        assertTrue("API harus ke ccAgentLoop", loop.contains("ccAgentLoop(userMsg, addUser, images, extra)"))
        assertTrue("sapaan API harus tanpa definisi alat", loop.contains("noToolsCall = true"))
        assertFalse("loop alat lama tidak boleh ada di jalur API", loop.contains("runTool("))
        assertFalse("loop alat lama tidak boleh ada di jalur API", loop.contains("callModelRetry(") && loop.contains("turn.calls"))
    }

    @Test fun penyediaApiTidakPernahMengirimDefinisiAlatLama() {
        assumeTrue(dir != null)
        for (f in listOf("ProviderGemini.kt", "ProviderOpenAI.kt", "ProviderAnthropic.kt")) {
            val isi = text(f)
            assertFalse("$f masih mengirim \"tools\"", isi.contains("put(\"tools\""))
            assertFalse("$f masih memakai declList()", isi.contains("declList()"))
        }
    }

    @Test fun hanyaPenyediaCliMenyentuhTermux() {
        assertTrue(usesTermuxWorld("cli"))
        for (fmt in listOf("openai", "anthropic", "gemini")) assertFalse(usesTermuxWorld(fmt))
    }

    @Test fun sisaPenjagaJalurLamaSudahDihapus() {
        assumeTrue(dir != null)
        val semua = dir!!.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.map { it.name to it.readText() }.toList()
        val terlarang = listOf(
            "HardLimits", "ReadLedger", "DownloadLedger", "ReferencePolicy", "SessionShell", "StudioProject",
            "ErrorSummaryLog", "RepeatDetector", "WriteTracker", "ErrorTracker", "needsPlanFirst", "detectToolCodeInShell", "nullArgProblem"
        )
        for (nama in terlarang) for ((berkas, isi) in semua)
            assertFalse("$berkas masih menyebut sisa jalur lama $nama", Regex("""\b$nama\b""").containsMatchIn(isi))
    }
}
