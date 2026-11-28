package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logo layanan di baris status live: Gmail untuk email, Telegram untuk Telegram, PDF untuk berkas .pdf, GitHub untuk build lewat GitHub Actions. */
class LogoKindTest {
    @Test fun alatEmailMemakaiLogoGmail() {
        for (t in listOf("email_daftar", "email_baca", "email_lampiran", "email_kirim"))
            assertEquals(t, KIND_LOGO_GMAIL, logoKindFor(t))
    }

    @Test fun alatTelegramMemakaiLogoTelegram() {
        assertEquals(KIND_LOGO_TELEGRAM, logoKindFor("telegram_baca"))
        assertEquals(KIND_LOGO_TELEGRAM, logoKindFor("telegram_kirim"))
    }

    @Test fun alatWhatsappMemakaiLogoWhatsapp() {
        for (t in listOf("whatsapp_kirim", "whatsapp_baca", "whatsapp_balas"))
            assertEquals(t, KIND_LOGO_WHATSAPP, logoKindFor(t))
    }

    @Test fun berkasPdfMemakaiLogoPdf() {
        assertEquals(KIND_LOGO_PDF, logoKindFor("Read", "docs/laporan.pdf"))
        assertEquals(KIND_LOGO_PDF, logoKindFor("Read", "LAPORAN.PDF"))
        assertEquals(KIND_LOGO_PDF, logoKindFor("MakeDocument", "hasil.pdf"))
        assertEquals(KIND_LOGO_PDF, logoKindFor("Present", "hasil.pdf"))
    }

    @Test fun buildGithubMemakaiLogoGithub() {
        assertEquals(KIND_LOGO_GITHUB, logoKindFor("GithubBuild"))
        assertEquals(KIND_LOGO_GITHUB, logoKindFor("build_apk"))
    }

    @Test fun perintahGitDanGhMemakaiLogoGithub() {
        for (c in listOf("git push origin main", "git clone https://github.com/a/b.git", "cd proyek && git commit -m x",
            "gh run watch", "gh auth status", "curl -s https://api.github.com/repos/a/b", "GIT status"))
            assertEquals(c, KIND_LOGO_GITHUB, logoKindFor("run_command", "", c))
        assertEquals(KIND_LOGO_GITHUB, logoKindFor("Bash", "", "git pull"))
    }

    @Test fun berkasDiFolderGithubMemakaiLogoGithub() {
        assertEquals(KIND_LOGO_GITHUB, logoKindFor("Write", ".github/workflows/build-apk.yml"))
        assertEquals(KIND_LOGO_GITHUB, logoKindFor("Edit", "app/.github/dependabot.yml"))
        assertEquals(KIND_LOGO_GITHUB, logoKindFor("Read", ".github"))
        assertEquals(KIND_LOGO_GITHUB, logoKindFor("Read", "proj\\.github\\workflows\\a.yml"))
    }

    @Test fun miripGitTapiBukanGithubTanpaLogo() {
        assertNull(logoKindFor("run_command", "", "cat .gitignore"))
        assertNull(logoKindFor("run_command", "", "ls digit/ gitlab-notes"))
        assertNull(logoKindFor("run_command", "", "cd git-repo"))
        assertNull(logoKindFor("Read", ".gitignore"))
        assertNull(logoKindFor("Read", "docs/github-notes.md"))
        assertNull(logoKindFor("Grep", ".github/x.yml")) // alat pencari tidak memicu logo
    }

    @Test fun targetDiambilDariRingkasanAlatSepertiDiAplikasi() {
        // Bentuk ringkasan nyata dari describe() tiap alat; dulu Present/Export/MakeDocument bertarget kosong -> logo PDF tidak muncul.
        assertEquals("out/laporan.pdf", logoTargetOf("Present", "Present out/laporan.pdf (+1)"))
        assertEquals("dist/report.pdf", logoTargetOf("Export", "Export dist/report.pdf"))
        assertEquals("out/laporan.pdf", logoTargetOf("MakeDocument", "MakeDocument out/laporan.pdf"))
        assertEquals("docs/a.pdf", logoTargetOf("Read", "Read {\"file_path\":\"docs/a.pdf\",\"offset\":1}"))
        assertEquals("hasil.pdf", logoTargetOf("Write", "Write hasil.pdf (1200 karakter)"))
        for ((tool, sum) in listOf("Present" to "Present out/laporan.pdf (+1)", "Export" to "Export dist/report.pdf",
            "MakeDocument" to "MakeDocument out/laporan.pdf", "Read" to "Read {\"file_path\":\"docs/a.pdf\"}"))
            assertEquals(sum, KIND_LOGO_PDF, logoKindFor(tool, logoTargetOf(tool, sum)))
        assertNull(logoKindFor("Present", logoTargetOf("Present", "Present out/laporan.docx")))
    }

    @Test fun folderGithubDariRingkasanAlat() {
        assertEquals(KIND_LOGO_GITHUB, logoKindFor("Mkdir", logoTargetOf("Mkdir", "Mkdir .github/workflows")))
        assertEquals(KIND_LOGO_GITHUB, logoKindFor("Move", logoTargetOf("Move", "Move build.yml -> .github/workflows/build.yml")))
        assertEquals(KIND_LOGO_GITHUB, logoKindFor("Edit", logoTargetOf("Edit", "Edit .github/workflows/build-apk.yml")))
        assertNull(logoKindFor("Edit", logoTargetOf("Edit", "Edit .gitignore")))
    }

    @Test fun alatLainDanBerkasLainTanpaLogo() {
        assertNull(logoKindFor("Read", "Main.kt"))
        assertNull(logoKindFor("Read", "pdfhelper.kt"))
        assertNull(logoKindFor("Grep", "a.pdf"))
        assertNull(logoKindFor("cari_web"))
    }

    @Test fun penandaLogoDikenali() {
        assertTrue(isLogoKind(KIND_LOGO_GMAIL))
        assertTrue(isLogoKind(KIND_LOGO_TELEGRAM))
        assertTrue(isLogoKind(KIND_LOGO_PDF))
        assertTrue(isLogoKind(KIND_LOGO_WHATSAPP))
        assertTrue(isLogoKind(KIND_LOGO_GITHUB))
        assertFalse(isLogoKind(KIND_DOTS))
        assertFalse(isLogoKind(KIND_HIDE))
        assertFalse(isLogoKind(IconView.GLOBE))
    }

    @Test fun berkasPdfDipindahSalinHapusMemakaiLogoPdf() {
        assertEquals(KIND_LOGO_PDF, logoKindFor("Move", logoTargetOf("Move", "Move a.pdf -> arsip/b.pdf")))
        assertEquals(KIND_LOGO_PDF, logoKindFor("Copy", logoTargetOf("Copy", "Copy laporan.PDF -> cadangan.pdf")))
        assertEquals(KIND_LOGO_PDF, logoKindFor("Delete", logoTargetOf("Delete", "Delete lama.pdf")))
        assertNull(logoKindFor("Move", logoTargetOf("Move", "Move a.txt -> b.txt")))
    }

    @Test fun webFetchGithubDanPdf() {
        assertEquals(KIND_LOGO_GITHUB, logoKindFor("WebFetch", logoTargetOf("WebFetch", "WebFetch https://github.com/a/b")))
        assertEquals(KIND_LOGO_GITHUB, logoKindFor("WebFetch", logoTargetOf("WebFetch", "WebFetch https://raw.githubusercontent.com/a/b/main/x.kt")))
        assertEquals(KIND_LOGO_GITHUB, logoKindFor("WebFetch", logoTargetOf("WebFetch", "WebFetch https://api.github.com/repos/a/b")))
        assertEquals(KIND_LOGO_PDF, logoKindFor("WebFetch", logoTargetOf("WebFetch", "WebFetch https://situs.id/dok/laporan.pdf?dl=1")))
        assertNull(logoKindFor("WebFetch", logoTargetOf("WebFetch", "WebFetch https://contoh.com/github.com-tips")))
        assertNull(logoKindFor("WebFetch", logoTargetOf("WebFetch", "WebFetch https://notgithub.com/a")))
        assertNull(logoKindFor("WebSearch", logoTargetOf("WebSearch", "WebSearch github actions")))
    }

    @Test fun pdfLampiranDibacaOtomatisMemicuLogoPdf() {
        val blok = buildAttachmentBlock(
            listOf(AttDigest("laporan.pdf", "pdf", "isi laporan"), AttDigest("catatan.txt", "teks", "halo"), AttDigest("kedua.pdf", "pdf", "isi lain")),
            10_000
        )
        assertEquals(listOf("laporan.pdf", "kedua.pdf"), pdfNamesInAttachmentBlock(blok))
        assertEquals("Membaca laporan.pdf", pdfReadingTitle(listOf("laporan.pdf")))
        assertEquals("Membaca 2 berkas PDF", pdfReadingTitle(listOf("a.pdf", "b.pdf")))
        assertEquals("", pdfReadingTitle(emptyList()))
        assertTrue(pdfNamesInAttachmentBlock("").isEmpty())
        assertTrue(pdfNamesInAttachmentBlock(buildAttachmentBlock(listOf(AttDigest("a.txt", "teks", "x")), 10_000)).isEmpty())
    }
}
