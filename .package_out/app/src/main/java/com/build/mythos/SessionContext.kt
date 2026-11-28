package com.build.mythos

/**
 * Jangkar konteks sesi lintas penyedia/kunci AI (OpenRouter, Gemini, OpenAI, Anthropic, Groq, DeepSeek, Ollama, CLI).
 * Menjaga agar setiap agen selalu mengetahui tujuan awal sesi, alur percakapan, dan tugas yang terhenti
 * di tengah jalan (mis. saat kuota/token habis lalu pengguna mengganti API key di sesi chat yang sama).
 * Murni Kotlin (tanpa android.*) supaya 100% dapat diuji di JVM.
 */
internal object SessionContext {
    const val DEFAULT_RECENT_PAIRS = 8
    const val CLI_RECENT_PAIRS = 6
    private const val INTERRUPTED_MARKER = "(terhenti di tengah jalan sebelum selesai)"

    private fun isInterruptedReply(reply: String): Boolean {
        val r = reply.trim()
        return r == INTERRUPTED_MARKER ||
            r.startsWith(FAILED_HIST_PREFIX) ||
            r.startsWith("(respons model kosong)")
    }

    /**
     * Ekstrak pasangan (pesan pengguna, jawaban akhir AI) secara berurutan dari awal sesi.
     * Pesan pengguna yang belum sempat terjawab (karena kuota habis / terhenti) tetap dipertahankan.
     */
    fun extractPairs(history: List<Hist>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var pending: String? = null
        for (h in history) {
            if (h.role == 0) {
                if (!h.text.startsWith(SYS_PREFIX) && h.text.isNotBlank()) {
                    val prev = pending
                    if (prev != null) {
                        out.add(prev to INTERRUPTED_MARKER)
                    }
                    pending = h.text.trim()
                }
            } else if (h.role == 1 && h.calls.isEmpty() && h.text.isNotBlank()) {
                val p = pending
                if (p != null) {
                    out.add(p to h.text.trim())
                    pending = null
                }
            }
        }
        val dangling = pending
        if (dangling != null) {
            out.add(dangling to INTERRUPTED_MARKER)
        }
        return out
    }

    /**
     * Bangun tugas untuk agen API (`CcBridge`) dengan jangkar tujuan awal sesi + ringkasan tengah (bila panjang)
     * + giliran terbaru + penanda tugas terhenti bila giliran sebelumnya gagal/kehabisan token.
     */
    fun buildTaskWithHistory(
        pairs: List<Pair<String, String>>,
        newMessage: String,
        maxRecent: Int = DEFAULT_RECENT_PAIRS
    ): String {
        if (pairs.isEmpty()) return newMessage
        val sb = StringBuilder()
        sb.append("Konteks sesi chat ini (berlaku lintas penyedia/kunci AI; lanjutkan secara konsisten dari awal):\n")

        if (pairs.size > maxRecent) {
            val first = pairs.first()
            sb.append("[Awal sesi — Giliran #1]\n")
            sb.append("Pengguna: ").append(clip(first.first, 700)).append("\n")
            sb.append("Mythos: ").append(clip(first.second, 900)).append("\n")

            val middleEnd = pairs.size - maxRecent
            if (middleEnd > 1) {
                sb.append("\n[Ringkasan giliran tengah #2..#").append(middleEnd).append("]\n")
                for (i in 1 until middleEnd) {
                    val (u, a) = pairs[i]
                    sb.append("- #").append(i + 1).append(" Pengguna: ").append(oneLine(u, 140))
                        .append(" -> Mythos: ").append(oneLine(a, 180)).append("\n")
                }
            }
            sb.append("\n[Percakapan terbaru]\n")
            val recentStart = pairs.size - maxRecent
            for (i in recentStart until pairs.size) {
                val (u, a) = pairs[i]
                sb.append("Pengguna: ").append(clip(u, 700))
                    .append("\nMythos: ").append(clip(a, 1500)).append("\n")
            }
        } else {
            for ((u, a) in pairs) {
                sb.append("Pengguna: ").append(clip(u, 700))
                    .append("\nMythos: ").append(clip(a, 1500)).append("\n")
            }
        }

        val lastPair = pairs.lastOrNull()
        if (lastPair != null && isInterruptedReply(lastPair.second)) {
            sb.append("\n[PERHATIAN — TUGAS SEBELUMNYA TERHENTI DI TENGAH JALAN]\n")
            sb.append("Permintaan yang belum tuntas: ").append(clip(lastPair.first, 600)).append("\n")
            sb.append("Pengguna kemungkinan baru saja mengganti kunci/penyedia AI karena token/kuota habis. ")
            sb.append("Periksa kondisi file/proyek yang sudah sempat dikerjakan, lalu lanjutkan pekerjaan yang tertunda sesuai pesan baru di bawah tanpa mengulang dari nol.\n")
        }

        sb.append("\nPesan baru dari pengguna:\n").append(newMessage)
        return sb.toString()
    }

    /**
     * Jangkar singkat untuk disisipkan ke `sysText()` agar agen API/lokal tetap tahu tujuan awal sesi
     * dan tugas yang terhenti walaupun pengguna baru mengganti kunci/penyedia AI.
     */
    fun buildSystemSessionAnchor(history: List<Hist>): String {
        val userTurns = history.filter { it.role == 0 && !it.text.startsWith(SYS_PREFIX) && it.text.isNotBlank() }
        if (userTurns.size < 2) return ""
        val firstGoal = clip(userTurns.first().text.trim(), 500)
        val pairs = extractPairs(history)
        val sb = StringBuilder()
        sb.append("\nKONTEKS SESI LINTAS-AI: Percakapan ini adalah lanjutan sesi chat yang sama (pengguna dapat berganti kunci/penyedia/model AI kapan saja). ")
        sb.append("Selalu pegang konteks dari awal sesi tanpa kebingungan.\n")
        sb.append("Tujuan/pesan awal sesi: ").append(oneLine(firstGoal, 500))
        if (pairs.size > 2) {
            val recentTopics = pairs.takeLast(3).joinToString(" | ") { oneLine(it.first, 90) }
            sb.append("\nTopik giliran terakhir: ").append(recentTopics)
        }
        val prevInterrupted = pairs.dropLast(1).lastOrNull { isInterruptedReply(it.second) }
            ?: pairs.lastOrNull()?.takeIf { isInterruptedReply(it.second) }
        if (prevInterrupted != null) {
            sb.append("\nCatatan: Tugas sebelumnya sempat terhenti (mis. token/kuota habis) pada permintaan: ")
                .append(oneLine(prevInterrupted.first, 240))
                .append(". Periksa progres yang sudah ada lalu lanjutkan tanpa bingung.")
        }
        return sb.toString()
    }

    /**
     * Bungkus pesan untuk penyedia CLI (Codex / Gemini CLI / Claude Code) agar membawa konteks
     * dari sesi chat sebelumnya saat pengguna berpindah dari AI API ke CLI di chat yang sama.
     */
    fun buildCliMessageWithHistory(
        history: List<Hist>,
        userMsg: String,
        maxRecent: Int = CLI_RECENT_PAIRS
    ): String {
        val pairs = extractPairs(history)
        if (pairs.isEmpty()) return userMsg
        return buildTaskWithHistory(pairs, userMsg, maxRecent = maxRecent)
    }

    private fun clip(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max).trimEnd() + "…"

    private fun oneLine(s: String, max: Int): String =
        clip(s.replace(Regex("\\s+"), " ").trim(), max)
}
