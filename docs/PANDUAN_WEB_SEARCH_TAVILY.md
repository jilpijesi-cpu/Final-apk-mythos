# Web Search MYTHOS 2.29 (Tavily)

## Mengaktifkan

1. Buat akun Tavily dan salin API key dari dashboard: https://app.tavily.com/home
2. Buka MYTHOS > Pengaturan > **Web Search: atur kunci Tavily**.
3. Tempel API key, lalu pilih **Simpan**.
4. Minta agen mencari topik tertentu. Agen memanggil tool native `web_search` dan menerima judul, URL, tanggal publikasi (bila tersedia), serta cuplikan.

## Keamanan dan batasan

- API key disimpan sebagai ciphertext memakai Android Keystore; key tidak dimasukkan ke prompt model, URL, query, ataupun log aplikasi.
- Query pencarian dikirim ke layanan Tavily. Jangan mengirim password, OTP, token, nomor rekening, atau informasi sensitif.
- Hasil pencarian dan cuplikan adalah data pihak ketiga yang tidak tepercaya. Agen diarahkan untuk mengabaikan instruksi di dalam cuplikan dan memverifikasi halaman sumber sebelum menyimpulkan.
- Permintaan memakai HTTPS, batas waktu koneksi/baca, batas respons 1 MB, maksimal 8 hasil, serta mematikan redirect agar header Authorization tidak diteruskan ke URL lain.
- Model lokal yang berjalan tanpa penyedia internet tidak menerima deklarasi tool ini. Web Search membutuhkan koneksi internet dan API key Tavily yang valid.
- Penggunaan dibatasi oleh kuota dan rate limit akun Tavily.

## Komponen yang diintegrasikan

- `ToolDeclarations.kt`: deklarasi function-call native `web_search`.
- `ToolExecution.kt`: dispatch ke klien pencarian.
- `WebSearch.kt`: klien HTTPS Tavily, parser respons, validasi URL, pemangkasan hasil, dan kontrak `exit=`.
- `WebSearchSettings.kt` dan `SettingsDialog.kt`: konfigurasi kunci API terenkripsi.
- `ToolGuidance.kt`, `ProviderConfig.kt`, `AgentLoop.kt`, `ActionIcons.kt`, `MainActivity.kt`: panduan agen, status aktivitas, ikon, dan tipe kartu.
- `WebSearchTest.kt`, `ToolDeclarationsTest.kt`: tes unit untuk parser dan registrasi tool.

## Status verifikasi

Audit statis dilakukan terhadap alur deklarasi, filtering model lokal, dispatch, penyimpanan key, permission, dan kontrak hasil tool. Manifest proyek sudah memiliki permission `android.permission.INTERNET`.

Build Gradle penuh dan tes Android belum dapat dijalankan di lingkungan audit ini karena distribusi Gradle dari `services.gradle.org` tidak dapat di-resolve (DNS). Karena itu, jangan anggap ZIP ini sebagai APK yang sudah berhasil dibangun atau API yang sudah diuji langsung dengan key akun. Setelah memasang APK dari build yang berhasil, masukkan API key dan jalankan pencarian percobaan untuk verifikasi end-to-end.
