# Form KTP Simulasi — Aplikasi Android Native (Kotlin + Material 3)

Proyek ini adalah konversi penuh dari *WebView wrapper* menjadi **aplikasi Android native 100% Kotlin & Material 3** tanpa `WebView`, tanpa HTML/JS, dan tanpa dependensi CDN internet.

## Peningkatan Utama dari Versi WebView
- **100% Native & Offline-First:** Tidak membutuhkan izin `INTERNET`. Seluruh UI menggunakan komponen Material 3 native, database 38 provinsi lokal (`WilayahData.kt`), parser NIK native (`NikParser.kt`), dan `android.graphics.Canvas` (`KtpCardRenderer.kt`).
- **Keamanan & Privasi Diperkeras:** Menghapus seluruh permukaan serangan `WebView` (`allowUniversalAccessFromFileURLs`, `JavascriptInterface`), menyetel `android:allowBackup="false"`, dan menyimpan data + aset gambar terpisah di memori privat aplikasi (`Context.filesDir`).
- **Perbaikan Seluruh Bug Fungsional Audit:**
  - Ekspor **PNG**, **JSON**, dan **CSV** (dengan proteksi CSV Formula Injection) kini benar-benar tersimpan ke folder `Download` via `MediaStore` (API 29+) atau izin runtime (API 24–28), ditambah fitur **Bagikan** via `FileProvider`.
  - Mode **Muat / Sunting** kini memulihkan **tanda tangan** dan **tanggal terbit** secara utuh tanpa race condition.
  - Perubahan tanggal pembuatan kartu langsung memperbarui *Live Preview*.
  - Watermark simulasi (`SIMULASI FORMULIR — CONTOH / BUKAN DOKUMEN RESMI`) digambar permanen pada lapisan teratas pratinjau kartu.

## Cara Build APK di Android Studio
1. Ekstrak arsip `FormKtpAndroidNative.zip`.
2. Buka **Android Studio** (Koala / Ladybug / terbaru) → **Open** → pilih folder `FormKtpAndroidNative`.
3. Tunggu proses *Gradle Sync* selesai (AGP `8.5.2`, Kotlin `1.9.24`, `compileSdk 34`, `minSdk 24`).
4. Klik **Build > Build Bundle(s) / APK(s) > Build APK(s)** untuk menghasilkan APK siap pasang di ponsel Android.
