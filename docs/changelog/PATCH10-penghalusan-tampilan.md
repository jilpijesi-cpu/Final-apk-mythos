# patch10: penghalusan tampilan
- **Jarak baris balasan AI**: 16 sp / 22 sp (1,375) menjadi 16 sp / 25 sp (sekitar 1,56) agar teks panjang lebih lega, terutama pada font serif dan tulisan tangan.
- **Jarak antarbagian**: antarparagraf 8 dp -> 12 dp, antarbutir daftar 3 dp -> 5 dp, di atas judul 14 dp -> 20 dp, antarblok 10 dp -> 12 dp; jarak baris kotak kode 19 -> 20 sp dan kotak teks 22 -> 23 sp.
- **Umpan balik tekan**: `GlyphButton` dan `CircleIconButton` kini mengecil halus (92%) saat ditekan lalu kembali (`Modifier.pressScale`), plus getaran halus (`HapticFeedbackType.TextHandleMove`). Ukuran tata letak tidak berubah (hanya `graphicsLayer`).
- Tidak ada perubahan logika. Tes: `PolishStyleTest`.
- Nama APK kini `Mythos-v83-patch10-debug.apk` (`archivesName` di `app/build.gradle.kts`).
