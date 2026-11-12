package com.mythos.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.sp
import com.mythos.app.R
import kotlinx.coroutines.delay

/** Teks seluruh draf untuk tombol salin. */
internal fun emailDraftText(d: EmailDraftItem): String = buildString {
    append("Kepada: ").append(d.to.joinToString())
    if (d.cc.isNotEmpty()) append("\nCc: ").append(d.cc.joinToString())
    append("\nSubjek: ").append(d.subject).append("\n\n").append(d.body)
}

private val EMAIL_ADDR = Regex("""^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$""")

/** Pecah isi kolom Kepada/Cc (dipisah koma, titik koma, spasi, atau baris baru) menjadi daftar alamat unik. */
internal fun parseRecipients(text: String): List<String> =
    text.split(',', ';', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** Alamat yang bentuknya tidak valid; kosong = semua valid. */
internal fun invalidRecipients(list: List<String>): List<String> = list.filterNot { EMAIL_ADDR.matches(it) }

/** Pesan galat untuk hasil edit, atau null bila boleh disimpan. */
internal fun emailEditError(to: List<String>, cc: List<String>, subject: String, body: String): String? = when {
    to.isEmpty() -> "Kolom Kepada tidak boleh kosong."
    invalidRecipients(to).isNotEmpty() -> "Alamat Kepada tidak valid: " + invalidRecipients(to).first()
    invalidRecipients(cc).isNotEmpty() -> "Alamat Cc tidak valid: " + invalidRecipients(cc).first()
    subject.isBlank() -> "Subjek tidak boleh kosong."
    body.isBlank() -> "Isi email tidak boleh kosong."
    else -> null
}

/** Palet kartu draf bergaya Gmail (patch9): terang atau gelap mengikuti pengaturan sistem. */
private class GmailPalette(
    val card: Color, val ink: Color, val hint: Color, val divider: Color,
    val chip: Color, val sendBg: Color, val sendInk: Color, val border: Color,
)
private val GmailLight = GmailPalette(
    card = Color(0xFFFFFFFF), ink = Color(0xFF1F1F1F), hint = Color(0xFF5F6368), divider = Color(0xFFE3E3E3),
    chip = Color(0xFFEEEEEE), sendBg = Color(0xFFA8CCFF), sendInk = Color(0xFF111111), border = Color(0xFFE6E4DF),
)
private val GmailDark = GmailPalette(
    card = Color(0xFF1F1F1F), ink = Color(0xFFEDEDED), hint = Color(0xFF9AA0A6), divider = Color(0xFF3C3C3C),
    chip = Color(0xFF141414), sendBg = Color(0xFF2244B0), sendInk = Color(0xFFFFFFFF), border = Color(0xFF2E2E2E),
)

/**
 * Kartu draf email di chat dengan tata letak mirip penulis email Gmail: kepala (logo Gmail, pensil, tombol kirim bulat, menu),
 * baris Kepada (chip penerima), Cc bila ada, Subjek, lalu Isi, dan lampiran PDF. Pensil mengubah draf langsung di kartu
 * (Kepada, Cc, Subjek, Isi); [onEdit] menyimpan hasilnya. Tombol kirim memakai alur yang sama seperti sebelumnya (PATCH7):
 * kirim langsung lewat Gmail API bila terhubung, bila tidak membuka aplikasi email dengan draf yang sudah diubah.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmailDraftCard(
    item: EmailDraftItem,
    onOpenPath: (String, Boolean) -> Unit,
    onEdit: (to: List<String>, cc: List<String>, subject: String, body: String) -> Unit = { _, _, _, _ -> },
    onSend: () -> Unit,
) {
    val c = if (isSystemInDarkTheme()) GmailDark else GmailLight
    val shape = RoundedCornerShape(28.dp)
    val clipboard = LocalClipboardManager.current
    var copied by remember(item.id) { mutableStateOf(false) }
    var menu by remember(item.id) { mutableStateOf(false) }
    var editing by remember(item.id) { mutableStateOf(false) }
    var editError by remember(item.id) { mutableStateOf("") }
    // Nilai awal tiap kali mode ubah dibuka, diambil dari draf yang tersimpan.
    var toText by remember(item.id, editing) { mutableStateOf(item.to.joinToString(", ")) }
    var ccText by remember(item.id, editing) { mutableStateOf(item.cc.joinToString(", ")) }
    var subjectText by remember(item.id, editing) { mutableStateOf(item.subject) }
    var bodyText by remember(item.id, editing) { mutableStateOf(item.body) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val busy = item.sending || item.sent
    val canEdit = !busy
    LaunchedEffect(busy) { if (busy) editing = false }

    fun save() {
        val to = parseRecipients(toText)
        val cc = parseRecipients(ccText)
        val err = emailEditError(to, cc, subjectText, bodyText)
        if (err != null) { editError = err; return }
        editError = ""
        if (to != item.to || cc != item.cc || subjectText.trim() != item.subject || bodyText.trimEnd() != item.body) {
            onEdit(to, cc, subjectText, bodyText)
        }
        editing = false
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.border, shape)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        // Kepala: logo Gmail, pensil (ubah/simpan), tombol kirim bulat, menu
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_connector_gmail), contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Text("Gmail", style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 17.sp, color = c.ink), modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(
                        enabled = canEdit,
                        onClickLabel = if (editing) "Simpan perubahan draf" else "Ubah draf email",
                        role = Role.Button
                    ) { if (editing) save() else { editError = ""; editing = true } },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (editing) Icons.Outlined.Done else Icons.Outlined.Edit,
                    contentDescription = if (editing) "Simpan" else "Ubah",
                    tint = if (canEdit) c.ink else c.hint,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            val sendOff = busy || editing
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (sendOff) c.sendBg.copy(alpha = 0.55f) else c.sendBg)
                    .clickable(enabled = !sendOff, onClickLabel = "Kirim email ini sekarang", role = Role.Button) { onSend() },
                contentAlignment = Alignment.Center
            ) {
                when {
                    item.sending -> CircularProgressIndicator(color = c.sendInk, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    item.sent -> Icon(Icons.Outlined.Done, contentDescription = "Terkirim", tint = c.sendInk, modifier = Modifier.size(24.dp))
                    else -> Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Kirim", tint = c.sendInk, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
            Box {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Menu draf email", role = Role.Button) { menu = true },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Outlined.MoreHoriz, contentDescription = null, tint = c.ink, modifier = Modifier.size(24.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (copied) "Tersalin" else "Salin draf", style = TextStyle(fontFamily = MythosSans, fontSize = 15.sp)) },
                        leadingIcon = { Icon(if (copied) Icons.Outlined.Done else Icons.Outlined.ContentCopy, contentDescription = null) },
                        onClick = { clipboard.setText(AnnotatedString(emailDraftText(item))); copied = true; menu = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (editing) {
            val fieldStyle = TextStyle(fontFamily = MythosSans, fontSize = 16.sp, lineHeight = 22.sp, color = c.ink)
            EditRow("Kepada", toText, { toText = it }, c, fieldStyle, KeyboardType.Email)
            EditRow("Cc", ccText, { ccText = it }, c, fieldStyle, KeyboardType.Email)
            GmailDivider(c)
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                if (subjectText.isEmpty()) Text("Subjek", style = fieldStyle.copy(color = c.hint))
                BasicTextField(
                    value = subjectText, onValueChange = { subjectText = it },
                    textStyle = fieldStyle, cursorBrush = SolidColor(c.ink), modifier = Modifier.fillMaxWidth()
                )
            }
            GmailDivider(c)
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(top = 18.dp, bottom = 8.dp)) {
                if (bodyText.isEmpty()) Text("Isi email", style = fieldStyle.copy(color = c.hint))
                BasicTextField(
                    value = bodyText, onValueChange = { bodyText = it },
                    textStyle = fieldStyle.copy(lineHeight = 24.sp), cursorBrush = SolidColor(c.ink), modifier = Modifier.fillMaxWidth()
                )
            }
            if (editError.isNotEmpty()) {
                Text(editError, style = TextStyle(fontFamily = MythosSans, fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFFE5645A)), modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Batal",
                    style = TextStyle(fontFamily = MythosSans, fontSize = 15.sp, color = c.hint),
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { editError = ""; editing = false }.padding(horizontal = 16.dp, vertical = 10.dp)
                )
                Text(
                    "Simpan",
                    style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.sendBg).clickable(role = Role.Button) { save() }.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        } else {
            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    RecipientRow("Kepada", item.to, c)
                    if (item.cc.isNotEmpty()) RecipientRow("Cc", item.cc, c)
                    GmailDivider(c)
                    Text(
                        item.subject,
                        style = TextStyle(fontFamily = MythosSans, fontSize = 16.sp, lineHeight = 22.sp, color = c.ink),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    )
                    GmailDivider(c)
                    Text(
                        item.body,
                        style = TextStyle(fontFamily = MythosSans, fontSize = 16.sp, lineHeight = 24.sp, color = c.ink),
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp)
                    )
                }
            }
        }
        if (item.attachments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lampiran", style = TextStyle(fontFamily = MythosSans, fontSize = 13.sp, color = c.hint))
                item.attachments.forEach { p ->
                    FileCard(p, onOpen = { onOpenPath(p, false) }, onShare = { onOpenPath(p, true) })
                }
            }
        }
        val noteStyle = TextStyle(fontFamily = MythosSans, fontSize = 12.sp, lineHeight = 17.sp, color = c.hint, textAlign = TextAlign.Center)
        if (item.note.isNotBlank()) {
            Text(item.note, style = noteStyle.copy(color = Color(0xFFE5645A)), modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        } else if (item.sent) {
            Text("Email sudah dikirim lewat Gmail Anda.", style = noteStyle, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        } else if (item.openedInApp && !item.sending) {
            Text(
                "Aplikasi email dibuka dengan draf ini. Email baru terkirim setelah Anda menekan Kirim di sana.",
                style = noteStyle, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
        }
    }
}

/** Baris isian penerima saat mode ubah: label di kiri, kolom teks di kanan (pisahkan beberapa alamat dengan koma). */
@Composable
private fun EditRow(label: String, value: String, onChange: (String) -> Unit, c: GmailPalette, style: TextStyle, keyboard: KeyboardType) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style, modifier = Modifier.padding(end = 10.dp))
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(c.chip).padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            BasicTextField(
                value = value, onValueChange = onChange, textStyle = style, cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Baris penerima: label di kiri, alamat sebagai chip membulat (seperti kolom Kepada di Gmail). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientRow(label: String, addresses: List<String>, c: GmailPalette) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TextStyle(fontFamily = MythosSans, fontSize = 16.sp, color = c.ink), modifier = Modifier.padding(end = 10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            addresses.forEach { a ->
                Text(
                    a,
                    style = TextStyle(fontFamily = MythosSans, fontSize = 16.sp, color = c.ink),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 16.dp, vertical = 11.dp)
                )
            }
        }
    }
}

@Composable
private fun GmailDivider(c: GmailPalette) {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
}
