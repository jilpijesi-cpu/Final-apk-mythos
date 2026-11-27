package com.build.mythos

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// Memilih model: label, daftar, uji (probe), ketik manual, templat.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.updateModelLabel() {
    val switched = fallbackProv != null || modelOverride != null
    val n = activeModelText(
        preset(prov).fmt == "cli", prov, pvOf(prov, "model").ifEmpty { preset(prov).model },
        fallbackProv, fallbackModel, modelOverride
    )
    val cut = if (switched) -1 else n.indexOf(' ')
    val sp = android.text.SpannableString(n)
    if (cut > 0) {
        sp.setSpan(android.text.style.ForegroundColorSpan(Palette.INK_SOFT), cut, n.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    runOnUiThread { modelLabel.text = sp; refreshProviderBadge() }
}

/** Ada permintaan daftar model yang masih berjalan? Mencegah ketukan berulang menumpuk permintaan dan toast. */
private val modelListLoading = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * Daftar model tidak bisa dimuat (penyedia macet, mati, atau kosong). Dulu hanya kotak ketik manual yang terbuka sehingga
 * pengguna tidak punya jalan untuk berganti AI. Kini tersedia tiga jalan: coba lagi, ganti penyedia, atau ketik manual.
 */
private fun MainActivity.showModelListFailed(reason: String) {
    AlertDialog.Builder(this)
        .setTitle("Daftar model tidak bisa dimuat")
        .setMessage(
            ProviderCatalog.label(prov) + " belum merespons" + (if (reason.isNotBlank()) " ($reason)" else "") +
                ".\nAnda bisa mencoba lagi, berganti ke penyedia AI lain, atau mengetik nama model sendiri."
        )
        .setPositiveButton("Ganti penyedia AI") { _, _ -> showApiKeyDialog() }
        .setNeutralButton("Ketik manual") { _, _ -> typeModel() }
        .setNegativeButton("Coba lagi") { _, _ -> pickModel() }
        .show()
}

internal fun MainActivity.pickModel() {
    if (preset(prov).fmt == "cli") { showApiKeyDialog(); return }
    // Model terkunci (OpenRouter): tidak ada yang bisa dipilih di sini, jadi langsung tawarkan ganti penyedia.
    if (lockedModelFor(prov) != null) { showApiKeyDialog(); return }
    if (needsKey() && cfgKey().isEmpty()) { showApiKeyDialog(); return }
    if (modelSetupBusy.get()) { toast("Sedang menyiapkan model, tunggu sebentar..."); return }
    if (!modelListLoading.compareAndSet(false, true)) { toast("Masih memuat daftar model..."); return }
    toast("Memuat daftar model...")
    bg {
        var failure = ""
        val list = try {
            fetchModelsCached()
        } catch (e: Exception) {
            failure = (e.message ?: "").take(80)
            emptyList<String>()
        } finally {
            modelListLoading.set(false)
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (list.isEmpty()) {
                showModelListFailed(failure)
            } else {
                val c = loadProbed()
                if (c != null) showProbed(c.first, c.second, list) else autoSetupModels(force = true)
            }
        }
    }
}

/** Satu baris model: nama, penanda terpilih (centang), dan keterangan kecil bila model sedang sibuk. */
private fun MainActivity.modelRow(name: String, selected: Boolean, busyNote: Boolean, onPick: () -> Unit): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(if (selected) rounded(Palette.BUBBLE, 14) else null)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        addView(TextView(context).apply {
            text = name
            textSize = Typo.BODY_L
            setTextColor(Palette.INK)
            if (selected) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (busyNote) addView(TextView(context).apply {
            text = "sibuk"
            textSize = Typo.CAPTION
            setTextColor(Palette.INK_SOFT)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        if (selected) addView(
            IconView(context, IconView.CHECK, Palette.BRAND),
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(8) }
        )
        setOnClickListener { onPick() }
        a11ySelected(selected)
    }

/** Lembar pilihan model: daftar bersih + tautan "Semua model" / "Ketik manual" di bawah. */
private fun MainActivity.showModelSheet(
    names: List<String>, busy: Set<String>, footerLabel: String, onRefresh: (() -> Unit)? = null, onFooter: () -> Unit
) {
    var dlg: Dialog? = null
    val cur = cfgModel()
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        for (n in names) {
            addView(modelRow(n.removePrefix("models/"), n == cur, n in busy) {
                prefs.edit().putString("p_${prov}_model", n).apply()
                resetFailover()
                dlg?.dismiss()
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }
    val footer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(softButton(footerLabel) { dlg?.dismiss(); onFooter() }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(softButton("Ketik manual") { dlg?.dismiss(); typeModel() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        // Model yang tidak bisa dipakai disembunyikan; tombol ini mengujinya ulang dan memunculkan yang sudah pulih.
        if (onRefresh != null) addView(softButton("Segarkan model") { dlg?.dismiss(); onRefresh() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        // Ganti penyedia atau kunci kapan saja: membuka dialog "Hubungkan AI Anda".
        addView(softButton("Ganti penyedia AI") { dlg?.dismiss(); showApiKeyDialog() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
    }
    dlg = showSheet("Pilih model", ProviderCatalog.label(prov), list, footer)
}

internal fun MainActivity.showModelList(list: List<String>) {
    showModelSheet(list, emptySet(), "Kembali") { pickModel() }
}

internal fun MainActivity.showProbed(good: List<String>, busy: List<String>, all: List<String>) {
    showModelSheet(good + busy, busy.toSet(), "Semua model", { retryUnavailableModels(manual = true) }) { showModelList(all) }
}

internal fun MainActivity.typeModel() {
    if (lockedModelFor(prov) != null) { toast(lockedModelMessage(prov)); return }
    val et = EditText(this).apply {
        hint = "Nama model"
        inputType = InputType.TYPE_CLASS_TEXT
        setText(cfgModel())
        setSelection(text.length)
        styleField(this)
    }
    val box = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    AlertDialog.Builder(this)
        .setTitle("Model ($prov)")
        .setView(box)
        .setPositiveButton("Simpan") { _, _ ->
            prefs.edit().putString("p_${prov}_model", et.text.toString().trim()).apply()
            resetFailover()
        }
        .setNegativeButton("Batal", null)
        // Jalan keluar yang selalu ada: berganti penyedia AI tanpa harus lewat Pengaturan.
        .setNeutralButton("Ganti penyedia AI") { _, _ -> showApiKeyDialog() }
        .show()
}

@Suppress("UNUSED_PARAMETER")
internal fun MainActivity.showTemplates(anchor: View) {
    // Menu (+): lampiran dan Penalaran mendalam. Semua tindakan proyek dijalankan lewat instruksi chat.
    val ref = arrayOfNulls<Dialog>(1)
    fun row(title: String, hint: String, trailing: View?, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(null)
        setPadding(dp(4), dp(12), dp(4), dp(12))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = Typo.BODY_L
                setTextColor(Palette.INK)
            })
            if (hint.isNotEmpty()) addView(TextView(context).apply {
                text = hint
                textSize = Typo.SMALL
                setTextColor(Palette.INK_SOFT)
            })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (trailing != null) addView(trailing, LinearLayout.LayoutParams(WRAP, WRAP))
        setOnClickListener { onClick() }
    }
    val deepSwitch = android.widget.Switch(this).apply {
        isChecked = deepOn()
        isClickable = false
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(row("Upload File", "Lampirkan file untuk dikerjakan agen", null) { ref[0]?.dismiss(); pickFile() })
        addView(row("Upload Gambar", "Lampirkan gambar ke pesan", null) { ref[0]?.dismiss(); pickImage() })
        addView(row("Penalaran mendalam", "Penalaran mendalam untuk masalah kompleks", deepSwitch) {
            val on = !deepOn()
            setDeep(on)
            deepSwitch.isChecked = on
            toast(if (on) "Penalaran mendalam aktif" else "Penalaran mendalam dimatikan")
        })
    }
    ref[0] = showSheet("Tambahkan", null, content)
}

private val modelSetupBusy = java.util.concurrent.atomic.AtomicBoolean(false)

/** Pilihan model terbaik dari daftar yang lolos uji: sonnet > flash > gpt-4o > mini > haiku; versi lite dihindari, versi tertinggi dipilih. Di penyedia open source, model open source terbaik yang dipilih. */
internal fun bestModel(good: List<String>, provider: String = ""): String? {
    // Penyedia open source (OpenRouter, Groq, ...): model open source didahulukan (OpenSource.kt).
    if (provider in OPEN_SOURCE_HOSTS) bestOpenSource(good)?.let { return it }
    // Penyedia berkunci API (Anthropic, OpenAI, Gemini, DeepSeek): aturan rekomendasi per penyedia (ProviderCatalog.kt).
    ProviderCatalog.recommend(provider, good)?.let { return it }
    val pref = listOf("sonnet", "flash", "gpt-4o", "mini", "haiku")
    for (w in pref) {
        val hit = good.filter { it.contains(w, true) }
        if (hit.isEmpty()) continue
        val full = hit.filter { !it.contains("lite", true) }
        return (if (full.isNotEmpty()) full else hit).maxOrNull()
    }
    return good.firstOrNull()
}

/**
 * Setelah Simpan (atau saat daftar belum pernah diuji): ambil daftar model, uji otomatis di latar,
 * simpan hasilnya, lalu pilih model terbaik yang bisa dipakai. Pengguna cukup mengisi API key.
 * Kalau hasil uji untuk key ini sudah ada dan model sudah terisi, tidak diulang.
 */
internal fun MainActivity.autoSetupModels(force: Boolean = false, silent: Boolean = false) {
    if (preset(prov).fmt == "cli") return
    // Model terkunci: tidak ada daftar yang diambil atau diuji; cukup pastikan label benar.
    lockedModelFor(prov)?.let { locked ->
        prefs.edit().putString("p_${prov}_model", locked).apply()
        updateModelLabel()
        return
    }
    if (needsKey() && cfgKey().isEmpty()) return
    if (!force && cfgModel().isNotBlank() && loadProbed() != null) return
    if (silent) {
        // Penyegaran diam-diam di latar: jangan ganggu tugas berjalan, dan jangan ulang tiap saat bila gagal.
        if (busy) return
        val tryKey = "p_${prov}_probe_try"
        val now = System.currentTimeMillis()
        if (!retryDue(prefs.getLong(tryKey, 0L), now)) return
        if (!modelSetupBusy.compareAndSet(false, true)) return
        prefs.edit().putLong(tryKey, now).apply()
    } else {
        if (!modelSetupBusy.compareAndSet(false, true)) return
    }
    val p = prov
    val gen = setupGen.incrementAndGet() // setup baru membatalkan uji lanjutan di latar milik setup lama
    if (!silent) runOnUiThread {
        modelLabel.text = "Menyiapkan model…"
        toast("Menyiapkan model otomatis...")
    }
    bg {
        var msg = ""
        var tail: List<String> = emptyList()
        var savedAt = 0L
        try {
            val cand = probeCandidates(p, fetchModelsCached().filter { n -> skipWords.none { w -> n.contains(w, true) } }, 30)
            if (cand.isEmpty()) {
                msg = if (p == "Ollama lokal") "Belum ada model di Ollama. Unduh lewat Siapkan Termux."
                else "Tidak ada model yang cocok. Cek API key atau langganan."
                if (p == "Ollama lokal") runOnUiThread { if (!isFinishing && !isDestroyed) showSetup() }
            } else {
                var out = ProbeOutcome(emptyList(), emptyList(), emptyMap())
                if (p == "Ollama lokal") {
                    // Model lokal: jangan diuji paralel (memuat beberapa model sekaligus menghabiskan RAM HP); semua yang terpasang dianggap bisa.
                    out = ProbeOutcome(cand, emptyList(), emptyMap())
                } else {
                    // Putaran pertama kecil dan cepat: cukup untuk memastikan kunci valid dan memilih model awal.
                    // Sisanya diuji diam-diam di latar (probeTail) tanpa menahan pengguna.
                    val chunks = cand.chunked(PROBE_FIRST_ROUND)
                    var i = 0
                    while (i < chunks.size && out.good.isEmpty() && out.busy.isEmpty()) {
                        val codes = probeCodes(chunks[i])
                        i++
                        out = mergeProbeResults(out, codes, System.currentTimeMillis())
                        // Semua hasil tak tentu (jaringan putus / kunci salah): jangan lanjut menguji puluhan model sia-sia.
                        if (codes.all { classifyProbe(it.second) == ProbeVerdict.UNKNOWN }) break
                    }
                    tail = chunks.drop(i).flatten()
                }
                savedAt = System.currentTimeMillis()
                msg = applyProbeOutcome(p, out, savedAt)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            msg = "Gagal menyiapkan model: " + (e.message ?: "").take(80)
            if (p == "Ollama lokal") runOnUiThread { if (!isFinishing && !isDestroyed) showSetup() } // server belum jalan: tuntun lewat Siapkan Termux
        } finally {
            // Pengguna sudah bisa memakai model awal: lepas penguncian setup SEBELUM uji lanjutan di latar.
            modelSetupBusy.set(false)
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            updateModelLabel()
            if (msg.isNotEmpty() && !silent) toast(msg)
        }
        if (tail.isNotEmpty()) probeTail(p, gen, tail, savedAt)
    }
}

private val setupGen = java.util.concurrent.atomic.AtomicInteger(0)

@Volatile private var tailRunning = false

/** Simpan hasil putaran pertama dan pilih model; mengembalikan pesan untuk pengguna ("" bila penyedia sudah berganti). */
private fun MainActivity.applyProbeOutcome(p: String, out: ProbeOutcome, savedAt: Long): String {
    if (out.good.isEmpty() && out.busy.isEmpty()) return "Tidak ada model yang lolos uji. Cek API key atau langganan."
    if (p != prov) return ""
    saveProbed(out.good, out.busy, savedAt, out.bad)
    val cur = cfgModel()
    val pick = if (cur in out.good) cur else bestModel(out.good, p) ?: bestModel(out.busy, p)
    if (pick != null && pick != cur) prefs.edit().putString("p_${p}_model", pick).apply()
    return if (out.good.isNotEmpty()) "Siap ✓ ${out.good.size} model bisa dipakai. Model: ${pick ?: cur}"
    else "Semua model sedang sibuk. Dipilih sementara: ${pick ?: cur}"
}

/**
 * Uji model sisanya per gelombang kecil di latar dan gabungkan ke daftar tersimpan (yang lolos muncul di pemilih,
 * yang gagal disembunyikan). Tidak mengubah model pilihan pengguna. Berhenti bila penyedia/setup berganti.
 */
private fun MainActivity.probeTail(p: String, gen: Int, tail: List<String>, savedAt: Long) {
    tailRunning = true
    try {
        for (chunk in tail.chunked(PROBE_TAIL_CHUNK)) {
            if (p != prov || setupGen.get() != gen) return
            val codes = probeCodes(chunk, PROBE_TAIL_CHUNK)
            if (p != prov || setupGen.get() != gen) return
            val raw = loadProbedRaw() ?: return
            val out = mergeProbeResults(ProbeOutcome(raw.good, raw.busy, raw.bad), codes, System.currentTimeMillis())
            saveProbed(out.good, out.busy, savedAt, out.bad)
            if (codes.all { classifyProbe(it.second) == ProbeVerdict.UNKNOWN }) return
        }
    } catch (e: Exception) {
        logQuiet("ModelPicker.kt", e)
    } finally {
        tailRunning = false
    }
}

/**
 * Uji ulang model yang disembunyikan (dan yang sibuk): yang sudah pulih langsung muncul lagi di pilihan.
 * Otomatis (diam-diam, paling cepat sejam sekali) atau manual lewat tombol "Segarkan" di pemilih.
 * Hanya model yang memang layak dicoba lagi ([shouldRetryUnavailable]); paling banyak [PROBE_RETRY_MAX] per putaran.
 */
internal fun MainActivity.retryUnavailableModels(manual: Boolean = false) {
    if (preset(prov).fmt == "cli" || lockedModelFor(prov) != null || prov == "Ollama lokal") return
    if (needsKey() && cfgKey().isEmpty()) return
    if (tailRunning || modelSetupBusy.get()) {
        if (manual) toast("Sedang menyiapkan model, coba lagi sebentar...")
        return
    }
    if (!manual && busy) return
    val p = prov
    val raw = loadProbedRaw() ?: return
    val now = System.currentTimeMillis()
    if (!manual) {
        val tryKey = "p_${p}_probe_retry"
        if (!retryDue(prefs.getLong(tryKey, 0L), now)) return
    }
    val due = raw.bad.filter { (_, e) -> manual || shouldRetryUnavailable(e.code, e.at, now) }.keys
    val targets = (due + raw.busy).distinct().take(PROBE_RETRY_MAX)
    if (targets.isEmpty()) {
        if (manual) toast("Semua model sudah diperiksa.")
        return
    }
    if (!modelSetupBusy.compareAndSet(false, true)) {
        if (manual) toast("Sedang menyiapkan model, coba lagi sebentar...")
        return
    }
    if (!manual) prefs.edit().putLong("p_${p}_probe_retry", now).apply()
    if (manual) toast("Memeriksa ${targets.size} model...")
    bg {
        var back = 0
        try {
            val codes = probeCodes(targets, PROBE_TAIL_CHUNK)
            if (p == prov) {
                val cur = loadProbedRaw()
                if (cur != null) {
                    val out = mergeProbeResults(ProbeOutcome(cur.good, cur.busy, cur.bad), codes, System.currentTimeMillis())
                    back = out.good.count { m -> cur.good.none { sameModel(it, m) } }
                    saveProbed(out.good, out.busy, cur.savedAt, out.bad)
                }
            }
        } catch (e: Exception) {
            logQuiet("ModelPicker.kt", e)
        } finally {
            modelSetupBusy.set(false)
        }
        if (manual) runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            toast(if (back > 0) "$back model tersedia lagi" else "Belum ada model yang pulih")
            pickModel()
        }
    }
}
