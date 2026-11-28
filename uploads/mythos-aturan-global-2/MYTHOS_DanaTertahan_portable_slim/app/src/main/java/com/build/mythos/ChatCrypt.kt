package com.build.mythos

import java.io.File
import java.io.IOException

// Enkripsi berkas riwayat chat (filesDir/chats/*.json dan index.json) dengan Android Keystore lewat [Secret].
// Sengaja tanpa dependensi Android supaya bisa diuji di JVM dengan [SecretCryptoProvider] palsu.
//
// Kompatibilitas: berkas lama (JSON polos) tetap terbaca karena Secret.decrypt mengembalikan teks apa adanya bila tidak
// berawalan "enc1:". Berkas lama baru terenkripsi saat chat itu disimpan atau diganti namanya berikutnya.
// Gagal tertutup: bila enkripsi gagal, TIDAK ada plaintext yang ditulis; exception dilempar dan pemanggil (saveHist,
// renameChat, writeIndex) sudah menampilkan "Riwayat chat gagal disimpan". Berkas lama tidak tersentuh.

internal const val CHAT_ENC_PREFIX = "enc1:" // harus sama dengan PREFIX di Secret

internal fun isEncryptedText(raw: String): Boolean = raw.startsWith(CHAT_ENC_PREFIX)

/** Baca teks berkas chat/indeks; terenkripsi atau JSON lama. Melempar [IOException] bila tidak bisa dibuka. */
internal fun readChatText(f: File, provider: SecretCryptoProvider = Secret.provider): String {
    val raw = f.readText()
    return when (val r = provider.decrypt(raw)) {
        is SecretDecryptResult.Success -> r.value
        else -> throw IOException("Berkas chat tidak bisa dibuka (${r::class.simpleName}): ${f.name}")
    }
}

/** Tulis teks chat/indeks secara atomik dalam bentuk terenkripsi. Tidak pernah menulis plaintext. */
internal fun writeChatText(target: File, text: String, provider: SecretCryptoProvider = Secret.provider) {
    val enc = provider.encrypt(text).getOrElse { e ->
        throw IOException("Enkripsi riwayat chat gagal; tidak menulis plaintext: ${target.name}", e)
    }
    if (!enc.startsWith(CHAT_ENC_PREFIX)) {
        throw IOException("Hasil enkripsi riwayat chat tidak valid; tidak menulis plaintext: ${target.name}")
    }
    atomicWriteText(target, enc)
}
