package com.build.mythos.cc

// Jembatan alat aplikasi (konektor email/Telegram/WhatsApp, kasus dana tertahan, berangkas) untuk agen cc.
// Alatnya sendiri tetap hidup di jalur lama (ConnectorsIntegration.kt, DanaTertahanIntegration.kt, BerangkasIntegration.kt):
// semua gerbang keselamatannya ikut terbawa (dialog persetujuan kirim, mode hanya baca, penanda "data tidak terpercaya").
// Kelas ini hanya mengubah satu deklarasi alat lama menjadi Tool cc, tanpa bergantung pada Android.

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [run] dipanggil di thread IO dan mengembalikan (berhasil, teks). Persetujuan pengguna ditangani di dalam alat lama,
 * jadi [risk] SAFE agar dialog tidak muncul dua kali; [effect] membedakan alat baca dan alat yang mengubah sesuatu.
 */
class AppBridgeTool(
    override val name: String,
    private val legacyDescription: String,
    override val schema: JSONObject,
    override val effect: Effect,
    override val timeoutMs: Long,
    private val runner: (JSONObject) -> Pair<Boolean, String>,
) : Tool {
    override val risk = Risk.SAFE

    /**
     * Jurnal pemulihan tugas yang sedang berjalan; diisi di [run] sebelum [runner] dipanggil, supaya alat jembatan yang
     * menulis berkas ke proyek (mis. email_lampiran) bisa mencadangkan/mencatatnya seperti Write dan Unzip. Agen memanggil alat satu per satu.
     */
    @Volatile var journal: TaskJournal? = null

    val nativeDescription: String
        get() = legacyDescription.replace("ask_user", "AskUserQuestion").replace("cari_web", "WebSearch")

    override val doc: String
        get() {
            val props = schema.optJSONObject("properties")
            val req = schema.optJSONArray("required")
            val required = HashSet<String>().also { r -> if (req != null) for (i in 0 until req.length()) r.add(req.getString(i)) }
            val params = props?.keys()?.asSequence()?.joinToString(", ") { k ->
                "\"$k\": \"" + props.getJSONObject(k).optString("description").replace('"', '\'').replace('\n', ' ') + (if (k in required) "" else " (opsional)") + "\""
            }.orEmpty()
            return "$name {$params} — " + legacyDescription.replace("ask_user", "AskUserQuestion").replace("cari_web", "WebSearch")
        }

    // Ringkasan untuk kartu aktivitas dan dialog: tidak pernah memuat isi pesan atau argumen rahasia.
    override fun describe(args: JSONObject) = "$name ${args.str("deskripsi") ?: ""}".trim()

    override suspend fun run(args: JSONObject, env: ToolEnv): ToolResult {
        journal = env.journal
        val (ok, text) = try {
            withContext(Dispatchers.IO) { runner(args) }
        } finally {
            journal = null
        }
        return ToolResult(ok, text)
    }
}
