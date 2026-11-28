package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTitleTest {
    @Test fun lsMenyebutFolderYangDilihat() {
        assertEquals(
            "Melihat daftar file dan folder di patok-logo-patched-1",
            ccToolTitle("LS", "", """LS {"path":"uploads/patok-logo-patched-1"}""")
        )
        assertEquals("Melihat daftar file dan folder di proyek", ccToolTitle("LS", "", """LS {"path":"."}"""))
    }

    @Test fun globMenyebutPolaDanLokasi() {
        val t = ccToolTitle("Glob", "", """Glob {"pattern":"**/*Test.kt","path":"app/src"}""")
        assertEquals("Mencari file berpola **/*Test.kt di folder src", t)
    }

    @Test fun grepMenyebutKataYangDicari() {
        val t = ccToolTitle("Grep", "", """Grep {"pattern":"pulseDuration","glob":"*.kt"}""")
        assertEquals("Mencari \"pulseDuration\" di dalam file *.kt", t)
    }

    @Test fun readMenyebutBerkas() {
        assertEquals(
            "Membaca isi Palette.kt untuk memahami kodenya",
            ccToolTitle("Read", "", """Read {"file_path":"app/src/main/Palette.kt"}""")
        )
        assertTrue(ccToolTitle("Read", "", """Read {"file_path":"a/B.kt","offset":401}""").contains("baris 401"))
    }

    @Test fun alatLainMenyebutSasaran() {
        assertEquals("Memindahkan a.txt ke b.txt", ccToolTitle("Move", "", "Move x/a.txt -> y/b.txt"))
        assertEquals("Mencari di web tentang \"gemini 4 thinking\"", ccToolTitle("WebSearch", "", "WebSearch gemini 4 thinking"))
        assertEquals("Membaca halaman web dari ai.google.dev", ccToolTitle("WebFetch", "", "WebFetch https://ai.google.dev/docs"))
    }

    @Test fun alatTakDikenalTidakHanyaNamaAlat() {
        assertEquals("Menjalankan Foo: {\"a\":1}", ccToolTitle("Foo", "", """Foo {"a":1}"""))
        assertEquals("Menjalankan alat Foo", ccToolTitle("Foo", "", "Foo"))
    }

    @Test fun selaluSatuBarisDanTidakKepanjangan() {
        val long = "x".repeat(300)
        val t = ccToolTitle("Grep", "", """Grep {"pattern":"$long","path":"${"d/".repeat(60)}"}""")
        assertFalse(t.contains('\n'))
        assertTrue(t.length <= 64)
        assertTrue(ccToolTitle("Foo", "", "Foo\n\n{\"a\":\n1}").none { it == '\n' })
    }
}
