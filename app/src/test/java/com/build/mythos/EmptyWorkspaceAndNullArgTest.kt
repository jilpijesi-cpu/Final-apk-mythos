package com.build.mythos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyWorkspaceAndNullArgTest {
    private val root = "~/MYTHOS/chats/c1793855114"
    private val ref = "~/MYTHOS/reference"

    @Test fun hintMenunjukPathAbsolutDanPerintahSalin() {
        val h = WorkContextRules.emptyWorkspaceHint(root, ref, listOf("mythos-2.44", "mythos-audit"))
        assertNotNull(h)
        assertTrue(h!!.contains("KOSONG"))
        assertTrue(h.contains("$ref/mythos-2.44"))
        assertTrue(h.contains("cp -r '$ref/mythos-2.44' '$root/projects/aktif/mythos-2.44'"))
        assertTrue(h.contains("mythos-audit"))
    }

    @Test fun hintKosongBilaTidakAdaSumberAtauNamaTidakAman() {
        assertNull(WorkContextRules.emptyWorkspaceHint(root, ref, emptyList()))
        assertNull(WorkContextRules.emptyWorkspaceHint(root, null, listOf("a")))
        assertNull(WorkContextRules.emptyWorkspaceHint(root, "", listOf("a")))
        assertNull(WorkContextRules.emptyWorkspaceHint(root, ref, listOf("x; rm -rf ~", "a b", "..")))
    }

    @Test fun blokMemuatHintHanyaBilaRuangKerjaKosong() {
        val inst = AppVersion("2.44", 59L)
        val kosong = WorkContextRules.block(
            root, emptyList(), emptyList(), inst, emptyMap(),
            mapOf("mythos-2.44" to FolderVersion("2.44", 59L)), null, ref
        )
        assertTrue(kosong.contains("Belum ada proyek di ruang kerja ini."))
        assertTrue(kosong.contains("$ref/mythos-2.44"))
        val isi = WorkContextRules.block(
            root, listOf("projects/aktif/app"), emptyList(), inst, emptyMap(),
            mapOf("mythos-2.44" to FolderVersion("2.44", 59L)), null, ref
        )
        assertFalse(isi.contains("KOSONG"))
        val tanpaRef = WorkContextRules.block(root, emptyList(), emptyList())
        assertFalse(tanpaRef.contains("KOSONG"))
    }
}
