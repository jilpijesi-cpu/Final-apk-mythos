package com.build.mythos

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pemetaan alat aplikasi yang dijembatani ke mesin cc (konektor, dana tertahan, berangkas): setiap alat yang dideklarasikan
 * wajib terdaftar di ToolDocs (daftar + tanda tangan) dan punya pelaksana serta status langkah di CcBridge.kt.
 * Tes ini membaca sumber, jadi alat baru yang lupa didaftarkan langsung gagal di sini. Alat jalur lama (run_command, write_file,
 * build_apk, dst.) sudah dihapus untuk penyedia API; lihat ToolPathTest.
 */
class ToolMappingTest {
    private val dir: File? = listOf("src/main/java/com/build/mythos", "app/src/main/java/com/build/mythos", "../app/src/main/java/com/build/mythos")
        .map { File(it) }.firstOrNull { it.isDirectory }

    private fun requireDir(): File = requireNotNull(dir) { "Direktori sumber com/build/mythos tidak ditemukan" }

    private fun text(name: String): String = File(requireDir(), name).readText()

    private fun functionBody(src: String, signatureFragment: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(signatureFragment) }
        assertTrue("Fungsi $signatureFragment tidak ditemukan di CcBridge.kt", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { lines[it] == "}" } ?: -1
        assertTrue("Penutup fungsi $signatureFragment tidak ditemukan di CcBridge.kt", end > start)
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private val declared: Set<String> by lazy {
        val re = Regex("""\b(?:decl|declOpt)\(\s*"([a-z_]+)"""")
        dir!!.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { re.findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()
    }

    @Test fun adaAlatYangDideklarasikanDanSemuanyaTerbaca() {
        assertNotNull("Direktori sumber tidak ditemukan", dir)
        assertTrue("alat jembatan harus terbaca oleh pemindai: $declared", setOf("berangkas_baca", "berangkas_salin", "kasus_dana_buat", "email_kirim").all { it in declared })
    }

    @Test fun tidakAdaLagiDeklarasiAlatJalurLama() {
        assertNotNull("Direktori sumber tidak ditemukan", dir)
        val lama = setOf("run_command", "write_file", "create_android_project", "validate_project", "build_apk", "self_audit", "self_upgrade", "create_tool", "delete_tool", "ask_user")
        val sisa = declared.intersect(lama)
        assertTrue("deklarasi alat jalur lama masih ada: $sisa", sisa.isEmpty())
    }

    @Test fun setiapAlatTerdaftarDiDaftarAlatPromptDanTandaTangan() {
        assertNotNull("Direktori sumber tidak ditemukan", dir)
        val connectors = CONNECTOR_TOOL_NAMES.toSet() // konektor punya petunjuk prompt sendiri dan hanya dikirim bila aktif
        for (n in declared - connectors) {
            assertTrue("$n belum ada di ToolDocs.NAMES", n in ToolDocs.NAMES)
            assertTrue("$n belum ada di ToolDocs.SIGNATURES", ToolDocs.SIGNATURES.any { it.startsWith("$n(") })
        }
    }

    @Test fun setiapAlatPunyaPelaksanaDanStatusDiJembatanCc() {
        assertNotNull("Direktori sumber tidak ditemukan", dir)
        val bridge = text("CcBridge.kt")
        val runBody = functionBody(bridge, "fun MainActivity.ccRunBridged(")
        val iconBody = functionBody(bridge, "fun ccIconDesc(")
        val groups = mapOf(
            "CONNECTOR_TOOL_NAMES" to CONNECTOR_TOOL_NAMES.toSet(),
            "DANA_TOOL_NAMES" to DANA_TOOL_NAMES.toSet()
        )
        for (n in declared) {
            val grup = groups.entries.firstOrNull { n in it.value }?.key
            assertTrue("$n tidak punya pelaksana di CcBridge.ccRunBridged", Regex(""""$n"[^\n]*->""").containsMatchIn(runBody) || (grup != null && "in $grup ->" in runBody))
            assertTrue("$n tidak punya status langkah di CcBridge.ccIconDesc", Regex(""""$n"[^\n]*->""").containsMatchIn(iconBody) || (grup != null && "in $grup ->" in iconBody))
        }
    }
}
