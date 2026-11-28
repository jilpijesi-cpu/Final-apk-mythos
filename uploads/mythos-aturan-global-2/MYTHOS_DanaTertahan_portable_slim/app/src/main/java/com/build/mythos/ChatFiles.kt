package com.build.mythos

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// File yang diserahkan agen lewat alat Present: disalin ke penyimpanan chat, tampil sebagai kartu unduh di layar chat
// (Buka / Unduh / Bagikan), dan tercatat di riwayat (Hist.files) supaya kartu pulih saat chat dibuka ulang.

/** File yang diserahkan dalam tugas yang sedang berjalan; dikosongkan saat tugas selesai ([drainDeliveredFiles]). */
private val pendingDelivered = CopyOnWriteArrayList<HistFile>()

internal fun drainDeliveredFiles(): List<HistFile> {
    val l = pendingDelivered.toList()
    pendingDelivered.clear()
    return l
}

internal fun clearDeliveredFiles() = pendingDelivered.clear()

/** Folder penyimpanan file serah-terima. Chat samaran memakai cache (ikut hilang bersama cache). */
internal fun MainActivity.chatFilesRoot(incognitoMode: Boolean): File = File(if (incognitoMode) cacheDir else filesDir, "chat_files")

/** Salin [src] ke penyimpanan chat dan catat untuk kartu. Mengembalikan null bila berhasil, atau pesan galat. */
internal fun MainActivity.deliverFile(src: File, shownName: String): String? {
    return try {
        if (!src.isFile) return "file tidak ada"
        val id = sanitizeChatId(currentChatId())
        val dir = File(chatFilesRoot(incognito), id).apply { mkdirs() }
        val safe = WorkspaceList.cacheName(shownName.ifBlank { src.name })
        val out = reserveUniqueAttachmentFile(dir, safe)
        try {
            src.copyTo(out, overwrite = true)
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        pendingDelivered.add(HistFile(id + "/" + out.name, out.name, out.length()))
        null
    } catch (e: Exception) {
        logQuiet("ChatFiles.kt", e)
        "gagal menyalin file: " + (e.message ?: e.javaClass.simpleName).take(100)
    }
}

/** File sebenarnya untuk [f] (null bila sudah dihapus); dijaga agar tidak keluar dari folder chat_files. */
internal fun MainActivity.resolveDelivered(f: HistFile): File? {
    for (root in listOf(chatFilesRoot(false), chatFilesRoot(true))) {
        val x = File(root, f.rel)
        try {
            if (x.isFile && x.canonicalPath.startsWith(root.canonicalPath + File.separator)) return x
        } catch (e: Exception) { logQuiet("ChatFiles.kt", e) }
    }
    return null
}

private fun MainActivity.deliveredUri(f: HistFile): Uri {
    val b = Uri.Builder().scheme("content").authority("$packageName.files")
    for (seg in f.rel.split('/')) if (seg.isNotEmpty()) b.appendPath(seg)
    return b.build()
}

/** Hapus semua file serah-terima milik chat [chatId] (dipanggil saat chat dihapus). */
internal fun MainActivity.deleteDeliveredFor(chatId: String) {
    val id = sanitizeChatId(chatId)
    for (root in listOf(chatFilesRoot(false), chatFilesRoot(true))) {
        try { File(root, id).deleteRecursively() } catch (e: Exception) { logQuiet("ChatFiles.kt", e) }
    }
}

/** Sapu folder serah-terima milik chat yang sudah tidak ada. */
internal fun MainActivity.sweepOrphanDelivered(keep: Set<String>) {
    val names = chatFilesRoot(false).list()?.toList() ?: return
    for (n in orphanProjectDirs(names, keep)) {
        try { File(chatFilesRoot(false), n).deleteRecursively() } catch (e: Exception) { logQuiet("ChatFiles.kt", e) }
    }
}

/** Tampilkan kartu unduh di chat (dipanggil setelah jawaban akhir agen, atau saat chat dipulihkan). */
internal fun MainActivity.showFileCards(files: List<HistFile>) {
    if (files.isEmpty()) return
    runOnUiThread {
        for (f in files) insertBeforeStatus(fileCardView(f), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        scrollDown()
    }
}

private data class FileCardVisual(
    val subtitle: String,
    val glyph: Int,
    val fill: Int,
    val border: Int,
    val iconColor: Int
)

private val WEB_EXTS = setOf("html", "htm")
private val CODE_EXTS = setOf(
    "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "c", "cpp", "h", "hpp",
    "cs", "go", "rs", "rb", "php", "swift", "sh", "bash", "zsh", "css", "scss",
    "gradle", "bat", "ps1"
)
private val DATA_EXTS = setOf("json", "xml", "yaml", "yml", "toml", "ini", "properties", "pro", "sql")
private val MD_EXTS = setOf("md", "markdown")
private val TEXT_EXTS = setOf("txt", "log", "rtf")
private val WORD_EXTS = setOf("doc", "docx", "odt")
private val SHEET_EXTS = setOf("xlsx", "xls", "csv", "ods")
private val SLIDE_EXTS = setOf("pptx", "ppt", "odp")
private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "bmp")
private val AUDIO_EXTS = setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")
private val VIDEO_EXTS = setOf("mp4", "mkv", "mov", "webm", "avi")
private val APK_EXTS = setOf("apk", "aab")

/** Judul kartu yang bersih: tanpa ekstensi, garis bawah/tanda hubung menjadi spasi. */
internal fun fileCardTitle(name: String): String {
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    return stem.replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { name }
}

private fun fileCardVisual(name: String): FileCardVisual {
    val extLow = name.substringAfterLast('.', "").lowercase()
    val extUp = extLow.uppercase().take(6)
    val dark = Palette.dark
    return when {
        extLow in WEB_EXTS -> FileCardVisual(
            subtitle = "Web · $extUp",
            glyph = GlyphView.GLOBE,
            fill = if (dark) 0xFF1B353D.toInt() else 0xFFE6F5FA.toInt(),
            border = if (dark) 0xFF2B5461.toInt() else 0xFFBCE3F0.toInt(),
            iconColor = if (dark) 0xFF67E8F9.toInt() else 0xFF0E7490.toInt()
        )
        extLow in CODE_EXTS -> FileCardVisual(
            subtitle = "Kode · $extUp",
            glyph = GlyphView.CODE,
            fill = if (dark) 0xFF3A3122.toInt() else 0xFFF5EBDA.toInt(),
            border = if (dark) 0xFF5C4B2E.toInt() else 0xFFE5D2AE.toInt(),
            iconColor = if (dark) 0xFFE4B96E.toInt() else 0xFF8B6220.toInt()
        )
        extLow in DATA_EXTS -> FileCardVisual(
            subtitle = "Data · $extUp",
            glyph = GlyphView.SLIDERS,
            fill = if (dark) 0xFF252742.toInt() else 0xFFECEEFE.toInt(),
            border = if (dark) 0xFF3D416E.toInt() else 0xFFCCD2FA.toInt(),
            iconColor = if (dark) 0xFFA5B4FC.toInt() else 0xFF4338CA.toInt()
        )
        extLow in MD_EXTS -> FileCardVisual(
            subtitle = "Markdown · $extUp",
            glyph = GlyphView.BUBBLE,
            fill = if (dark) 0xFF1D3532.toInt() else 0xFFE8F3F1.toInt(),
            border = if (dark) 0xFF2E544F.toInt() else 0xFFC2DFDA.toInt(),
            iconColor = if (dark) 0xFF5EEAD4.toInt() else 0xFF0F766E.toInt()
        )
        extLow in TEXT_EXTS -> FileCardVisual(
            subtitle = "Teks · $extUp",
            glyph = GlyphView.COPY,
            fill = if (dark) 0xFF2D2C29.toInt() else 0xFFF3F2EE.toInt(),
            border = if (dark) 0xFF474540.toInt() else 0xFFDCD9D0.toInt(),
            iconColor = if (dark) 0xFFD6D3D1.toInt() else 0xFF57534E.toInt()
        )
        extLow == "pdf" -> FileCardVisual(
            subtitle = "Dokumen · PDF",
            glyph = GlyphView.FILE_UP,
            fill = if (dark) 0xFF3A2422.toInt() else 0xFFFBECEB.toInt(),
            border = if (dark) 0xFF5E3734.toInt() else 0xFFEDC9C5.toInt(),
            iconColor = if (dark) 0xFFEF8F86.toInt() else 0xFFB33A2E.toInt()
        )
        extLow in WORD_EXTS -> FileCardVisual(
            subtitle = "Dokumen · $extUp",
            glyph = GlyphView.COPY,
            fill = if (dark) 0xFF222D3D.toInt() else 0xFFEAF1FC.toInt(),
            border = if (dark) 0xFF354A66.toInt() else 0xFFC7DAF5.toInt(),
            iconColor = if (dark) 0xFF8AB4F8.toInt() else 0xFF2B5CA8.toInt()
        )
        extLow in SHEET_EXTS -> FileCardVisual(
            subtitle = "Tabel · $extUp",
            glyph = GlyphView.APPS,
            fill = if (dark) 0xFF1F3628.toInt() else 0xFFE8F5EC.toInt(),
            border = if (dark) 0xFF315740.toInt() else 0xFFC2E3CC.toInt(),
            iconColor = if (dark) 0xFF7ED9A1.toInt() else 0xFF1E7A44.toInt()
        )
        extLow in SLIDE_EXTS -> FileCardVisual(
            subtitle = "Slide · $extUp",
            glyph = GlyphView.ARTIFACT,
            fill = if (dark) 0xFF3D2A1E.toInt() else 0xFFFDF0E6.toInt(),
            border = if (dark) 0xFF634430.toInt() else 0xFFF0D0B8.toInt(),
            iconColor = if (dark) 0xFFF4A874.toInt() else 0xFFC2591A.toInt()
        )
        extLow in IMAGE_EXTS -> FileCardVisual(
            subtitle = "Gambar · $extUp",
            glyph = GlyphView.IMAGE,
            fill = if (dark) 0xFF2F2642.toInt() else 0xFFF0EBFC.toInt(),
            border = if (dark) 0xFF4B3D6B.toInt() else 0xFFD6C9F5.toInt(),
            iconColor = if (dark) 0xFFC2A8FF.toInt() else 0xFF6D42C7.toInt()
        )
        extLow in AUDIO_EXTS -> FileCardVisual(
            subtitle = "Audio · $extUp",
            glyph = GlyphView.PLAY,
            fill = if (dark) 0xFF3D2130.toInt() else 0xFFFCEBF4.toInt(),
            border = if (dark) 0xFF63344E.toInt() else 0xFFF3C6DF.toInt(),
            iconColor = if (dark) 0xFFF9A8D4.toInt() else 0xFFBE185D.toInt()
        )
        extLow in VIDEO_EXTS -> FileCardVisual(
            subtitle = "Video · $extUp",
            glyph = GlyphView.PLAY,
            fill = if (dark) 0xFF332240.toInt() else 0xFFF4EBFA.toInt(),
            border = if (dark) 0xFF523666.toInt() else 0xFFDFC7F2.toInt(),
            iconColor = if (dark) 0xFFD8B4FE.toInt() else 0xFF7E22CE.toInt()
        )
        extLow in APK_EXTS -> FileCardVisual(
            subtitle = "Aplikasi · $extUp",
            glyph = GlyphView.PROJECT,
            fill = if (dark) 0xFF25361B.toInt() else 0xFFEDF7E4.toInt(),
            border = if (dark) 0xFF3C592B.toInt() else 0xFFCCE5B5.toInt(),
            iconColor = if (dark) 0xFFA3E635.toInt() else 0xFF3F7D18.toInt()
        )
        else -> FileCardVisual(
            subtitle = extUp.ifEmpty { "FILE" },
            glyph = GlyphView.COPY,
            fill = if (dark) 0xFF242321.toInt() else 0xFFFFFFFF.toInt(),
            border = if (dark) 0xFF3E3C39.toInt() else 0xFFE5E3DE.toInt(),
            iconColor = if (dark) 0xFFB8B5AE.toInt() else 0xFF4F4D49.toInt()
        )
    }
}

private fun MainActivity.showDeliveredActions(f: HistFile) {
    AlertDialog.Builder(this)
        .setTitle(f.name)
        .setItems(arrayOf("Bagikan", "Unduh ke Download", "Buka dengan aplikasi lain")) { _, which ->
            when (which) {
                0 -> openDelivered(f, share = true)
                1 -> saveDelivered(f)
                else -> openDelivered(f, share = false)
            }
        }
        .show()
}

private fun MainActivity.fileCardView(f: HistFile): View {
    val vis = fileCardVisual(f.name)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        minimumHeight = dp(78)
        background = pressable(rounded(Palette.CARD, 18, Palette.BORDER, 1))
        clipToOutline = true
        setOnClickListener { hapticTap(); openDelivered(f, share = false) }
        setOnLongClickListener { showDeliveredActions(f); true }
    }

    // Kotak ikon kiri menempel rata di tepi bawah kartu dengan sudut membulat di bagian atas saja (gaya kartu lampiran).
    val topR = dp(10).toFloat()
    val thumbBg = GradientDrawable().apply {
        setColor(vis.fill)
        setStroke(dp(1), vis.border)
        cornerRadii = floatArrayOf(topR, topR, topR, topR, 0f, 0f, 0f, 0f)
    }
    val thumb = FrameLayout(this).apply {
        background = thumbBg
        addView(
            GlyphView(context, vis.glyph, vis.iconColor).apply { strokeDp = 1.7f },
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        )
    }
    card.addView(thumb, LinearLayout.LayoutParams(dp(60), MATCH).apply {
        marginStart = dp(16)
        topMargin = dp(12)
        bottomMargin = 0
    })

    // Judul + subjudul jenis file di tengah.
    val info = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(8), dp(14))
    }
    info.addView(TextView(this).apply {
        text = fileCardTitle(f.name)
        textSize = Typo.TITLE_S
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.INK)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.15f)
    }, LinearLayout.LayoutParams(MATCH, WRAP))
    info.addView(TextView(this).apply {
        text = vis.subtitle
        textSize = Typo.BODY_S
        setTextColor(Palette.INK_SOFT)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
    card.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f).apply { gravity = Gravity.CENTER_VERTICAL })

    // Ikon ekspor/bagikan di sisi kanan.
    val actionBtn = FrameLayout(this).apply {
        contentDescription = "Bagikan atau unduh ${f.name}"
        background = pressable(rounded(Color.TRANSPARENT, 23))
        addView(
            GlyphView(context, GlyphView.SHARE, Palette.INK).apply { strokeDp = 1.7f },
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        )
        setOnClickListener { hapticTap(); showDeliveredActions(f) }
    }
    card.addView(actionBtn, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
        gravity = Gravity.CENTER_VERTICAL
        marginEnd = dp(8)
    })

    return card
}

/** Simpan ke Download/MYTHOS/export di ponsel. */
internal fun MainActivity.saveDelivered(f: HistFile) {
    val file = resolveDelivered(f)
    if (file == null) { toast("File sudah tidak ada. Minta agen membuatnya lagi."); return }
    toast("Menyimpan…")
    bg {
        try {
            val where = exportToDownloads(file, f.name)
            runOnUiThread { toast("Tersimpan di $where") }
        } catch (e: Exception) {
            runOnUiThread { toast("Gagal menyimpan: " + (e.message ?: e.javaClass.simpleName).take(100)) }
        }
    }
}

/** Buka dengan aplikasi lain ([share] false) atau bagikan ([share] true). */
internal fun MainActivity.openDelivered(f: HistFile, share: Boolean) {
    if (resolveDelivered(f) == null) { toast("File sudah tidak ada. Minta agen membuatnya lagi."); return }
    val uri = deliveredUri(f)
    val mime = WorkspaceList.mimeFor(f.name)
    try {
        if (share) {
            val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri("", uri)
            startActivity(Intent.createChooser(send, "Bagikan file").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } else {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            view.clipData = ClipData.newRawUri("", uri)
            startActivity(view)
        }
    } catch (e: ActivityNotFoundException) {
        toast("Tidak ada aplikasi untuk membuka file ini. Ketuk Unduh lalu buka dari penyimpanan.")
    }
}
