package com.mythos.app.agent

import android.content.Context

/**
 * Adapter provider Codex. Mythos tetap mengelola loop, parsing tool, izin, dan eksekusi tool sendiri;
 * Codex app-server hanya menghasilkan completion dan dilarang menjalankan tool native pada jalur ini.
 */
class CodexAppServerClient(private val context: Context) : LlmClient {
    /** Selalu satu sesi per pesan; jalur always-on sudah dihapus (v83). */
    private suspend fun request(prompt: String, onText: (String) -> Unit): String =
        TermuxLauncher.runCodexAppServer(context, prompt, onText)

    override suspend fun complete(system: String, messages: List<Msg>): String =
        request(codexPrompt(system, messages)) {}

    override suspend fun stream(system: String, messages: List<Msg>, onText: (String) -> Unit): String =
        request(codexPrompt(system, messages), onText)
}

private val STRUCTURAL_TAG = Regex("<(/?)(MYTHOS_SYSTEM|CONVERSATION|USER|ASSISTANT|SYSTEM)(?=[\\s>/])", RegexOption.IGNORE_CASE)

/**
 * Isi pesan (halaman web, email, hasil tool) tidak boleh meniru pembatas prompt. Tag struktural dinetralkan dengan
 * zero-width space setelah "<" sehingga tidak ada yang bisa menutup blok lalu menyisipkan blok sistem palsu.
 */
internal fun escapeStructuralTags(text: String): String =
    STRUCTURAL_TAG.replace(text) { "<" + it.groupValues[1] + "\u200B" + it.groupValues[2] }

internal fun codexPrompt(system: String, messages: List<Msg>): String = buildString {
    appendLine("Kamu adalah endpoint model bahasa untuk aplikasi Mythos, bukan agen CLI mandiri.")
    appendLine("Jangan memakai tool bawaan Codex: jangan membaca/menulis file, menjalankan perintah, membuka jaringan, atau meminta persetujuan tool.")
    appendLine("PENTING: alat Mythos (Read, Glob, Grep, Write, Edit, dan lainnya sesuai daftar di instruksi sistem) TERSEDIA untukmu, tetapi hanya lewat blok <tool_use>{...}</tool_use> di dalam jawaban teksmu; Mythos yang menjalankannya. Lampiran pengguna ada di folder uploads/. Jangan pernah menjawab bahwa alat atau lampiran tidak tersedia: panggil alat Mythos yang sesuai.")
    appendLine("PEMETAAN ALAT: mencari di web = tulis <tool_use>{\"name\": \"WebSearch\", \"input\": {\"query\": \"...\"}}</tool_use>; membuka halaman = WebFetch; membaca file = Read/Glob/Grep; menulis file = Write/Edit. Kamu tidak punya pencarian web bawaan; jangan pernah mencoba webSearch/mcpToolCall/commandExecution/fileChange milik Codex, karena percobaan itu langsung dibatalkan. Bila suatu alat tidak ada di daftar instruksi sistem, jawab langsung tanpa alat.")
    appendLine("Balas hanya sesuai instruksi sistem Mythos berikut. Jika instruksi meminta format <tool_use> atau protokol lain, keluarkan format itu sebagai teks persis untuk diproses Mythos.")
    appendLine()
    appendLine("<MYTHOS_SYSTEM>")
    appendLine(escapeStructuralTags(system))
    appendLine("</MYTHOS_SYSTEM>")
    appendLine("<CONVERSATION>")
    messages.forEach { message ->
        appendLine("<${message.role.name}>")
        appendLine(escapeStructuralTags(message.text))
        appendLine("</${message.role.name}>")
    }
    appendLine("</CONVERSATION>")
}
