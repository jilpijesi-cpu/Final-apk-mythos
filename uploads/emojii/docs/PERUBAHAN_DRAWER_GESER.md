# Lebar drawer dan geser yang mengikuti jari

STATUS: BELUM DIKOMPILASI dan tes belum dijalankan (dibuat tanpa Gradle/Kotlin di lingkungan penyusun).
Jalankan `./gradlew testDebugUnitTest assembleDebug` (atau workflow GitHub) sebelum dipakai.

## Lebar drawer dibatasi (saran nomor 9)
- Lebar = 82% layar, tetapi paling besar 360dp dan tidak pernah melebihi layar (`DrawerGeometry.widthPx`). Di ponsel biasa tidak ada yang berubah.
- Sebelumnya lebar dihitung sekali saat layar dibuat. Karena `MainActivity` menangani `orientation|screenSize` sendiri (tidak dibuat ulang),
  lebar itu basi setelah layar diputar. Kini `onConfigurationChanged` memanggil `refreshDrawerWidth()` yang menerapkan lebar baru
  dan menggeser lapisan chat sesuai.

## Geser yang mengikuti jari (saran nomor 10)
- Dulu: geseran melewati 64dp hanya memicu animasi buka/tutup penuh. Kini lapisan chat bergerak persis mengikuti jari (`applyDrawerProgress`
  dengan kemajuan 0..1: sudut membulat, bayangan, dan warna status bar ikut berubah selama menggeser).
- Mulai menggeser setelah jari melewati dua kali ambang sentuh sistem, dengan syarat gerak mendatar dominan (1,5x vertikal).
  Buka: hanya dari tepi kiri (32dp) ke kanan. Tutup: geser ke kiri di mana saja saat drawer terbuka atau sedang bergerak.
  Drawer tidak melompat sebesar jarak ambang; ia mulai dari posisinya saat ini.
- Saat jari diangkat: lemparan lebih cepat dari 500dp/detik menentukan arah, selain itu drawer terbuka bila sudah lewat setengah.
  Durasi animasi penyelesaian sebanding dengan sisa jarak (minimal 40% dari 260 md).
- View anak dibatalkan sekali di awal geseran (sebelumnya ACTION_CANCEL dikirim pada setiap gerakan).
- Daftar drawer di-refresh saat geseran buka dimulai, jadi isinya sudah benar selama ditarik.
- Kode: `DrawerGeometry.kt` (logika murni), `Drawer.kt` (`handleDrawerSwipe`, `animateDrawer`, `refreshDrawerWidth`, `DrawerDrag`),
  `MainActivity.kt` (`dispatchTouchEvent`, `onConfigurationChanged`).

## Tes baru
- `DrawerGeometryTest`: lebar (ponsel, layar lebar, tidak melebihi layar), syarat mulai geser (arah, tepi, getaran, vertikal),
  kemajuan dijepit 0..1, lemparan vs setengah jalan, durasi.

## Batasan yang diketahui
- Pada navigasi gestur Android 10+, geser dari tepi kiri bisa direbut gestur "kembali" sistem sebelum aplikasi melihatnya. Gejalanya:
  drawer sulit dibuka dari tepi paling kiri. Bila terjadi, perbaikannya `systemGestureExclusionRects` (maks 200dp tinggi); belum dipasang
  karena belum terbukti bermasalah di perangkat.
- Jarak geser belum diuji di perangkat; angka ambang (32dp tepi, 500dp/detik lemparan) bisa perlu disetel.
