package com.build.mythos

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import java.io.ByteArrayOutputStream

/**
 * Satu pemanggilan asisten: menampilkan [AssistPanel] di atas aplikasi lain dan mengisinya dengan teks layar
 * (AssistStructure, resmi dari Android) serta tangkapan layar bila diizinkan. Tidak ada izin khusus: pengguna
 * sendiri yang memilih MYTHOS sebagai asisten digital dan memanggilnya.
 */
internal class AssistSession(private val ctx: Context) : VoiceInteractionSession(ctx), AssistHost {

    private var container: FrameLayout? = null
    @Volatile private var panel: AssistPanel? = null

    override fun onCreateContentView(): View {
        val c = FrameLayout(ctx)
        container = c
        return c
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        window?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val lockedCtx = ctx.withLockedDisplay()
        applyTheme(lockedCtx)
        panel?.destroy()
        val p = AssistPanel(lockedCtx, this)
        panel = p
        container?.apply {
            removeAllViews()
            addView(p.root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        p.animateIn()
    }

    override fun onHide() {
        panel?.destroy()
        panel = null
        container?.removeAllViews()
        super.onHide()
    }

    override fun onDestroy() {
        panel?.destroy()
        panel = null
        container?.removeAllViews()
        container = null
        super.onDestroy()
    }

    override fun onBackPressed() { hide() }

    // ------------------------------------------------------------------ membaca layar

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        if (structure == null) { panel?.setCapture(ScreenCapture("aplikasi ini", "", emptyList())); return }
        val pkg = structure.activityComponent?.packageName.orEmpty()
        val raw = ArrayList<String>()
        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i)?.rootViewNode ?: continue
            collect(root, raw, 0)
            if (raw.size >= AssistLogic.MAX_NODES * 2) break
        }
        // Pembersihan ribuan teks dan pencarian nama aplikasi tidak perlu di thread utama: panel sedang beranimasi masuk.
        val target = panel ?: return
        Thread {
            val texts = AssistLogic.cleanTexts(raw)
            val label = appLabel(pkg)
            target.root.post { if (panel === target) target.setCapture(ScreenCapture(label, pkg, texts)) }
        }.start()
    }

    private fun isSensitiveNode(n: AssistStructure.ViewNode): Boolean {
        if (isPasswordInputType(n.inputType)) return true
        val hints = n.autofillHints
        if (hints != null && hints.any { h ->
                val low = h.lowercase()
                low.contains("password") || low.contains("creditcard") || low.contains("cvv") || low.contains("pin")
            }) return true
        val attrs = n.htmlInfo?.attributes
        if (attrs != null && attrs.any { pair ->
                pair.first.equals("type", ignoreCase = true) && pair.second.equals("password", ignoreCase = true)
            }) return true
        return false
    }

    private fun collect(n: AssistStructure.ViewNode, out: MutableList<String>, depth: Int) {
        if (depth > 60 || out.size >= AssistLogic.MAX_NODES * 2) return
        if (n.visibility != View.VISIBLE) return
        if (n.isAssistBlocked) return                       // aplikasi menandai bagian ini tidak boleh dibaca
        if (!isSensitiveNode(n)) {                          // jangan baca kolom sandi / PIN / kartu kredit
            val t = n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString()
            if (!t.isNullOrBlank()) out.add(t)
        }
        for (i in 0 until n.childCount) {
            val child = n.getChildAt(i) ?: continue
            collect(child, out, depth + 1)
        }
    }

    /** Tangkapan layar (hanya bila pengguna mengaktifkannya di Pengaturan > Asisten); dikecilkan lalu di-JPEG. */
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot == null) return
        val target = panel ?: return
        Thread {
            val b64 = try { encodeJpeg(screenshot) } catch (_: Exception) { null }
            if (b64 != null) target.root.post { if (panel === target) target.setScreenshot(b64) }
        }.start()
    }

    private fun encodeJpeg(src: Bitmap, maxSide: Int = 768): String {
        if (src.width <= 0 || src.height <= 0) throw IllegalArgumentException("Empty bitmap")
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) {
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, w, h, true)
        } else src
        try {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (bmp !== src) bmp.recycle() // salinan kecil kita sendiri; bitmap asli milik sistem, jangan disentuh
        }
    }

    private fun appLabel(pkg: String): String {
        if (pkg.isEmpty()) return "aplikasi ini"
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    // ------------------------------------------------------------------ AssistHost

    override fun close() { hide() }

    override fun openMythos() {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startAssistantActivity(i) } catch (_: Exception) { try { ctx.startActivity(i) } catch (_: Exception) {} }
        hide()
    }

    override fun insert(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MYTHOS", text))
        toast("Disalin. Tempel di kolom teks yang Anda buka.")
        hide()
    }

    override fun toast(msg: String) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
}
