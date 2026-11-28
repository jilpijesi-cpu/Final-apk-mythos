package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ikon langkah agen: terminal untuk perintah umum, buku untuk membaca, jam untuk menunggu/waktu, dst. */
class ActionIconsTest {
    private fun cmd(command: String) = iconForCommand("run_command", command)
    private fun desc(text: String, command: String = "") = iconForCommand("run_command", command, text)

    @Test fun perintahUmumTanpaPolaPakaiIkonTerminal() {
        assertEquals(IconView.TERMINAL, cmd("foo bar"))
        assertEquals(IconView.TERMINAL, cmd(""))
    }

    @Test fun membacaFilePakaiIkonBuku() {
        assertEquals(IconView.BOOK, cmd("cat a.txt"))
        assertEquals(IconView.BOOK, cmd("head -n 20 Foo.kt"))
    }

    @Test fun menungguDanWaktuPakaiIkonJam() {
        assertEquals(IconView.CLOCK, cmd("sleep 5"))
        assertEquals(IconView.CLOCK, cmd("date"))
        assertEquals(IconView.CLOCK, desc("Menunggu jawaban pengguna"))
        assertEquals(IconView.CLOCK, desc("Memeriksa waktu"))
        assertEquals(IconView.CLOCK, desc("Memeriksa tanggal dan jam saat ini"))
    }

    @Test fun jenisAksiLainTetapBenar() {
        assertEquals(IconView.GEAR, cmd("./gradlew assembleDebug"))
        assertEquals(IconView.FOLDER, cmd("ls"))
        assertEquals(IconView.GRID, cmd("grep -rn foo ."))
        assertEquals(IconView.TRASH, cmd("rm file"))
        assertEquals(IconView.NEW, cmd("echo hi > out.txt"))
        assertEquals(IconView.DOWNLOAD, cmd("pip install x"))
    }

    @Test fun deskripsiModelBerbahasaInggrisJugaDikenali() {
        assertEquals(IconView.GEAR, desc("Rebuild both sample documents"))
        assertEquals(IconView.BOOK, desc("Check evidence cover page"))
        assertEquals(IconView.GEAR, desc("Building a stress test with every image type"))
    }

    @Test fun namaBerkasTidakMemicuAturanYangSalah() {
        assertEquals(IconView.BOOK, desc("Membaca build.gradle.kts"))
        assertEquals(IconView.BOOK, desc("Membaca isi app/src/zip-utils.kt"))
        assertEquals("membaca", stripFileNames("membaca build.gradle.kts").trim())
    }

    @Test fun melihatIsiFolderPakaiIkonFolder() {
        assertEquals(IconView.FOLDER, desc("Melihat isi folder"))
    }

    @Test fun mengujiPakaiCentang() {
        assertEquals(IconView.CHECK, desc("Menguji hasil perbaikan"))
    }

    @Test fun deskripsiTanpaPolaJatuhKePerintahLaluTerminal() {
        assertEquals(IconView.TERMINAL, desc("Merapikan spasi nyasar dan jarak antar baris"))
        assertEquals(IconView.GRID, desc("Merapikan spasi nyasar", "grep -rn foo ."))
    }
}
