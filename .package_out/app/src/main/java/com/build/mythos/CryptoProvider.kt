package com.build.mythos

/** Hasil pembukaan key tersimpan tanpa membocorkan isi key ke log atau error UI. */
internal sealed interface SecretDecryptResult {
    data class Success(val value: String) : SecretDecryptResult
    data object Missing : SecretDecryptResult
    data object Corrupt : SecretDecryptResult
    data object KeystoreUnavailable : SecretDecryptResult
}

/** Provider kripto dapat diganti fake pada unit test JVM. */
internal interface SecretCryptoProvider {
    fun encrypt(plain: String): Result<String>
    fun decrypt(stored: String): SecretDecryptResult
}

/**
 * Simpan hanya hasil enkripsi yang sukses. Fungsi ini sengaja murni agar test dapat memastikan
 * plaintext tidak pernah masuk ke penyimpanan ketika provider gagal.
 */
internal fun putEncryptedValue(
    store: MutableMap<String, String>,
    name: String,
    plain: String,
    provider: SecretCryptoProvider
): Result<Unit> = provider.encrypt(plain).mapCatching { encrypted ->
    if (plain.isNotEmpty() && !encrypted.startsWith(ENC_PREFIX)) {
        throw IllegalStateException("Hasil enkripsi tidak valid (tanpa awalan $ENC_PREFIX)")
    }
    store[name] = encrypted
}

/** Awalan hasil enkripsi; harus sama dengan PREFIX di [Secret]. Dipisah agar test JVM tidak memuat kelas Android. */
private const val ENC_PREFIX = "enc1:"

/** Hasil migrasi satu key lama yang mungkin masih plaintext. */
internal sealed interface KeyMigrationResult {
    /** Kosong atau sudah terenkripsi: biarkan apa adanya. */
    data object Unchanged : KeyMigrationResult
    /** Berhasil dienkripsi: simpan nilai ini. */
    data class Encrypted(val value: String) : KeyMigrationResult
    /** Tidak bisa diamankan: hapus key lama, jangan simpan plaintext. */
    data object Remove : KeyMigrationResult
}

internal fun migrateStoredKey(raw: String, provider: SecretCryptoProvider): KeyMigrationResult {
    if (raw.isEmpty() || raw.startsWith(ENC_PREFIX)) return KeyMigrationResult.Unchanged
    val enc = provider.encrypt(raw).getOrNull()
    return if (enc != null && enc.startsWith(ENC_PREFIX)) KeyMigrationResult.Encrypted(enc) else KeyMigrationResult.Remove
}
