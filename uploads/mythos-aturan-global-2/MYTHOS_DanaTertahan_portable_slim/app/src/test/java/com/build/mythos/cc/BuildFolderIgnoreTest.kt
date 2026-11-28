package com.build.mythos.cc

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * Regresi: paket com.build.mythos punya folder bernama "build". Dulu alat (LS, Glob, Grep, Zip, build GitHub)
 * melewati SEMUA folder bernama build, sehingga 285 file sumber dan tes hilang dari zip buatan agen.
 */
class BuildFolderIgnoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun env(root: File) = ToolEnv(ProjectSandbox(root), SessionState(), { "" }, null, null)
    private fun run(tool: Tool, json: String, env: ToolEnv): ToolResult = runBlocking { tool.run(JSONObject(json), env) }

    /** Proyek Android mini: sumber di com/build/..., keluaran Gradle di app/build. */
    private fun androidLike(): File {
        val ws = tmp.newFolder("ws")
        val root = File(ws, "proj").also { it.mkdirs() }
        fun w(p: String, t: String = "x") { File(root, p).apply { parentFile.mkdirs() }.writeText(t) }
        w("settings.gradle.kts")
        w("app/build.gradle.kts")
        w("app/src/main/java/com/build/mythos/Main.kt", "fun main() {}")
        w("app/src/main/java/com/build/mythos/cc/Office.java", "class Office {}")
        w("app/src/test/java/com/build/mythos/MainTest.kt", "class MainTest")
        w("app/build/outputs/apk/debug/app-debug.apk", "BIN")
        w("app/build/intermediates/junk.txt")
        w("node_modules/m/i.js")
        w(".git/config")
        return root
    }

    @Test fun folderBernamaBuildDiDalamPohonSumberTidakDiabaikan() {
        val root = androidLike()
        assertFalse(isIgnoredDir(File(root, "app/src/main/java/com/build")))
        assertFalse(isIgnoredDir(File(root, "app/src/test/java/com/build")))
    }

    @Test fun keluaranGradleDiSampingBuildGradleDiabaikan() {
        val root = androidLike()
        assertTrue(isIgnoredDir(File(root, "app/build")))
    }

    @Test fun folderSampahSelaluDiabaikan() {
        val root = androidLike()
        assertTrue(isIgnoredDir(File(root, "node_modules")))
        assertTrue(isIgnoredDir(File(root, ".git")))
    }

    @Test fun buildTanpaProyekBuildDiIndukTidakDiabaikan() {
        val root = tmp.newFolder("polos")
        File(root, "build").mkdirs()
        File(root, "dist").mkdirs()
        assertFalse(isIgnoredDir(File(root, "build")))
        assertFalse(isIgnoredDir(File(root, "dist")))
    }

    @Test fun distDanTargetDiSampingMarkerDiabaikan() {
        val root = tmp.newFolder("web")
        File(root, "package.json").writeText("{}")
        File(root, "dist").mkdirs()
        val rust = tmp.newFolder("rs")
        File(rust, "Cargo.toml").writeText("")
        File(rust, "target").mkdirs()
        assertTrue(isIgnoredDir(File(root, "dist")))
        assertTrue(isIgnoredDir(File(rust, "target")))
    }

    @Test fun kitFilesMenyertakanSumberDanMencatatFolderYangDilewati() {
        val root = androidLike()
        val skipped = ArrayList<String>()
        val rel = kitFiles(root, true, skipped).map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
        assertTrue(rel.contains("app/src/main/java/com/build/mythos/Main.kt"))
        assertTrue(rel.contains("app/src/main/java/com/build/mythos/cc/Office.java"))
        assertTrue(rel.contains("app/src/test/java/com/build/mythos/MainTest.kt"))
        assertFalse(rel.any { it.startsWith("app/build/") })
        assertFalse(rel.any { it.startsWith("node_modules/") || it.startsWith(".git/") })
        assertTrue(skipped.contains("app/build"))
    }

    @Test fun renderTreeMenampilkanPaketBuild() {
        val root = androidLike()
        val tree = renderTree(root, 9, 500)
        assertTrue(tree, tree.contains("Main.kt"))
        assertTrue(tree, tree.contains("MainTest.kt"))
        assertFalse(tree, tree.contains("app-debug.apk"))
    }

    @Test fun fileBernamaBuildTanpaEkstensiTidakLagiTersembunyi() {
        val root = tmp.newFolder("p")
        File(root, "build").writeText("#!/bin/sh")
        assertTrue(renderTree(root, 2).contains("build"))
    }

    @Test fun globMenemukanKtDiBawahPaketBuild() {
        val root = androidLike()
        val r = run(Glob(), """{"pattern":"**/*.kt"}""", env(root))
        assertTrue(r.text, r.ok)
        assertTrue(r.text, r.text.contains("Main.kt"))
        assertTrue(r.text, r.text.contains("MainTest.kt"))
    }

    @Test fun zipMemuatSemuaSumberDanMelaporkanFolderYangDilewati() {
        val proj = androidLike()
        val ws = proj.parentFile
        val z = run(Zip(), """{"path":"proj","output":"out/proj.zip"}""", env(ws))
        assertTrue(z.text, z.ok)
        assertTrue(z.text, z.text.contains("Skipped build/cache folders"))
        assertTrue(z.text, z.text.contains("app/build"))
        val names = ZipFile(File(ws, "out/proj.zip")).use { zf -> zf.entries().asSequence().map { it.name }.toList() }
        assertTrue(names.contains("app/src/main/java/com/build/mythos/Main.kt"))
        assertTrue(names.contains("app/src/main/java/com/build/mythos/cc/Office.java"))
        assertTrue(names.contains("app/src/test/java/com/build/mythos/MainTest.kt"))
        assertFalse(names.any { it.endsWith(".apk") })
        assertEquals(1, names.count { it == "app/build.gradle.kts" })
    }

    @Test fun catatanFolderDilewatiKosongBilaTidakAda() {
        assertEquals("", skippedNote(emptyList()))
        assertTrue(skippedNote(listOf("a", "b")).contains("a, b"))
        assertTrue(skippedNote((1..10).map { "d$it" }).contains("+2 more"))
    }
}
