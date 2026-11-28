package com.build.mythos

import android.app.AlertDialog
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Tool ask_user: agen bertanya langsung ke pengguna lewat dialog, lalu lanjut setelah dijawab atau dilewati.

/** Thread agen yang sedang menunggu jawaban dialog. Dilepas saat Activity ditutup atau diputar agar tidak macet. */
internal val pendingWaits = CopyOnWriteArrayList<CountDownLatch>()
internal const val ASK_TIMEOUT_SEC = 300L

internal fun releaseWaits() {
    pendingWaits.forEach { it.countDown() }
}

/** Tampilkan pertanyaan. Jika [options] kosong, pengguna mengetik jawaban bebas. Thread agen menunggu jawaban. */
internal fun MainActivity.askUser(question: String, options: List<String>): String {
    if (isAutonomous()) return "(Mode otonom aktif: pertanyaan ini TIDAK diteruskan ke pengguna. Putuskan sendiri pendekatan yang paling mungkin mencapai tujuan, " +
        "dan jangan mengulang tindakan yang sudah ditolak pengguna.)"
    val latch = CountDownLatch(1)
    val answer = AtomicReference<String?>(null)
    val answered = AtomicBoolean(false)
    val yesNo = isYesNoQuestion(question, options)
    pendingWaits.add(latch)
    runOnUiThread {
        if (isFinishing || isDestroyed) {
            latch.countDown()
            return@runOnUiThread
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        box.addView(TextView(this).apply {
            text = if (yesNo) "$question\n\nPilih IYA atau TIDAK." else question
            textSize = Typo.BODY_L
            setTextColor(Palette.INK)
        })
        lateinit var dlg: AlertDialog
        // Pilihan dibuat sebagai tombol di dalam isi dialog. setItems() tidak tampil bila setMessage() dipakai,
        // sehingga dulu pertanyaan berpilihan hanya menampilkan "Batal tugas" tanpa cara menjawab.
        options.forEach { o ->
            box.addView(Button(this).apply {
                text = o; isAllCaps = false
                setOnClickListener { answered.set(true); answer.set(o); latch.countDown(); try { dlg.dismiss() } catch (e: Exception) { logQuiet("AskUser.kt", e) } }
            })
        }
        val input = if (yesNo) null else EditText(this).apply {
            hint = if (options.isEmpty()) "Ketik jawaban" else "Atau ketik jawaban lain"
            styleField(this)
        }
        if (input != null) box.addView(input, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        val b = AlertDialog.Builder(this)
            .setTitle("AI bertanya")
            .setView(ScrollView(this).apply { addView(box) })
            .setOnCancelListener { latch.countDown() }
            .setNegativeButton(if (yesNo) "TIDAK" else "Batal tugas") { _, _ ->
                if (yesNo) { answered.set(true); answer.set("TIDAK") }
                latch.countDown()
            }
        if (yesNo) b.setPositiveButton("IYA") { _, _ -> answered.set(true); answer.set("IYA"); latch.countDown() }
        else b.setPositiveButton("Kirim") { _, _ -> answered.set(true); answer.set(input?.text?.toString()?.trim().orEmpty()); latch.countDown() }
        dlg = b.create()
        confirmDialog = dlg
        dlg.setOnDismissListener {
            if (confirmDialog === dlg) confirmDialog = null
            latch.countDown()
            hideKeyboard()
        }
        try {
            dlg.show()
        } catch (e: Exception) {
            logQuiet("AskUser.kt", e)
            if (confirmDialog === dlg) confirmDialog = null
            latch.countDown()
        }
    }
    try {
        val completed = latch.await(ASK_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!completed || !answered.get()) {
            runOnUiThread { try { confirmDialog?.dismiss() } catch (e: Exception) { logQuiet("AskUser.kt", e) } }
            cancelled = true
            runOnUiThread { setStatus(IconView.STOP, "Pertanyaan tidak dijawab; tugas dibatalkan") }
            throw Cancelled()
        }
    } finally {
        pendingWaits.remove(latch)
    }
    return answer.get()?.takeIf { it.isNotEmpty() }
        ?: "(Pengguna tidak menjawab. Jangan menganggap ini persetujuan: pilih pendekatan yang aman dan bisa dibatalkan, atau jelaskan kendalanya.)"
}
