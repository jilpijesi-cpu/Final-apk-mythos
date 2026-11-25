# Form KTP Simulasi — Proyek Android Studio (APK)

Proyek Android WebView siap *build* yang membungkus aplikasi HTML5 `<canvas>` Anda di `app/src/main/assets/www/`.

## Fitur Native Android yang Sudah Disiapkan
- **Pemilih File (`onShowFileChooser`):** Mendukung unggah Pas Foto, Ganti Template, dan Unggah Tanda Tangan langsung dari galeri/kamera Android.
- **Simpan PNG ke Folder Download (`AndroidBridge.savePng`):** Mengetuk **Unduh Pratinjau Kartu (PNG)** otomatis menyimpan file PNG `1011×638` ke folder `Download` ponsel via `MediaStore`.
- **Penyimpanan Lokal (`domStorageEnabled`):** Data tab *Tersimpan* tetap tersimpan di `localStorage`.

## Cara Build APK di Android Studio
1. Ekstrak arsip `FormKtpAndroid-Studio.zip`.
2. Buka **Android Studio** -> pilih **Open** -> arahkan ke folder `FormKtpAndroid`.
3. Tunggu proses *Gradle Sync* selesai, lalu klik menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. File `app-debug.apk` akan muncul di `app/build/outputs/apk/debug/app-debug.apk` dan siap dipasang di HP Android Anda.
