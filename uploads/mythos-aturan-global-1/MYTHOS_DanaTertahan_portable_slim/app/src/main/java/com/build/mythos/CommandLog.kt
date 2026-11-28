package com.build.mythos

import android.content.Context
import java.io.File
import org.json.JSONObject

/** Cara sebuah perintah sampai disetujui. */
enum class ApprovalMode(val label: String) {
    MANUAL("Disetujui manual"),
    ALL_BUTTON("Tombol \"Ya, semua\""),
    ALL_SESSION("\"Ya, semua\" sedang aktif"),
    AUTO_READ("Otomatis (hanya baca)"),
    AUTO_MINIMAL("Otomatis (konfirmasi minimal)")
}

/** Satu catatan: perintah (atau penulisan file) yang disetujui, kapan, dan hasilnya. */
class LogEntry(
    val time: Long,
    val kind: String,           // "perintah" atau "tulis file"
    val command: String,
    val description: String,    // penjelasan dari model (hanya keterangan)
    val risk: Risk,             // dihitung aplikasi, bukan klaim model
    val mode: ApprovalMode,
    val ok: Boolean,
    val result: String,
    val exitCode: Int? = null,      // null bila perintah tidak pernah dijalankan
    val decision: String = "",      // "disetujui", "otomatis", "ditolak", "diblokir"
    val sandbox: Boolean = false,
    val network: Boolean = false
)


/** Penyimpanan catatan: satu entri JSON per baris di filesDir/command_log.jsonl, dibatasi MAX_KEEP terakhir. */
object CommandLog {
    const val MAX_KEEP = 500
    private const val TRIM_AT = MAX_KEEP + 100
    private val lock = Any()
    private var lineCount = -1

    private fun file(ctx: Context) = File(ctx.filesDir, "command_log.jsonl")

    private fun toJson(e: LogEntry) = JSONObject()
        .put("t", e.time).put("k", e.kind).put("c", redactSecrets(e.command)).put("d", redactSecrets(e.description))
        .put("r", e.risk.name).put("m", e.mode.name).put("ok", e.ok).put("res", redactSecrets(e.result))
        .put("dec", e.decision).put("sbx", e.sandbox).put("net", e.network)
        .apply { e.exitCode?.let { put("x", it) } }

    private fun fromJson(o: JSONObject) = LogEntry(
        time = o.optLong("t"),
        kind = o.optString("k", "perintah"),
        command = o.optString("c"),
        description = o.optString("d"),
        risk = runCatching { Risk.valueOf(o.optString("r")) }.getOrDefault(Risk.WRITE),
        mode = runCatching { ApprovalMode.valueOf(o.optString("m")) }.getOrDefault(ApprovalMode.MANUAL),
        ok = o.optBoolean("ok"),
        result = o.optString("res"),
        exitCode = if (o.has("x")) o.optInt("x") else null,
        decision = o.optString("dec"),
        sandbox = o.optBoolean("sbx"),
        network = o.optBoolean("net")
    )

    private fun encodeLine(rawJson: String, provider: SecretCryptoProvider = Secret.provider): String {
        val enc = provider.encrypt(rawJson).getOrElse { e ->
            throw java.io.IOException("Enkripsi CommandLog gagal; menolak menulis plaintext", e)
        }
        if (!isEncryptedText(enc)) throw java.io.IOException("Hasil enkripsi CommandLog tidak valid")
        return enc
    }

    private fun decodeLine(line: String, provider: SecretCryptoProvider = Secret.provider): String? =
        when (val r = provider.decrypt(line)) {
            is SecretDecryptResult.Success -> r.value
            else -> null
        }

    fun append(ctx: Context, e: LogEntry) = synchronized(lock) {
        val f = file(ctx)
        val encoded = encodeLine(toJson(e).toString())
        f.appendText(encoded + "\n")
        if (lineCount < 0) {
            val lines = f.readLines()
            lineCount = lines.size
            if (lineCount > TRIM_AT) {
                val kept = lines.takeLast(MAX_KEEP)
                atomicWriteText(f, kept.joinToString("\n") + "\n")
                lineCount = kept.size
            }
        } else {
            lineCount++
            if (lineCount > TRIM_AT) {
                val lines = f.readLines()
                if (lines.size > TRIM_AT) {
                    val kept = lines.takeLast(MAX_KEEP)
                    atomicWriteText(f, kept.joinToString("\n") + "\n")
                    lineCount = kept.size
                } else {
                    lineCount = lines.size
                }
            }
        }
    }

    /** F-20: migrasikan baris plaintext legacy menjadi terenkripsi secara atomik. */
    internal fun migratePlaintextIfNeeded(ctx: Context, provider: SecretCryptoProvider = Secret.provider) = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) return@synchronized
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.none { !isEncryptedText(it) }) return@synchronized
        val migrated = lines.takeLast(MAX_KEEP).mapNotNull { line ->
            if (isEncryptedText(line)) line
            else runCatching { encodeLine(line, provider) }.getOrNull()
        }
        if (migrated.size == lines.takeLast(MAX_KEEP).size) {
            atomicWriteText(f, if (migrated.isEmpty()) "" else migrated.joinToString("\n") + "\n")
            lineCount = migrated.size
        }
    }

    /** Terbaru di depan. Baris yang rusak dilewati. */
    fun load(ctx: Context): List<LogEntry> = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        f.readLines().mapNotNull { line ->
            if (line.isBlank()) null
            else runCatching {
                val plain = decodeLine(line) ?: return@runCatching null
                fromJson(JSONObject(plain))
            }.getOrNull()
        }.asReversed()
    }

    fun clear(ctx: Context) = synchronized(lock) { file(ctx).delete(); lineCount = 0; Unit }
}
