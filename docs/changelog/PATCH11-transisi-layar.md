# patch11: transisi antarlayar
- Perpindahan antara Proyek, Kode, Artefak, Browser, dan Pengaturan memakai `AnimatedContent` (fade masuk 240 ms dengan zoom 0,98 -> 1, fade keluar 150 ms) di `MainActivity.kt`.
- Beranda dan Obrolan berbagi satu `DrawerHost`: `contentKey` menyamakan keduanya sehingga tidak ada transisi di antara mereka.
- Tes: `ScreenTransitionTest`. Versi tidak berubah (83).
