# Laporan Audit MYTHOS 2.29 — Integrasi Web Search

## Ringkasan hasil

- ZIP asli diperiksa: CRC arsip valid, **275 entri** (243 file dan 32 direktori).
- Semua **243 file asli dipertahankan**; tidak ada file asli yang hilang.
- Versi hasil audit berisi **251 file** (243 file asli + 8 file tambahan) dan 283 entri.
- Web Search diimplementasikan menggunakan Tavily Search API resmi, bukan scraping halaman HTML mesin pencari.
- Build Gradle penuh dan `testDebugUnitTest` belum dapat diverifikasi di lingkungan ini karena `services.gradle.org` gagal di-resolve oleh DNS (`UnknownHostException`).

## Integrasi yang diaudit

| Area | Hasil audit |
|---|---|
| Deklarasi function-call `web_search` | Terdaftar; query wajib, batas jumlah hasil opsional |
| Dispatch tool | Terhubung ke `WebSearch.search()` dari `MainActivity.runTool()` |
| Status langkah dan detail aktivitas | Menampilkan status pencarian dan query |
| Ikon dan jenis kartu aktivitas | Menggunakan ikon globe dan kartu tool |
| Prompt dan panduan tool | Menjelaskan prasyarat Tavily, kerahasiaan query, serta perlakuan hasil sebagai data tidak tepercaya |
| Benturan custom tool | `web_search` dilindungi sebagai tool bawaan |
| Model lokal | Tidak dikirimi deklarasi Web Search; daftar tool lokal tetap dibatasi |
| Izin jaringan | Manifest sudah memuat `android.permission.INTERNET` |
| Penyimpanan API key | Dienkripsi memakai `Secret.encrypt()` / Android Keystore |
| Jaringan | HTTPS, Bearer authorization, timeout, batas respons, redirect dinonaktifkan |
| Validasi hasil | Hanya URL HTTP/HTTPS tanpa user-info; URL duplikat dibuang; hasil dibatasi maksimal 8 |
| Kontrak hasil | Menggunakan prefix `exit=` yang dibaca runtime agen |

## File asli yang diubah

1. `app/src/main/java/com/build/mythos/ToolDeclarations.kt`
2. `app/src/main/java/com/build/mythos/ToolExecution.kt`
3. `app/src/main/java/com/build/mythos/ToolGuidance.kt`
4. `app/src/main/java/com/build/mythos/ProviderConfig.kt`
5. `app/src/main/java/com/build/mythos/AgentLoop.kt`
6. `app/src/main/java/com/build/mythos/ActionIcons.kt`
7. `app/src/main/java/com/build/mythos/MainActivity.kt`
8. `app/src/main/java/com/build/mythos/SettingsDialog.kt`
9. `app/src/main/java/com/build/mythos/CustomTools.kt`

## File tambahan

- `app/src/main/java/com/build/mythos/WebSearch.kt`
- `app/src/main/java/com/build/mythos/WebSearchSettings.kt`
- `app/src/main/java/com/build/mythos/GeneralAssist.kt` (patch asisten serba bisa)
- `app/src/test/java/com/build/mythos/WebSearchTest.kt`
- `app/src/test/java/com/build/mythos/ToolDeclarationsTest.kt`
- `app/src/test/java/com/build/mythos/GeneralAssistTest.kt`
- `PANDUAN_WEB_SEARCH_TAVILY.md`
- `LAPORAN_AUDIT_WEB_SEARCH_TAVILY.md`

## Pengujian dan batas verifikasi

1. CRC ZIP asli: lulus.
2. Pemeriksaan retensi nama seluruh file asli: lulus, tidak ada yang hilang.
3. Pemeriksaan statis registrasi tool, dispatch, endpoint Tavily, autentikasi Bearer, penyimpanan terenkripsi, baris pengaturan, izin INTERNET, pembatasan model lokal, dan ketiadaan endpoint scraping DuckDuckGo: lulus.
4. Kompilasi sintaks `WebSearch.kt` secara terisolasi memakai Kotlin compiler dan stub `org.json`: lulus. Ini hanya pemeriksaan sintaks, bukan pengujian respons API sebenarnya.
5. Tes JUnit yang ditambahkan belum dapat dijalankan karena Gradle 8.9 tidak berhasil diunduh (`UnknownHostException: services.gradle.org`). Android SDK juga tidak tersedia pada lingkungan audit.
6. Belum dilakukan pemanggilan live Tavily karena API key akun pengguna belum tersedia. Karena itu autentikasi akun, kuota, respons jaringan, dan hasil pencarian end-to-end masih perlu diuji pada perangkat.

## Cara mengaktifkan

1. Buat akun dan ambil API key di https://app.tavily.com/home.
2. Di MYTHOS buka **Pengaturan > Web Search: atur kunci Tavily**.
3. Tempel key lalu pilih **Simpan**.
4. Uji dengan permintaan pencarian sederhana.

API key tidak ditanam ke source ZIP. Query dikirim ke Tavily, sehingga jangan sertakan kata sandi, OTP, token, atau data pribadi.
