package com.build.mythos

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypoTest {
    @Test fun skalaMenaikDanTanpaSelisihSetengahSp() {
        val all = Typo.ALL
        for (i in 1 until all.size) {
            assertTrue("skala harus menaik: ${all[i - 1]} lalu ${all[i]}", all[i] > all[i - 1])
        }
        // Setiap ukuran bulat atau, paling jauh, ada selisih >= 1sp dari tetangganya; tak ada dua ukuran nyaris sama.
        for (i in 1 until all.size) assertTrue(all[i] - all[i - 1] >= 1f)
        assertTrue("ukuran terkecil terbaca", all.first() >= 12f)
    }

    /** Mencegah angka ukuran teks kembali tertulis langsung di kode; gunakan konstanta di Typo. */
    @Test fun tidakAdaTextSizeAngkaLangsung() {
        val dir = listOf("src/main/java/com/build/mythos", "app/src/main/java/com/build/mythos", "../app/src/main/java/com/build/mythos")
            .map { File(it) }.firstOrNull { it.isDirectory }
        assertNotNull("folder sumber tidak ditemukan dari direktori kerja tes", dir)
        val literal = Regex("""(?<![\w.])textSize = \d""")
        val offenders = dir!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (literal.containsMatchIn(line)) "${f.name}:${i + 1}" else null
            }.asSequence()
        }.toList()
        assertTrue("Pakai Typo.* alih-alih angka langsung: $offenders", offenders.isEmpty())
    }
}
