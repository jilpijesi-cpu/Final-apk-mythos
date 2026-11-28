package com.build.mythos

import java.io.File
import java.util.UUID
import org.json.JSONObject

// Eksekusi alat: persetujuan perintah, catatan, mode baca-saja, runTool.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).


internal fun MainActivity.readOnlyMode() = prefs.getBoolean("readonly", false)

/** Konfirmasi minimal: selalu aktif (saklarnya dihapus dari Pengaturan). Hanya pertanyaan AI, hapus data, dan area sensitif yang meminta jawaban. */
@Suppress("UnusedReceiverParameter")
internal fun MainActivity.minimalConfirm() = true

/**
 * Persetujuan lewat dialog. Tidak ada tindakan yang ditolak otomatis: area sensitif hanya diberi peringatan merah.
 * "Ya, semua" berlaku untuk sisa tugas ini. Tanpa jawaban dalam 10 menit dianggap tidak.
 */
internal fun MainActivity.confirm(
    msg: String, cmd: String? = null, desc: String = "",
    onAll: (() -> Unit)? = null, allLabel: String = "Ya, semua", warning: String? = null,
    force: Boolean = false,  // true: tidak bisa dilewati oleh mode otonom / "Ya, semua" (mis. perintah berjaringan di sandbox)
    routine: Boolean = true, // false: selalu bertanya, tidak ikut konfirmasi minimal (mis. menjalankan CLI)
    emailDraft: EmailDraft? = null // bila ada, dialog menampilkan kartu draf email bergaya tetap
): Boolean {
    // "Ya, semua" tidak membungkam peringatan area sensitif: tindakan bertanda peringatan tetap bertanya.
    // Hanya mode otonom (opt-in eksplisit di Pengaturan) yang melewatinya. [force] tidak bisa dilewati keduanya.
    if (bypassesApproval(allowAll && warning == null, isAutonomous(), force)) { lastApprovalMode = ApprovalMode.ALL_SESSION; return true }
    // Konfirmasi minimal (bawaan): hanya perintah shell yang terbukti READ dan penulisan file (dicadangkan) jalan tanpa dialog;
    // perintah shell WRITE/skrip, hapus data, peringatan, dan [force] selalu bertanya.
    val risk = if (cmd != null) classify(cmd) else Risk.WRITE // tulis file selalu dicadangkan dulu, jadi diperlakukan WRITE
    if (routine && minimalAutoApproves(minimalConfirm(), risk, warning, force, shell = cmd != null)) { lastApprovalMode = ApprovalMode.AUTO_MINIMAL; return true }
    agentState = AgentState.AwaitingApproval(cmd ?: "approval")
    lastDenyUnanswered = false
    val latch = java.util.concurrent.CountDownLatch(1)
    val answer = java.util.concurrent.atomic.AtomicInteger(0) // 0 belum, 1 ya, 2 ya semua, 3 tidak (eksplisit), 4 tidak ada jawaban
    val dialogRef = java.util.concurrent.atomic.AtomicReference<android.app.AlertDialog?>(null)
    val inlineClose = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
    pendingWaits.add(latch)
    runOnUiThread {
        if (isFinishing || isDestroyed) {
            answer.compareAndSet(0, 4)
            latch.countDown()
            return@runOnUiThread
        }
        try {
            if (emailDraft != null) {
                // Draf email tampil langsung di chat dengan tombol Kirim/Tolak, bukan di dialog.
                inlineClose.set(showInlineEmailDraft(
                    emailDraft, warning,
                    onYes = { answer.set(1); latch.countDown() },
                    onNo = { answer.set(3); latch.countDown() }
                ))
                return@runOnUiThread
            }
            val d = showApprovalDialog(
                this,
                ApprovalRequest(cmd ?: msg, desc),
                onYes = { answer.set(1); latch.countDown() },
                onYesAll = { onAll?.invoke(); answer.set(2); latch.countDown() },
                onNo = { answer.set(3); latch.countDown() },
                offerAll = !force && (!minimalConfirm() || onAll != null),
                simple = minimalConfirm(),
                allLabel = allLabel,
                warning = warning,
                card = null
            )
            d.setOnDismissListener {
                if (answer.get() == 0) { answer.set(4); latch.countDown() }
                if (confirmDialog === d) confirmDialog = null
                hideKeyboard()
            }
            dialogRef.set(d)
            confirmDialog = d
        } catch (e: Exception) {
            logQuiet("ToolExecution.kt", e)
            answer.compareAndSet(0, 4)
            latch.countDown()
        }
    }
    try {
        if (!latch.await(10, java.util.concurrent.TimeUnit.MINUTES)) {
            // Tanpa jawaban: anggap "tidak" dan tutup dialognya, supaya tombol "Ya" yang masih tampil tidak menipu.
            answer.compareAndSet(0, 4)
            runOnUiThread { try { dialogRef.get()?.dismiss() } catch (e: Exception) { logQuiet("ToolExecution.kt", e) } }
        }
    } finally {
        // Draf di chat: bila tidak ada jawaban (waktu habis / layar ditutup), matikan tombolnya.
        if (answer.get() == 0 || answer.get() == 4) runOnUiThread { inlineClose.get()?.invoke() }
        pendingWaits.remove(latch)
        activeStep?.setWaiting(false)
    }
    val a = answer.get()
    if (a == 2) { if (onAll == null) allowAll = true; lastApprovalMode = ApprovalMode.ALL_BUTTON }
    else if (a == 1) lastApprovalMode = ApprovalMode.MANUAL
    // 0 = pemanggil dilepas (Activity ditutup/diputar), 4 = dialog ditutup atau 10 menit habis: bukan penolakan eksplisit.
    lastDenyUnanswered = a == 0 || a == 4
    return a == 1 || a == 2
}

/**
 * Hasil alat bila persetujuan tidak didapat. Membedakan penolakan eksplisit dari "tidak ada jawaban",
 * karena yang pertama tidak boleh diulang, sedangkan yang kedua boleh diajukan lagi sekali.
 * Diawali "Ditolak" agar dikenali parseToolResult (USER_DENIED) dan stateOfResult (DENIED).
 */
internal fun MainActivity.deniedResult(): String =
    if (lastDenyUnanswered)
        "Ditolak: tidak ada jawaban persetujuan (dialog ditutup, layar diputar, atau 10 menit tanpa respons). " +
            "Tindakan TIDAK dijalankan. Ini belum tentu penolakan: boleh mengajukannya sekali lagi bila memang perlu, atau pilih pendekatan lain."
    else
        "Ditolak pengguna. Tindakan TIDAK dijalankan. Jangan mengulang tindakan yang sama; pilih pendekatan lain" +
            (if (isAutonomous()) " atau jelaskan kendalanya di jawaban akhir." else ", tanyakan alasannya lewat ask_user, atau jelaskan kendalanya.")

/** Catat perintah/penulisan yang sudah disetujui. Tidak boleh pernah mengganggu alur agen. */
internal fun MainActivity.logApproved(
    kind: String, command: String, desc: String, risk: Risk,
    mode: ApprovalMode, ok: Boolean, result: String,
    exitCode: Int? = null, decision: String = "disetujui", sandbox: Boolean = false, network: Boolean = false
) {
    try {
        CommandLog.append(this, LogEntry(System.currentTimeMillis(), kind, command, desc, risk, mode, ok, result, exitCode, decision, sandbox, network))
    } catch (e: Exception) {
    }
}

