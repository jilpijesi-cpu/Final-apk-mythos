package com.mythos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Jenis berkas untuk kartu: nama jenis di baris kedua, ikon, warna kotak ikon, dan apakah ketukan kartu = bagikan (tak ada aplikasi pembuka yang pasti). */
internal enum class FileKind(
    val label: String?,        // null = baris kedua hanya ekstensi (mis. "PATCH")
    val icon: ImageVector,
    val tint: Color,
    val bg: Color,
    val border: Color,
    val shareOnTap: Boolean,
) {
    PDF("Dokumen", Icons.Outlined.PictureAsPdf, Color(0xFF1F5FA8), Color(0xFFE3EEFB), Color(0xFFBFD6F2), false),
    CODE("Kode", Icons.Outlined.Code, Color(0xFF8A4B00), Color(0xFFFBEBD0), Color(0xFFF0CD8A), true),
    PATCH(null, Icons.Outlined.ContentCopy, Color(0xFF55534F), Color.White, Color(0xFFDADAD8), true),
    DOC("Dokumen", Icons.Outlined.Description, Color(0xFF1F5FA8), Color(0xFFE3EEFB), Color(0xFFBFD6F2), false),
    SHEET("Tabel", Icons.Outlined.TableChart, Color(0xFF1B7F3B), Color(0xFFE2F3E7), Color(0xFFB5DEC1), false),
    SLIDES("Presentasi", Icons.Outlined.Slideshow, Color(0xFFB4501F), Color(0xFFFCE7DC), Color(0xFFF0C3AA), false),
    IMAGE("Gambar", Icons.Outlined.Image, Color(0xFF7A3FB5), Color(0xFFF0E6FA), Color(0xFFD9C3F0), false),
    AUDIO("Audio", Icons.Outlined.MusicNote, Color(0xFF9B2F6B), Color(0xFFFAE4F0), Color(0xFFEBBBD6), false),
    VIDEO("Video", Icons.Outlined.Videocam, Color(0xFF9B2F6B), Color(0xFFFAE4F0), Color(0xFFEBBBD6), false),
    ARCHIVE("Arsip", Icons.Outlined.FolderZip, Color(0xFF6B5300), Color(0xFFFBF1CC), Color(0xFFEBD98A), true),
    OTHER("Berkas", Icons.Outlined.Description, Color(0xFF55534F), Color(0xFFEFEEEA), Color(0xFFDADAD8), true),
}

private val CODE_EXT = setOf(
    "kt", "kts", "java", "js", "mjs", "ts", "tsx", "jsx", "py", "html", "htm", "css", "scss", "json", "xml", "sh", "bash", "c", "h", "cpp",
    "hpp", "cs", "go", "rs", "php", "rb", "swift", "gradle", "yml", "yaml", "sql", "toml", "properties", "ini", "lua", "dart", "vue",
)

internal fun fileExt(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

internal fun fileKindOf(path: String): FileKind = when (val e = fileExt(path)) {
    "pdf" -> FileKind.PDF
    "patch", "diff" -> FileKind.PATCH
    in CODE_EXT -> FileKind.CODE
    "doc", "docx", "txt", "md", "rtf", "odt" -> FileKind.DOC
    "xls", "xlsx", "csv", "tsv", "ods" -> FileKind.SHEET
    "ppt", "pptx", "odp" -> FileKind.SLIDES
    "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "heic" -> FileKind.IMAGE
    "mp3", "wav", "ogg", "m4a", "flac", "aac" -> FileKind.AUDIO
    "mp4", "mkv", "webm", "mov", "avi" -> FileKind.VIDEO
    "zip", "tar", "gz", "tgz", "7z", "rar" -> FileKind.ARCHIVE
    else -> FileKind.OTHER
}

/** Baris kedua kartu: "Kode · KT", "Dokumen · PDF", "PATCH". */
internal fun fileSubtitle(path: String): String {
    val kind = fileKindOf(path)
    val ext = fileExt(path).uppercase()
    return when {
        kind.label == null -> ext.ifEmpty { "BERKAS" }
        ext.isEmpty() -> kind.label.orEmpty()
        else -> "${kind.label} · $ext"
    }
}

/** "uploads/Shimmer-size.patch" -> "Shimmer size", "Design.kt" -> "Design", "Bukti_Pengaduan_OJK.pdf" -> "Bukti Pengaduan OJK". */
internal fun prettyFileName(path: String): String {
    val name = path.substringAfterLast('/')
    val base = if (name.contains('.') && !name.startsWith(".")) name.substringBeforeLast('.') else name
    val spaced = base.replace('_', ' ').replace('-', ' ').trim().ifEmpty { name }
    return spaced.replaceFirstChar { it.uppercase() }
}

/**
 * Kartu berkas: kotak ikon sesuai jenis di kiri, nama (tanpa ekstensi), jenis · ekstensi, dan tombol bagikan (ikon unggah) di kanan.
 * Ketuk kartu untuk membuka (atau membagikan bila jenisnya tak punya aplikasi pembuka pasti).
 */
@Composable
internal fun FileCard(path: String, onOpen: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val kind = fileKindOf(path)
    val title = prettyFileName(path)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .border(1.dp, MythosColors.Border, shape)
            .clickable(onClickLabel = if (kind.shareOnTap) "Bagikan $title" else "Buka $title", role = Role.Button, onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(kind.bg).border(1.dp, kind.border, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(kind.icon, contentDescription = null, tint = kind.tint) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, color = MythosColors.Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(2.dp))
            Text(fileSubtitle(path), style = TextStyle(fontFamily = MythosSans, fontSize = 13.sp, color = MythosColors.Hint), maxLines = 1)
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Unduh atau bagikan $title", role = Role.Button, onClick = onShare),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Outlined.IosShare, contentDescription = null, tint = MythosColors.Ink) }
    }
}
